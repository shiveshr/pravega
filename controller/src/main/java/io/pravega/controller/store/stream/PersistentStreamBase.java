/**
 * Copyright (c) 2017 Dell Inc., or its subsidiaries. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.pravega.controller.store.stream;

import com.google.common.collect.Lists;
import io.pravega.client.stream.StreamConfiguration;
import io.pravega.common.ExceptionHelpers;
import io.pravega.common.concurrent.FutureHelpers;
import io.pravega.common.util.BitConverter;
import io.pravega.controller.store.stream.tables.ActiveTxnRecord;
import io.pravega.controller.store.stream.tables.CompletedTxnRecord;
import io.pravega.controller.store.stream.tables.Create;
import io.pravega.controller.store.stream.tables.Data;
import io.pravega.controller.store.stream.tables.HistoryRecord;
import io.pravega.controller.store.stream.tables.IndexRecord;
import io.pravega.controller.store.stream.tables.State;
import io.pravega.controller.store.stream.tables.TableHelper;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.lang.SerializationUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

import java.util.AbstractMap;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
public abstract class PersistentStreamBase<T> implements Stream {

    private static final Map<State, List<State>> STATE_TRANSITIONS;

    static {
        Map<State, List<State>> map = new HashMap<>();
        map.put(State.UNKNOWN, Lists.newArrayList(State.UNKNOWN, State.CREATING));
        map.put(State.CREATING, Lists.newArrayList(State.CREATING, State.ACTIVE));
        map.put(State.ACTIVE, Lists.newArrayList(State.ACTIVE, State.SCALING, State.SEALING, State.SEALED, State.UPDATING));
        map.put(State.SCALING, Lists.newArrayList(State.SCALING, State.ACTIVE));
        map.put(State.UPDATING, Lists.newArrayList(State.UPDATING, State.ACTIVE));
        map.put(State.SEALING, Lists.newArrayList(State.SEALING, State.SEALED));
        map.put(State.SEALED, Lists.newArrayList(State.SEALED));
        STATE_TRANSITIONS = Collections.unmodifiableMap(map);
    }

    private final String scope;
    private final String name;

    PersistentStreamBase(String scope, final String name) {
        this.scope = scope;
        this.name = name;
    }

    @Override
    public String getScope() {
        return this.scope;
    }

    @Override
    public String getName() {
        return this.name;
    }

    @Override
    public String getScopeName() {
        return this.scope;
    }

    /***
     * Creates a new stream record in the stream store.
     * Create a new task of type Create.
     * If create task already exists, use that and bring it to completion
     * If no task exists, fall through all create steps. They are all idempotent
     * <p>
     * Create Steps:
     * 1. Create new store configuration
     * 2. Create new segment table.
     * 3. Create new history table.
     * 4. Create new index
     *
     * @param configuration stream configuration.
     * @return : future of whether it was done or not
     */
    @Override
    public CompletableFuture<Boolean> create(final StreamConfiguration configuration, long createTimestamp) {
        final Create create = new Create(createTimestamp, configuration);

        return checkScopeExists()
                .thenCompose(x -> checkStreamExists(create))
                .thenCompose(x -> storeCreationTime(create))
                .thenCompose(x -> createConfiguration(create))
                .thenCompose(x -> createState(State.CREATING))
                .thenCompose(x -> createSegmentTable(create))
                .thenCompose(x -> createNewEpoch(0))
                .thenCompose(x -> {
                    final int numSegments = create.getConfiguration().getScalingPolicy().getMinNumSegments();
                    final byte[] historyTable = TableHelper.createHistoryTable(create.getCreationTime(),
                            IntStream.range(0, numSegments).boxed().collect(Collectors.toList()));

                    return createHistoryTable(new Data<>(historyTable, null));
                })
                .thenCompose(x -> createIndexTable(new Data<>(TableHelper.createIndexTable(create.getCreationTime(), 0), null)))
                .thenApply(x -> true);
    }

    @Override
    public CompletableFuture<Void> delete() {
        return deleteStream();
    }

    /**
     * Update configuration at configurationPath.
     *
     * @param configuration new stream configuration.
     * @return : future of boolean
     */
    @Override
    public CompletableFuture<Boolean> updateConfiguration(final StreamConfiguration configuration) {
        // replace the configurationPath with new configurationPath
        return verifyState(() -> updateState(State.UPDATING)
                .thenApply(x -> setConfigurationData(configuration))
                .thenApply(x -> true),
                Lists.newArrayList(State.ACTIVE));
    }

    /**
     * Fetch configuration at configurationPath.
     *
     * @return : future of stream configuration
     */
    @Override
    public CompletableFuture<StreamConfiguration> getConfiguration() {
        return getConfigurationData();
    }

    @Override
    public CompletableFuture<Boolean> updateState(final State state) {
        return getStateData()
                .thenCompose(currState -> {
                    if (allowed((State) SerializationUtils.deserialize(currState.getData()), state)) {
                        return setStateData(new Data<>(SerializationUtils.serialize(state), currState.getVersion()))
                                .thenApply(x -> true);
                    } else {
                        return FutureHelpers.failedFuture(new WriteConflictException("state"));
                    }
                });
    }

    private boolean allowed(State currentState, State newState) {
        return STATE_TRANSITIONS.containsKey(currentState) && STATE_TRANSITIONS.get(currentState).contains(newState);
    }

    @Override
    public CompletableFuture<State> getState() {
        return getStateData()
                .thenApply(x -> (State) SerializationUtils.deserialize(x.getData()));
    }

    /**
     * Fetch the segment table and retrieve the segment.
     *
     * @param number segment number.
     * @return : future of segment
     */
    @Override
    public CompletableFuture<Segment> getSegment(final int number) {
        return verifyLegalState(() -> getSegmentRow(number));
    }

    @Override
    public CompletableFuture<Integer> getSegmentCount() {
        return verifyLegalState(() -> getSegmentTable().thenApply(x -> TableHelper.getSegmentCount(x.getData())));
    }

    /**
     * Given segment number, find its successor candidates and then compute overlaps with its keyrange
     * to find successors.
     *
     * @param number segment number.
     * @return : future of list of successor segment numbers
     */
    @Override
    public CompletableFuture<List<Integer>> getSuccessors(final int number) {
        return verifyLegalState(
                () -> getSuccessorsForSegment(number).thenApply(list ->
                        list.stream().map(Segment::getNumber).collect(Collectors.toList())));
    }

    private CompletableFuture<List<Segment>> findOverlapping(Segment segment, List<Integer> candidates) {
        return verifyLegalState(() -> FutureHelpers.allOfWithResults(candidates.stream().map(this::getSegment).collect(Collectors.toList()))
                .thenApply(successorCandidates -> successorCandidates.stream()
                        .filter(x -> x.overlaps(segment))
                        .collect(Collectors.toList())));
    }

    private CompletableFuture<List<Segment>> getSuccessorsForSegment(final int number) {
        val segmentFuture = getSegment(number);
        val indexTableFuture = getIndexTable();
        val historyTableFuture = getHistoryTable();
        CompletableFuture<Void> all = CompletableFuture.allOf(segmentFuture, indexTableFuture, historyTableFuture);

        return all.thenCompose(x -> {
            final Segment segment = segmentFuture.getNow(null);
            List<Integer> candidates = TableHelper.findSegmentSuccessorCandidates(segment,
                    indexTableFuture.getNow(null).getData(),
                    historyTableFuture.getNow(null).getData());
            return findOverlapping(segment, candidates);
        });
    }

    @Override
    public CompletableFuture<Map<Integer, List<Integer>>> getSuccessorsWithPredecessors(final int number) {
        val indexTableFuture = getIndexTable();
        val historyTableFuture = getHistoryTable();
        CompletableFuture<List<Segment>> segments = getSuccessorsForSegment(number);

        CompletableFuture<Void> all = CompletableFuture.allOf(segments, indexTableFuture, historyTableFuture);

        return verifyLegalState(() -> all.thenCompose(v -> {
            List<CompletableFuture<Map.Entry<Segment, List<Integer>>>> resultFutures = new ArrayList<>();
            List<Segment> successors = segments.getNow(null);
            for (Segment successor : successors) {
                List<Integer> candidates = TableHelper.findSegmentPredecessorCandidates(successor,
                        indexTableFuture.getNow(null).getData(),
                        historyTableFuture.getNow(null).getData());
                resultFutures.add(findOverlapping(successor, candidates).thenApply(
                        list -> new SimpleImmutableEntry<>(successor, list.stream().map(Segment::getNumber).collect(Collectors.toList()))));
            }
            return FutureHelpers.allOfWithResults(resultFutures);
        }).thenApply(list -> list.stream().collect(Collectors.toMap(e -> e.getKey().getNumber(), Map.Entry::getValue))));
    }

    /**
     * Find predecessor candidates and find overlaps with given segment's key range.
     *
     * @param number segment number.
     * @return : future of list of predecessor segment numbers
     */
    @Override
    public CompletableFuture<List<Integer>> getPredecessors(final int number) {
        val segmentFuture = getSegment(number);
        val indexTableFuture = getIndexTable();
        val historyTableFuture = getHistoryTable();
        CompletableFuture<Void> all = CompletableFuture.allOf(segmentFuture, indexTableFuture, historyTableFuture);

        return verifyLegalState(() -> all.thenCompose(x -> {
            final Segment segment = segmentFuture.getNow(null);
            List<Integer> candidates = TableHelper.findSegmentPredecessorCandidates(segment,
                    indexTableFuture.getNow(null).getData(),
                    historyTableFuture.getNow(null).getData());
            return findOverlapping(segment, candidates);
        }).thenApply(list -> list.stream().map(e -> e.getNumber()).collect(Collectors.toList())));
    }

    @Override
    public CompletableFuture<List<Integer>> getActiveSegments() {
        return verifyLegalState(() -> getHistoryTable().thenApply(x -> TableHelper.getActiveSegments(x.getData())));
    }

    /**
     * if timestamp is < create time of stream, we will return empty list.
     * 1. perform binary searchIndex on index table to find timestamp
     * 2. fetch the record from history table for the pointer in index.
     * Note: index may be stale so we may need to fall through
     * 3. parse the row and return the list of integers
     *
     * @param timestamp point in time.
     * @return : list of active segment numbers at given time stamp
     */
    @Override
    public CompletableFuture<List<Integer>> getActiveSegments(final long timestamp) {
        final CompletableFuture<Data<T>> indexFuture = getIndexTable();

        final CompletableFuture<Data<T>> historyFuture = getHistoryTable();

        return verifyLegalState(() -> indexFuture.thenCombine(historyFuture,
                (indexTable, historyTable) -> TableHelper.getActiveSegments(timestamp,
                        indexTable.getData(),
                        historyTable.getData())));
    }

    /**
     * Scale and create are two tasks where we update the table. For scale to be legitimate, it has to be
     * preceded by create. Which means all appropriate tables exist.
     * Scale Steps:
     * 1. Add new segment information in segment table.
     *
     * @param newRanges      key ranges of new segments to be created
     * @param scaleTimestamp deleted timestamp
     * @param runOnlyIfStarted run only if the scale operation was started.
     * @return : list of newly created segments
     */
    @Override
    public CompletableFuture<StartScaleResponse> startScale(final List<Integer> sealedSegments,
                                                            final List<AbstractMap.SimpleEntry<Double, Double>> newRanges,
                                                            final long scaleTimestamp,
                                                            boolean runOnlyIfStarted) {
        return verifyState(() -> updateState(State.SCALING)
                .thenCompose(status -> getHistoryTable()
                .thenCompose(historyTable -> getSegmentTable().thenApply(segmentTable -> new ImmutablePair<>(historyTable, segmentTable)))
                .thenCompose(pair -> {
                    final Data<T> segmentTable = pair.getRight();
                    final Data<T> historyTable = pair.getLeft();
                    final long epoch = TableHelper.getActiveEpoch(historyTable.getData());

                    final int nextSegmentNumber;
                    if (TableHelper.isScaleOngoing(historyTable.getData(), segmentTable.getData())) {
                        if (TableHelper.isRerunOf(sealedSegments, newRanges, historyTable.getData(), segmentTable.getData())) {
                            // rerun means segment table is already updated. No need to do anything
                            nextSegmentNumber = TableHelper.getSegmentCount(segmentTable.getData()) - newRanges.size();
                            return CompletableFuture.completedFuture(
                                    new ImmutablePair<>(epoch, nextSegmentNumber));
                        } else {
                            throw new ScaleOperationExceptions.ScaleStartException();
                        }
                    } else {
                        // check input is valid
                        if (!TableHelper.canScaleFor(sealedSegments, historyTable.getData())) {
                            // invalid input, log and ignore
                            log.warn("scale input no longer valid {}", sealedSegments);
                            throw new ScaleOperationExceptions.ScaleInvalidException();
                        }

                        if (runOnlyIfStarted) {
                            throw new ScaleOperationExceptions.ScaleStartException();
                        }

                        // fresh run
                        nextSegmentNumber = TableHelper.getSegmentCount(segmentTable.getData());
                        final byte[] updated = TableHelper.updateSegmentTable(nextSegmentNumber,
                                segmentTable.getData(),
                                newRanges,
                                scaleTimestamp
                        );

                        final Data<T> updatedData = new Data<>(updated, segmentTable.getVersion());

                        return setSegmentTable(updatedData).thenApply(z -> new ImmutablePair<>(epoch, nextSegmentNumber));
                    }
                })
                .thenCompose(epochStartSegmentpair -> getSegments(IntStream.range(epochStartSegmentpair.getRight(),
                        epochStartSegmentpair.getRight() + newRanges.size())
                        .boxed()
                        .collect(Collectors.toList()))
                        .thenApply(newSegments -> new StartScaleResponse(epochStartSegmentpair.getLeft(), newSegments)))),
                Lists.newArrayList(State.ACTIVE, State.SCALING)
        );
    }

    /**
     * Segments created with pravega, update the history table with this fact so they are available as successors
     * 3. Add entry into the history table.
     *
     * @param sealedSegments segments to be sealed
     * @return : list of newly created segments
     */
    @Override
    public CompletableFuture<Void> scaleNewSegmentsCreated(final List<Integer> sealedSegments,
                                                           final List<Integer> newSegments,
                                                           final long scaleTimestamp) {
        return verifyState(() -> FutureHelpers.toVoid(addPartialHistoryRecord(sealedSegments, newSegments)),
                Lists.newArrayList(State.SCALING));
    }

    /**
     * If scale is ongoing, try to delete the epoch node.
     *
     * @param epoch epoch
     * @return true if we are able to delete the epoch, false otherwise.
     */
    @Override
    public CompletableFuture<Boolean> scaleTryDeleteEpoch(final long epoch) {
        return verifyState(() -> getHistoryTable()
                .thenCompose(historyTable -> getSegmentTable().thenApply(segmentTable -> new ImmutablePair<>(historyTable, segmentTable)))
                .thenCompose(pair -> {
                    Data<T> segmentTable = pair.getRight();
                    Data<T> historyTable = pair.getLeft();
                    CompletableFuture<Boolean> result = new CompletableFuture<>();

                    if (TableHelper.isScaleOngoing(historyTable.getData(), segmentTable.getData())) {
                        deleteEpochNode(epoch)
                                .whenComplete((r, e) -> {
                                    if (e != null) {
                                        Throwable ex = ExceptionHelpers.getRealException(e);
                                        if (ex instanceof DataExistsException) {
                                            // cant delete as there are transactions still running under epoch node
                                            result.complete(false);
                                        } else {
                                            result.completeExceptionally(ex);
                                        }
                                    }
                                    result.complete(true);
                                });
                    } else {
                        result.complete(false);
                    }
                    return result;
                }),
                Lists.newArrayList(State.SCALING));
    }

    private CompletableFuture<Void> clearMarkers(final List<Integer> segments) {
        return FutureHelpers.toVoid(FutureHelpers.allOfWithResults(segments.stream().parallel()
                .map(this::removeColdMarker).collect(Collectors.toList())));
    }

    /**
     * Remainder of scale metadata update. Also set the state back to active.
     * 4. complete entry into the history table.
     * 5. Add entry into the index table.
     *
     * @param sealedSegments segments to be sealed
     * @return : list of newly created segments
     */
    @Override
    public CompletableFuture<Void> scaleOldSegmentsSealed(final List<Integer> sealedSegments,
                                                          final List<Integer> newSegments,
                                                          final long scaleTimestamp) {
        return verifyState(() -> FutureHelpers.toVoid(clearMarkers(sealedSegments)
                .thenCompose(x -> completeScale(scaleTimestamp, sealedSegments, newSegments))),
                Lists.newArrayList(State.SCALING));
    }

    @Override
    public CompletableFuture<Pair<List<Integer>, List<Integer>>> latestScaleData() {
        return verifyLegalState(() -> getHistoryTable().thenApply(history -> {
            byte[] historyTable = history.getData();
            return TableHelper.getLatestScaleData(historyTable);
        }));
    }

    @Override
    public CompletableFuture<VersionedTransactionData> createTransaction(final long lease, final long maxExecutionTime,
                                                                         final long scaleGracePeriod) {
        final UUID txId = UUID.randomUUID();
        final long current = System.currentTimeMillis();
        return verifyLegalState(() -> createNewTransaction(txId, current, current + lease, current + maxExecutionTime, scaleGracePeriod)
                .thenApply(x ->
                        new VersionedTransactionData(txId, 0, TxnStatus.OPEN, current,
                                current + maxExecutionTime, scaleGracePeriod)));
    }

    @Override
    public CompletableFuture<VersionedTransactionData> pingTransaction(final UUID txId, final long lease) {
        return getActiveTx(txId)
                .thenCompose(data -> {
                    ActiveTxnRecord activeTxnRecord = ActiveTxnRecord.parse(data.getData());
                    if (activeTxnRecord.getTxnStatus() == TxnStatus.OPEN) {
                        // Update txn record with new lease value and return versioned tx data.
                        ActiveTxnRecord newData = new ActiveTxnRecord(activeTxnRecord.getTxCreationTimestamp(),
                                System.currentTimeMillis() + lease, activeTxnRecord.getMaxExecutionExpiryTime(),
                                activeTxnRecord.getScaleGracePeriod(), activeTxnRecord.getTxnStatus());

                        return updateActiveTx(txId, newData.toByteArray())
                                .thenApply(x ->
                                        new VersionedTransactionData(txId, data.getVersion() + 1,
                                                TxnStatus.OPEN, activeTxnRecord.getTxCreationTimestamp(),
                                                activeTxnRecord.getMaxExecutionExpiryTime(),
                                                activeTxnRecord.getScaleGracePeriod()));
                    } else {
                        return FutureHelpers.failedFuture(new IllegalStateException(txId.toString()));
                    }
                });
    }

    @Override
    public CompletableFuture<VersionedTransactionData> getTransactionData(UUID txId) {
        return getActiveTx(txId)
                .thenApply(data -> {
                    ActiveTxnRecord activeTxnRecord = ActiveTxnRecord.parse(data.getData());
                    return new VersionedTransactionData(txId, data.getVersion(),
                            activeTxnRecord.getTxnStatus(), activeTxnRecord.getTxCreationTimestamp(),
                            activeTxnRecord.getMaxExecutionExpiryTime(), activeTxnRecord.getScaleGracePeriod());
                });
    }

    @Override
    public CompletableFuture<TxnStatus> checkTransactionStatus(final UUID txId) {

        return verifyLegalState(() -> getActiveTx(txId)
                .handle((ok, ex) -> {
                    if (ok == null ||
                            (ex != null && ExceptionHelpers.getRealException(ex) instanceof DataNotFoundException)) {
                        return TxnStatus.UNKNOWN;
                    } else if (ex != null) {
                        throw new CompletionException(ex);
                    }
                    return ActiveTxnRecord.parse(ok.getData()).getTxnStatus();
                }).thenCompose(x -> parseStatus(txId, x)));
    }

    private CompletionStage<TxnStatus> parseStatus(final UUID txId, TxnStatus x) {
        if (!x.equals(TxnStatus.UNKNOWN)) {
            return CompletableFuture.completedFuture(x);
        }
        return getCompletedTx(txId).handle((ok, ex) -> {
            if (ok == null || (ex != null && ex instanceof DataNotFoundException)) {
                return TxnStatus.UNKNOWN;
            } else if (ex != null) {
                throw new CompletionException(ex);
            }
            return CompletedTxnRecord.parse(ok.getData()).getCompletionStatus();
        });
    }

    @Override
    public CompletableFuture<TxnStatus> sealTransaction(final UUID txId, final boolean commit,
                                                        final Optional<Integer> version) {
        return verifyLegalState(() -> checkTransactionStatus(txId)
                .thenCompose(x -> {
                    if (commit) {
                        switch (x) {
                            case OPEN:
                                return sealActiveTx(txId, true, version).thenApply(y -> TxnStatus.COMMITTING);
                            case COMMITTING:
                            case COMMITTED:
                                return CompletableFuture.completedFuture(x);
                            case ABORTING:
                            case ABORTED:
                                throw new OperationOnTxNotAllowedException(txId.toString(), "seal");
                            default:
                                throw new TransactionNotFoundException(txId.toString());
                        }
                    } else {
                        switch (x) {
                            case OPEN:
                                return sealActiveTx(txId, false, version).thenApply(y -> TxnStatus.ABORTING);
                            case ABORTING:
                            case ABORTED:
                                return CompletableFuture.completedFuture(x);
                            case COMMITTING:
                            case COMMITTED:
                                throw new OperationOnTxNotAllowedException(txId.toString(), "seal");
                            default:
                                throw new TransactionNotFoundException(txId.toString());
                        }
                    }
                }));
    }

    @Override
    public CompletableFuture<TxnStatus> commitTransaction(final UUID txId) {

        return verifyLegalState(() -> checkTransactionStatus(txId)
                .thenApply(x -> {
                    switch (x) {
                        // Only sealed transactions can be committed
                        case COMMITTED:
                        case COMMITTING:
                            return x;
                        case OPEN:
                        case ABORTING:
                        case ABORTED:
                            throw new OperationOnTxNotAllowedException(txId.toString(), "commit");
                        case UNKNOWN:
                        default:
                            throw new TransactionNotFoundException(txId.toString());
                    }
                })
                .thenCompose(x -> {
                    if (x.equals(TxnStatus.COMMITTING)) {
                        return createCompletedTxEntry(txId, TxnStatus.COMMITTED, System.currentTimeMillis());
                    } else {
                        return CompletableFuture.completedFuture(null); // already committed, do nothing
                    }
                })
                .thenCompose(x -> removeActiveTxEntry(txId))
                .thenApply(x -> TxnStatus.COMMITTED));
    }

    @Override
    public CompletableFuture<TxnStatus> abortTransaction(final UUID txId) {
        return verifyLegalState(() -> checkTransactionStatus(txId)
                .thenApply(x -> {
                    switch (x) {
                        case ABORTING:
                        case ABORTED:
                            return x;
                        case OPEN:
                        case COMMITTING:
                        case COMMITTED:
                            throw new OperationOnTxNotAllowedException(txId.toString(), "aborted");
                        case UNKNOWN:
                        default:
                            throw new TransactionNotFoundException(txId.toString());
                    }
                })
                .thenCompose(x -> {
                    if (x.equals(TxnStatus.ABORTING)) {
                        return createCompletedTxEntry(txId, TxnStatus.ABORTED, System.currentTimeMillis());
                    } else {
                        return CompletableFuture.completedFuture(null); // already committed, do nothing
                    }
                })
                .thenCompose(y -> removeActiveTxEntry(txId))
                .thenApply(y -> TxnStatus.ABORTED));
    }

    @Override
    public CompletableFuture<Map<UUID, ActiveTxnRecord>> getActiveTxns() {
        return verifyLegalState(() -> getCurrentTxns()
                .thenApply(x -> x.entrySet().stream()
                        .collect(Collectors.toMap(k -> UUID.fromString(k.getKey()),
                                v -> ActiveTxnRecord.parse(v.getValue().getData())))));
    }

    @Override
    public CompletableFuture<Void> setColdMarker(int segmentNumber, long timestamp) {

        return verifyLegalState(() -> getMarkerData(segmentNumber)
                .thenCompose(x -> {
                    if (x != null) {
                        byte[] b = new byte[Long.BYTES];
                        BitConverter.writeLong(b, 0, timestamp);
                        final Data<T> data = new Data<>(b, x.getVersion());
                        return updateMarkerData(segmentNumber, data);
                    } else {
                        return createMarkerData(segmentNumber, timestamp);
                    }
                }));
    }

    @Override
    public CompletableFuture<Long> getColdMarker(int segmentNumber) {
        return verifyLegalState(() -> getMarkerData(segmentNumber)
                .thenApply(x -> (x != null) ? BitConverter.readLong(x.getData(), 0) : 0L));
    }

    @Override
    public CompletableFuture<Void> removeColdMarker(int segmentNumber) {
        return verifyLegalState(() -> removeMarkerData(segmentNumber));
    }

    private <U> CompletableFuture<U> verifyState(Supplier<CompletableFuture<U>> future, List<State> states) {
        return getState()
                .thenApply(state -> state != null && states.contains(state))
                .thenCompose(created -> {
                    if (created) {
                        return future.get();
                    } else {
                        throw new IllegalStateException("stream state not valid for the operation");
                    }
                });
    }

    private <U> CompletableFuture<U> verifyLegalState(Supplier<CompletableFuture<U>> future) {
        return getState()
                .thenApply(state -> state != null &&
                        !state.equals(State.UNKNOWN) &&
                        !state.equals(State.CREATING))
                .thenCompose(created -> {
                    if (created) {
                        return future.get();
                    } else {
                        throw new IllegalStateException("stream state unknown or stream is still being created");
                    }
                });
    }

    private CompletableFuture<List<Segment>> getSegments(final List<Integer> segments) {
        return FutureHelpers.allOfWithResults(segments.stream().map(this::getSegment)
                .collect(Collectors.toList()));
    }

    private CompletableFuture<Void> createNewEpoch(long epoch) {
        return createEpochNode(epoch);
    }

    /**
     * update history table if not already updated:
     * fetch last record from history table.
     * if eventTime is >= scale.scaleTimeStamp do nothing, else create record
     *
     * @return : future of history table offset for last entry
     */
    private CompletableFuture<Void> addPartialHistoryRecord(final List<Integer> sealedSegments,
                                                            final List<Integer> createdSegments) {
        return getHistoryTable()
                .thenCompose(historyTable -> {
                    final Optional<HistoryRecord> lastRecordOpt = HistoryRecord.readLatestRecord(historyTable.getData(), false);

                    // scale task is not allowed unless create is done which means at least one
                    // record in history table.
                    assert lastRecordOpt.isPresent();

                    final HistoryRecord lastRecord = lastRecordOpt.get();

                    // idempotent check
                    if (lastRecord.getSegments().containsAll(createdSegments)) {
                        HistoryRecord previous = HistoryRecord.fetchPrevious(lastRecord, historyTable.getData()).get();

                        assert previous.getSegments().stream().noneMatch(createdSegments::contains);
                        return CompletableFuture.completedFuture(null);
                    }

                    return createNewEpoch(lastRecordOpt.get().getEpoch())
                            .thenCompose(v -> {
                                final List<Integer> newActiveSegments = getNewActiveSegments(createdSegments, sealedSegments, lastRecord);

                                byte[] updatedTable = TableHelper.addPartialRecordToHistoryTable(historyTable.getData(), newActiveSegments);
                                final Data<T> updated = new Data<>(updatedTable, historyTable.getVersion());

                                return updateHistoryTable(updated);
                            });
                });
    }

    private CompletableFuture<Void> completeScale(long scaleTimestamp, List<Integer> sealedSegments, List<Integer> newSegments) {
        return getHistoryTable()
                .thenCompose(historyTable -> {
                    final Optional<HistoryRecord> lastRecordOpt = HistoryRecord.readLatestRecord(historyTable.getData(), false);

                    assert lastRecordOpt.isPresent();

                    final HistoryRecord lastRecord = lastRecordOpt.get();

                    // idempotent check
                    if (!lastRecord.isPartial()) {
                        assert lastRecord.getSegments().stream().noneMatch(sealedSegments::contains);
                        assert newSegments.stream().allMatch(x -> lastRecord.getSegments().contains(x));

                        return CompletableFuture.completedFuture(null);
                    }

                    long scaleEventTime = Math.max(System.currentTimeMillis(), scaleTimestamp);
                    final Optional<HistoryRecord> previousOpt = HistoryRecord.fetchPrevious(lastRecord, historyTable.getData());
                    if (previousOpt.isPresent()) {
                        // To ensure that we always have ascending time in history records irrespective of controller clock mismatches.
                        scaleEventTime = Math.max(scaleEventTime, previousOpt.get().getScaleTime() + 1);
                    }

                    byte[] updatedTable = TableHelper.completePartialRecordInHistoryTable(historyTable.getData(), lastRecord, scaleEventTime);
                    final Data<T> updated = new Data<>(updatedTable, historyTable.getVersion());

                    final HistoryRecord newRecord = HistoryRecord.readLatestRecord(updatedTable, false).get();
                    return addIndexRecord(newRecord)
                            .thenCompose(x -> updateHistoryTable(updated))
                            .thenCompose(x -> FutureHelpers.toVoid(updateState(State.ACTIVE)));
                });
    }

    private List<Integer> getNewActiveSegments(final List<Integer> createdSegments,
                                               final List<Integer> sealedSegments,
                                               final HistoryRecord lastRecord) {
        final List<Integer> segments = lastRecord.getSegments();
        segments.removeAll(sealedSegments);
        segments.addAll(createdSegments);
        return segments;
    }

    private CompletableFuture<Void> addIndexRecord(final HistoryRecord historyRecord) {
        return getIndexTable()
                .thenCompose(indexTable -> {
                    final Optional<IndexRecord> lastRecord = IndexRecord.readLatestRecord(indexTable.getData());
                    // check idempotent
                    if (lastRecord.isPresent() && lastRecord.get().getHistoryOffset() == historyRecord.getOffset()) {
                        return CompletableFuture.completedFuture(null);
                    }

                    final byte[] updatedTable = TableHelper.updateIndexTable(indexTable.getData(),
                            historyRecord.getScaleTime(),
                            historyRecord.getOffset());
                    final Data<T> updated = new Data<>(updatedTable, indexTable.getVersion());
                    return updateIndexTable(updated);
                });
    }

    abstract CompletableFuture<Void> deleteStream();

    abstract CompletableFuture<Void> checkStreamExists(final Create create);

    abstract CompletableFuture<Void> storeCreationTime(final Create create);

    abstract CompletableFuture<Void> createConfiguration(final Create create);

    abstract CompletableFuture<Void> setConfigurationData(final StreamConfiguration configuration);

    abstract CompletableFuture<StreamConfiguration> getConfigurationData();

    abstract CompletableFuture<Void> createState(final State state);

    abstract CompletableFuture<Void> setStateData(final Data<T> state);

    abstract CompletableFuture<Data<T>> getStateData();

    abstract CompletableFuture<Void> createSegmentTable(final Create create);

    abstract CompletableFuture<Segment> getSegmentRow(final int number);

    abstract CompletableFuture<Data<T>> getSegmentTable();

    abstract CompletableFuture<Void> setSegmentTable(final Data<T> data);

    abstract CompletableFuture<Void> createIndexTable(final Data<T> data);

    abstract CompletableFuture<Data<T>> getIndexTable();

    abstract CompletableFuture<Void> updateIndexTable(final Data<T> updated);

    abstract CompletableFuture<Void> createHistoryTable(final Data<T> data);

    abstract CompletableFuture<Void> updateHistoryTable(final Data<T> updated);

    abstract CompletableFuture<Data<T>> getHistoryTable();

    abstract CompletableFuture<Void> createEpochNode(long epoch);

    abstract CompletableFuture<Void> deleteEpochNode(long epoch);

    abstract CompletableFuture<Void> createNewTransaction(final UUID txId, final long timestamp,
                                                          final long leaseExpiryTime,
                                                          final long maxExecutionExpiryTime,
                                                          final long scaleGracePeriod);

    abstract CompletableFuture<Data<Integer>> getActiveTx(final UUID txId) throws DataNotFoundException;

    abstract CompletableFuture<Void> updateActiveTx(final UUID txId, final byte[] data) throws DataNotFoundException;

    abstract CompletableFuture<Void> sealActiveTx(final UUID txId, final boolean commit,
                                                  final Optional<Integer> version) throws DataNotFoundException;

    abstract CompletableFuture<Data<Integer>> getCompletedTx(final UUID txId) throws DataNotFoundException;

    abstract CompletableFuture<Void> removeActiveTxEntry(final UUID txId);

    abstract CompletableFuture<Void> createCompletedTxEntry(final UUID txId, final TxnStatus complete, final long timestamp);

    abstract CompletableFuture<Void> createMarkerData(int segmentNumber, long timestamp);

    abstract CompletableFuture<Void> updateMarkerData(int segmentNumber, Data<T> data);

    abstract CompletableFuture<Void> removeMarkerData(int segmentNumber);

    abstract CompletableFuture<Data<T>> getMarkerData(int segmentNumber);

    abstract CompletableFuture<Map<String, Data<T>>> getCurrentTxns();

    abstract CompletableFuture<Void> checkScopeExists() throws StoreException;
}
