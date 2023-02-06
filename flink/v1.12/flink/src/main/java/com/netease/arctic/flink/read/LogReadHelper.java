/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netease.arctic.flink.read;

import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.functions.RuntimeContext;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.state.OperatorStateStore;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.api.common.typeutils.base.StringSerializer;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.typeutils.runtime.TupleSerializer;
import org.apache.flink.api.java.typeutils.runtime.kryo.KryoSerializer;
import org.apache.flink.runtime.state.FunctionInitializationContext;
import org.apache.flink.table.data.RowData;
import org.apache.flink.types.RowKind;
import org.apache.flink.util.FlinkRuntimeException;
import org.apache.iceberg.relocated.com.google.common.collect.Maps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * According to upstreamId and partition topic dealing with the flip message, when should begin to retract message and
 * when to end it.
 * <p>
 * @deprecated since 0.4.1, will be removed in 0.7.0;
 * use {@link com.netease.arctic.flink.read.source.log.LogSourceHelper} instead.
 */
@Deprecated
public class LogReadHelper implements Serializable {
  private static final Logger LOG = LoggerFactory.getLogger(LogReadHelper.class);
  private static final long serialVersionUID = 1L;

  /**
   * this map is relate upstream id and topic partitions to the retracting epicNo,
   * also describe whether retracting right now.
   * Key : String format, upstream job id + "_" + topic partition, generated by
   * {@link #combineUpstreamIdAndPartition)} method.
   * Value : {@link EpicRetractingOffset}.
   */
  private NavigableMap<String, EpicRetractingOffset> retractingState;

  private ListState<Tuple2<String, EpicRetractingOffset>> retractingRestoreState;
  private static final String STATE_EPIC_RETRACTING = "upstream-id-partition-epic-offset";

  private ListState<Tuple2<String, LogEpicStateHandler.EpicPartitionOffsets>> epicRestoreState;
  private static final String STATE_EPIC = "epic-offsets";

  private LogEpicStateHandler epicStateHandler;

  private Map<String, Boolean> upstreamIdPartitionCleanBufferMap;

  public void initializeState(FunctionInitializationContext context, RuntimeContext runtimeContext) throws Exception {
    OperatorStateStore stateStore = context.getOperatorStateStore();
    retractingRestoreState =
        stateStore.getUnionListState(
            new ListStateDescriptor<>(
                STATE_EPIC_RETRACTING,
                createStateSerializer(runtimeContext.getExecutionConfig())
            )
        );

    epicRestoreState =
        stateStore.getUnionListState(
            new ListStateDescriptor<>(
                STATE_EPIC,
                createEpicStateSerializer(runtimeContext.getExecutionConfig())
            )
        );

    Map<String, LogEpicStateHandler.EpicPartitionOffsets> upstreamEpicOffsets = new ConcurrentHashMap<>();
    retractingState = Maps.newTreeMap();
    if (context.isRestored()) {
      for (Tuple2<String, EpicRetractingOffset> upstreamIdPartitionRetractingOffset : retractingRestoreState.get()) {
        retractingState.put(upstreamIdPartitionRetractingOffset.f0, upstreamIdPartitionRetractingOffset.f1);
      }
      for (Tuple2<String, LogEpicStateHandler.EpicPartitionOffsets> epicOffset : epicRestoreState.get()) {
        upstreamEpicOffsets.put(epicOffset.f0, epicOffset.f1);
      }
    }

    epicStateHandler = new LogEpicStateHandler(upstreamEpicOffsets);

    upstreamIdPartitionCleanBufferMap = new HashMap<>();
  }

