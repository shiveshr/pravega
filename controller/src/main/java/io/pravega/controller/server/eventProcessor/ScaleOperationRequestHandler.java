/**
 * Copyright (c) 2017 Dell Inc., or its subsidiaries.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.pravega.controller.server.eventProcessor;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import io.pravega.client.stream.ScalingPolicy;
import io.pravega.client.stream.StreamConfiguration;
import io.pravega.common.ExceptionHelpers;
import io.pravega.common.concurrent.FutureHelpers;
import io.pravega.common.util.Retry;
import io.pravega.controller.retryable.RetryableException;
import io.pravega.controller.store.stream.OperationContext;
import io.pravega.controller.store.stream.Segment;
import io.pravega.controller.store.stream.StreamMetadataStore;
import io.pravega.controller.store.task.LockFailedException;
import io.pravega.controller.task.Stream.StreamMetadataTasks;
import io.pravega.shared.controller.event.ScaleOpEvent;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.ImmutablePair;

import java.time.Duration;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;

/**
 * Request handler for scale requests in scale-request-stream.
 */
@Slf4j
public class ScaleOperationRequestHandler implements RequestHandler<ScaleOpEvent> {

    private static final long RETRY_INITIAL_DELAY = 100;
    private static final int RETRY_MULTIPLIER = 2;
    private static final int RETRY_MAX_ATTEMPTS = 10;
    private static final long RETRY_MAX_DELAY = Duration.ofSeconds(10).toMillis();

    private static final Retry.RetryAndThrowConditionally<RuntimeException> RETRY = Retry.withExpBackoff(RETRY_INITIAL_DELAY, RETRY_MULTIPLIER, RETRY_MAX_ATTEMPTS, RETRY_MAX_DELAY)
            .retryWhen(RetryableException::isRetryable)
            .throwingOn(RuntimeException.class);

    private final StreamMetadataTasks streamMetadataTasks;
    private final StreamMetadataStore streamMetadataStore;
    private final ScheduledExecutorService executor;

    public ScaleOperationRequestHandler(final StreamMetadataTasks streamMetadataTasks,
                                        final StreamMetadataStore streamMetadataStore,
                                        final ScheduledExecutorService executor) {
        Preconditions.checkNotNull(streamMetadataStore);
        Preconditions.checkNotNull(streamMetadataTasks);
        Preconditions.checkNotNull(executor);
        this.streamMetadataTasks = streamMetadataTasks;
        this.streamMetadataStore = streamMetadataStore;
        this.executor = executor;
    }

    public CompletableFuture<Void> process(final ScaleOpEvent request) {
        CompletableFuture<Void> result = new CompletableFuture<>();

        streamMetadataTasks.scale(request.getScope(),
                request.getStream(),
                request.getSegments(),
                request.getNewRanges(),
                System.currentTimeMillis(),
                null)
                .whenCompleteAsync((res, e) -> {
                    if (e != null) {
                        Throwable cause = ExceptionHelpers.getRealException(e);
                        if (cause instanceof LockFailedException) {
                            result.completeExceptionally(cause);
                        } else {
                            result.completeExceptionally(e);
                        }
                    } else {
                        // completed - either successfully or with pre-condition-failure. Clear markers on all scaled segments.
                        result.complete(null);
                    }
                }, executor);

        return result;
    }
}
