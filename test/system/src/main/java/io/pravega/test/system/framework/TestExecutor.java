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

package io.pravega.test.system.framework;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

/**
 * Test Executor interface.
 */
public interface TestExecutor {

    /**
     * Start Test Execution given a test method.
     *  @param testMethod test method to be executed.
     *  @return a CompletableFuture which is completed once the test method execution is completed.
     */
    CompletableFuture<Void> startTestExecution(Method testMethod);

    /**
     * Stop Test Execution given the testID.
     *  @param testID testIdentifier indicating the test to be terminated.
     *  @return a CompletableFuture which is completed once the test method execution is stopped.
     */
    CompletableFuture<Void> stopTestExecution(String testID);

}