  public void snapshotState() throws Exception {
    retractingRestoreState.clear();
    for (Map.Entry<String, EpicRetractingOffset> retractingOffsetEntry : retractingState.entrySet()) {
      retractingRestoreState.add(
          Tuple2.of(retractingOffsetEntry.getKey(), retractingOffsetEntry.getValue())
      );
    }

    epicRestoreState.clear();
    for (Map.Entry<String, LogEpicStateHandler.EpicPartitionOffsets> epicOffsets :
        epicStateHandler.getAll().entrySet()) {
      epicRestoreState.add(
          Tuple2.of(epicOffsets.getKey(), epicOffsets.getValue())
      );
    }
  }

  /**
   * turn row kind of a row.
   * +I -> -D
   * -D -> +I
   * -U -> +U
   * +U -> -U
   *
   * @param rowData before reset row
   * @return after reset row kind.
   */
  public RowData turnRowKind(RowData rowData) {
    switch (rowData.getRowKind()) {
      case INSERT:
        rowData.setRowKind(RowKind.DELETE);
        break;
      case DELETE:
        rowData.setRowKind(RowKind.INSERT);
        break;
      case UPDATE_AFTER:
        rowData.setRowKind(RowKind.UPDATE_BEFORE);
        break;
      case UPDATE_BEFORE:
        rowData.setRowKind(RowKind.UPDATE_AFTER);
        break;
      default:
        throw new FlinkRuntimeException("unKnown ChangeAction=" + rowData.getRowKind());
    }
    LOG.debug("after retract a row, ChangeAction={}", rowData.getRowKind());
    return rowData;
  }

  /**
   * check this job <p>upstream job id</p> whether retracting right now.
   *
   * @param upstreamId upstream job id
   * @param partition  topic partition num
   * @return retracting or not
   */
  public boolean isJobRetractingRightNow(String upstreamId, int partition) {
    return retractingState.containsKey(combineUpstreamIdAndPartition(upstreamId, partition));
  }

  /**
   * suspend retract
   */
  public void suspendRetracting(String upstreamId, long epicNo, int partition, long offset) {
    String key = combineUpstreamIdAndPartition(upstreamId, partition);
    retractingState.remove(key);
    epicStateHandler.registerEpicPartitionRetractedOffset(upstreamId, epicNo, partition, offset);

    long newOffset = offset + 1;
    epicStateHandler.registerEpicPartitionStartOffsetForce(
        upstreamId,
        epicNo,
        partition,
        newOffset
    );
    LOG.info(
        "due to the fetcher has finished this retraction upstreamId={}, epicNo={}, " +
            "so modify old partition={} startOffset to new start offset={}.",
        upstreamId, epicNo, partition, newOffset);

    // and then clean the map which epicNo is bigger than this #epicNo
    epicStateHandler.clean(upstreamId, epicNo, partition);
    LOG.info("suspended retracting step.");
  }

  /**
   * starting retract
   */
  public void markEpicPartitionRetracting(String upstreamId, long epicNo, int partition, Long offset) {
    String key = combineUpstreamIdAndPartition(upstreamId, partition);
    EpicRetractingOffset retractingOffset = EpicRetractingOffset.of(epicNo, offset);
    retractingState.put(key, retractingOffset);
    LOG.info(
        "ready to start retraction upstreamId={}, epicNo={}, partition={}, offset={}.",
        upstreamId, epicNo, partition, offset);
  }

  public void updateEpicStartOffsetIfEmpty(String upstreamId, long epicNo, int partition, long startOffset) {
    epicStateHandler.registerEpicPartitionStartOffset(upstreamId, epicNo, partition, startOffset);
  }

  public void updateRetractingEpicOffset(String upstreamId, long epicNo, int partition, long offset) {
    String key = combineUpstreamIdAndPartition(upstreamId, partition);
    EpicRetractingOffset retractingOffset = retractingState.get(key);
    if (retractingOffset != null) {
      retractingOffset.offset = offset;
      // todo need put back??
    } else {
      throw new FlinkRuntimeException("There may be a bug, because can't find retracting epicNo offset.");
    }
  }

  public long queryRetractingEpicNo(String upstreamId, int partition) {
    String key = combineUpstreamIdAndPartition(upstreamId, partition);
    if (!retractingState.containsKey(key)) {
      throw new FlinkRuntimeException("Can't find epicNo, upstreamId=" + upstreamId + ", partition=" + partition);
    }

    return retractingState.get(key).epicNo;
  }

  public long queryPartitionRetractingOffset(String upstreamId, int partition) {
    String key = combineUpstreamIdAndPartition(upstreamId, partition);
    if (!retractingState.containsKey(key)) {
      throw new FlinkRuntimeException(
          "Can't find retracting offset, upstreamId=" + upstreamId + ", partition=" + partition);
    }
    return retractingState.get(key).offset;
  }

  public Optional<Long> getEpicOffset(String upstreamId, long epicNo, int partition) {
    Optional<LogEpicStateHandler.EpicPartitionOffsets> epicNoFlipInfoOptional =
        epicStateHandler.getEpicNoFlip(upstreamId, epicNo, partition);

    return epicNoFlipInfoOptional.map(epicPartitionOffsets -> {
      Long startOffset = epicPartitionOffsets.startOffset;
      Long retractedOffset = epicPartitionOffsets.retractedOffset;
      if (retractedOffset != null) {
        return retractedOffset;
      }
      return startOffset;
    });
  }

  private TupleSerializer<Tuple2<String, LogEpicStateHandler.EpicPartitionOffsets>> createEpicStateSerializer(
      ExecutionConfig executionConfig) {
    TypeSerializer<?>[] fieldSerializers =
        new TypeSerializer<?>[]{
            StringSerializer.INSTANCE,
            new KryoSerializer<>(LogEpicStateHandler.EpicPartitionOffsets.class, executionConfig)
        };
    @SuppressWarnings("unchecked")
    Class<Tuple2<String, LogEpicStateHandler.EpicPartitionOffsets>> tupleClass =
        (Class<Tuple2<String, LogEpicStateHandler.EpicPartitionOffsets>>) (Class<?>) Tuple2.class;
    return new TupleSerializer<>(tupleClass, fieldSerializers);
  }

  private TupleSerializer<Tuple2<String, EpicRetractingOffset>> createStateSerializer(
      ExecutionConfig executionConfig) {
    TypeSerializer<?>[] fieldSerializers =
        new TypeSerializer<?>[]{
            StringSerializer.INSTANCE,
            new KryoSerializer<>(EpicRetractingOffset.class, executionConfig)
        };
    @SuppressWarnings("unchecked")
    Class<Tuple2<String, EpicRetractingOffset>> tupleClass =
        (Class<Tuple2<String, EpicRetractingOffset>>) (Class<?>) Tuple2.class;
    return new TupleSerializer<>(tupleClass, fieldSerializers);
  }

  private String combineUpstreamIdAndPartition(String upstreamId, int partition) {
    return upstreamId + "_" + partition;
  }

  public boolean getCleanBufferAction(String upstreamId, int partition) {
    return upstreamIdPartitionCleanBufferMap
        .getOrDefault(
            combineUpstreamIdAndPartition(upstreamId, partition),
            false
        );
  }

  public void cleanBufferAction(String upstreamId, int partition, boolean enableOrDisable) {
    upstreamIdPartitionCleanBufferMap.put(
        combineUpstreamIdAndPartition(upstreamId, partition),
        enableOrDisable
    );
  }

  /**
   * The epic and offset information is recorded
   * while the retracting state is in progress and the offset content is updated in real time.
   */
  private static class EpicRetractingOffset implements Serializable {
    private static final long serialVersionUID = 6996885584095516319L;
    private long epicNo;
    private long offset;

    private static EpicRetractingOffset of(long epicNo, long offset) {
      EpicRetractingOffset retractingOffset = new EpicRetractingOffset();
      retractingOffset.epicNo = epicNo;
      retractingOffset.offset = offset;
      return retractingOffset;
    }
  }
}
