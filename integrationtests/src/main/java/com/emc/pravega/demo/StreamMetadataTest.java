/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.emc.pravega.demo;

import com.emc.pravega.controller.stream.api.v1.CreateStreamStatus;
import com.emc.pravega.controller.stream.api.v1.UpdateStreamStatus;
import com.emc.pravega.service.contracts.StreamSegmentStore;
import com.emc.pravega.service.server.host.handler.PravegaConnectionListener;
import com.emc.pravega.service.server.store.ServiceBuilder;
import com.emc.pravega.service.server.store.ServiceBuilderConfig;
import com.emc.pravega.stream.ScalingPolicy;
import com.emc.pravega.stream.Stream;
import com.emc.pravega.stream.StreamConfiguration;
import com.emc.pravega.stream.impl.PositionInternal;
import com.emc.pravega.stream.impl.StreamConfigurationImpl;
import com.emc.pravega.stream.impl.StreamImpl;
import com.emc.pravega.stream.impl.StreamSegments;
import lombok.Cleanup;
import org.apache.curator.test.TestingServer;


import java.util.List;
import java.util.concurrent.CompletableFuture;

public class StreamMetadataTest {
    @SuppressWarnings("checkstyle:ReturnCount")
    public static void main(String[] args) throws Exception {
        TestingServer zkTestServer = new TestingServer();
        ControllerWrapper controller = new ControllerWrapper(zkTestServer.getConnectString());

        ServiceBuilder serviceBuilder = ServiceBuilder.newInMemoryBuilder(ServiceBuilderConfig.getDefaultConfig());
        serviceBuilder.initialize().get();
        StreamSegmentStore store = serviceBuilder.createStreamSegmentService();
        @Cleanup
        PravegaConnectionListener server = new PravegaConnectionListener(false, 12345, store);
        server.startListening();

        String testFlag = "true";
        final String scope1 = "scope1";
        final String streamName1 = "stream1";
        final StreamConfiguration config1 =
                new StreamConfigurationImpl(scope1,
                        streamName1,
                        new ScalingPolicy(ScalingPolicy.Type.FIXED_NUM_SEGMENTS, 100L, 2, 2));
        CompletableFuture<CreateStreamStatus> createStatus;

        //create stream

        //CS1:create a stream :given a streamName, scope and config
        System.err.println(String.format("Creating stream (%s, %s)", scope1, streamName1));
        createStatus = controller.createStream(config1);
        if (createStatus.get() != CreateStreamStatus.SUCCESS) {
            System.err.println("Create stream failed, exiting");
            testFlag = "false";
            return;
        }

        //PS5:Get position at time before stream creation
        Stream stream1 = new StreamImpl(scope1, streamName1, config1);
        final int count1 = 10;
        CompletableFuture<List<PositionInternal>> getPositions;
        System.err.println(String.format("Position at time before (%s, %s) creation ", scope1, streamName1));
        getPositions  =  controller.getPositions(stream1, System.currentTimeMillis()-3600, count1);
        if (getPositions.get().isEmpty()) {
            System.err.println("Position cannot be fetched for non existent stream");
        } else {
            System.err.println("Fetching position non existent stream, exiting");
            testFlag = "false";
            return;
        }

        //PS6:Get positions at a time in future after stream creation
        System.err.println(String.format("Position at given time in future after (%s, %s) creation ", scope1, streamName1));
        getPositions  =  controller.getPositions(stream1, System.currentTimeMillis()+3600, count1);
        if (getPositions.get().isEmpty()) {
            System.err.println("Fetching position at given time in furture after stream creation failed, exiting");
            testFlag = "false";
            return;
        }

        //CS2:stream duplication not allowed
        System.err.println(String.format("Duplicating stream (%s, %s)", scope1, streamName1));
        createStatus = controller.createStream(config1);
        if (createStatus.get() == CreateStreamStatus.STREAM_EXISTS) {
            System.err.println("Stream duplication not allowed ");
        } else if (createStatus.get() == CreateStreamStatus.FAILURE) {
            System.err.println("Create stream failed, exiting");
            testFlag = "false";
            return;
        } else {
            System.err.println("Stream duplication successful, exiting");
            testFlag = "false";
            return;
        }

        //CS3:create a stream with same stream name in different scopes
        final String scope2 = "scope2";
        final StreamConfiguration config2 =
                new StreamConfigurationImpl(scope2,
                        streamName1,
                        new ScalingPolicy(ScalingPolicy.Type.FIXED_NUM_SEGMENTS, 100L, 2, 2));
        System.err.println(String.format("Creating stream with same stream name in different scope (%s, %s)", scope2, streamName1));
        createStatus = controller.createStream(config2);
        if (createStatus.get() != CreateStreamStatus.SUCCESS) {
            System.err.println("Creating stream with same stream name in different scope failed, exiting ");
            testFlag = "false";
            return;
        }

        //CS4:create a stream with different stream name and config  in same scope
        final String streamName2 = "stream2";
        final StreamConfiguration config3 =
                new StreamConfigurationImpl(scope1,
                        streamName2,
                        new ScalingPolicy(ScalingPolicy.Type.FIXED_NUM_SEGMENTS, 100L, 2, 3));
        System.err.println(String.format("Creating stream with different stream  name and config in same scope(%s, %s)", scope1, streamName2));
        createStatus = controller.createStream(config3);
        if (createStatus.get() != CreateStreamStatus.SUCCESS) {
            System.err.println("Create stream failed, exiting");
            testFlag = "false";
            return;
        }

        //update stream config(alter Stream)

        //AS1:update the stream name
        final StreamConfiguration config4 =
                new StreamConfigurationImpl(scope1,
                        "stream4",
                        new ScalingPolicy(ScalingPolicy.Type.FIXED_NUM_SEGMENTS, 100L, 2, 2));
        CompletableFuture<UpdateStreamStatus> updateStatus;
        updateStatus = controller.alterStream(config4);
        System.err.println(String.format("Updating the stream name (%s, %s)", scope1, "stream4"));
        if (updateStatus.get() != UpdateStreamStatus.FAILURE) {
            System.err.println(" Stream name updated, exiting");
            testFlag = "false";
            return;
        }

        //AS2:update the scope name
        final StreamConfiguration config5 =
                new StreamConfigurationImpl("scope5",
                        streamName1,
                        new ScalingPolicy(ScalingPolicy.Type.FIXED_NUM_SEGMENTS, 100L, 2, 2));
        updateStatus = controller.alterStream(config5);
        System.err.println(String.format("Updtaing the scope name (%s, %s)", "scope5", streamName1));
        if (updateStatus.get() != UpdateStreamStatus.FAILURE) {
            System.err.println("Scope name updated, exiting");
            testFlag = "false";
            return;
        }

        //AS3:update the type of scaling policy
        final StreamConfiguration config6 =
                new StreamConfigurationImpl(scope1,
                        streamName1,
                        new ScalingPolicy(ScalingPolicy.Type.BY_RATE_IN_BYTES, 100L, 2, 2));
        updateStatus = controller.alterStream(config6);
        System.err.println(String.format("Updating the  type of scaling policy(%s, %s)", scope1, streamName1));
        if (updateStatus.get() != UpdateStreamStatus.SUCCESS) {
            System.err.println("Update the  type of scaling policy failed, exiting");
            testFlag = "false";
            return;
        }

        //AS4:update the target rate of scaling policy
        final StreamConfiguration config7 =
                new StreamConfigurationImpl(scope1,
                        streamName1,
                        new ScalingPolicy(ScalingPolicy.Type.FIXED_NUM_SEGMENTS, 200L, 2, 2));
        System.err.println(String.format("Updating the target rate (%s, %s)", scope1, streamName1));
        updateStatus = controller.alterStream(config7);
        if (updateStatus.get() != UpdateStreamStatus.SUCCESS) {
            System.err.println("Update the target rate failed, exiting");
            testFlag = "false";
            return;
        }

        //AS5:update the scale factor of scaling policy
        final StreamConfiguration config8 =
                new StreamConfigurationImpl(scope1,
                        streamName1,
                        new ScalingPolicy(ScalingPolicy.Type.FIXED_NUM_SEGMENTS, 100L, 3, 2));
        System.err.println(String.format("Updating the scalefactor (%s, %s)", scope1, streamName1));
        updateStatus = controller.alterStream(config8);
        if (updateStatus.get() != UpdateStreamStatus.SUCCESS) {
            System.err.println("Udate the scalefactor failed, exiting");
            testFlag = "false";
            return;
        }

        //AS6:update the minNumsegments of scaling policy
        final StreamConfiguration config9 =
                new StreamConfigurationImpl(scope1,
                        streamName1,
                        new ScalingPolicy(ScalingPolicy.Type.FIXED_NUM_SEGMENTS, 100L, 2, 3));
        System.err.println(String.format("Updating the min Num segments (%s, %s)", scope1, streamName1));
        updateStatus = controller.alterStream(config9);
        if (updateStatus.get() != UpdateStreamStatus.SUCCESS) {
            System.err.println("Update  min Num segments failed, exiting");
            testFlag = "false";
            return;
        }

        //AS7:alter configuration of non-existent stream.
        final StreamConfiguration config =
                new StreamConfigurationImpl("scope",
                        "streamName",
                        new ScalingPolicy(ScalingPolicy.Type.FIXED_NUM_SEGMENTS, 200L, 2, 3));
        System.err.println(String.format("Altering the  configuration of a non-existent stream (%s, %s)", "scope", "streamName"));
        updateStatus = controller.alterStream(config);
        if (updateStatus.get() ==  UpdateStreamStatus.STREAM_NOT_FOUND) {
            System.err.println("Altering the configuration of a non-existent stream is not allowed");
        } else if (updateStatus.get() == UpdateStreamStatus.FAILURE) {
            System.err.println("Alter configuration failed, exiting");
            testFlag = "false";
            return;
        } else {
            System.err.println("Altering the configuration of a non-existent stream, exiting");
            testFlag = "false";
            return;
        }

        //get currently active segments

        //GCS1:get active segments of the stream
        CompletableFuture<StreamSegments> getActiveSegments;
        System.err.println(String.format("Get active segments of the stream (%s, %s)", scope1, streamName1));
        getActiveSegments = controller.getCurrentSegments(scope1, streamName1);
        if (getActiveSegments.get().getSegments().isEmpty()) {
           System.err.println("Fetching active segments failed, exiting");
           testFlag = "false";
           return;
        }

        //GCS2:Get active segments for a non-existent stream.
        System.err.println(String.format("Get active segments of the stream (%s, %s)", "scope", "streamName"));
        getActiveSegments = controller.getCurrentSegments("scope", "streamName");
        if (getActiveSegments.get().getSegments().isEmpty())  {
            System.err.println("Active segments cannot be fetched for non existent stream");
        } else {
            System.err.println("Fetching active segments for non existent stream, exiting ");
            testFlag = "false";
            return;
        }

        //get  positions at a given time stamp

        //PS1:get  positions at a given time stamp:given stream, time stamp, count
        System.err.println(String.format("Fetching positions at given time stamp of (%s,%s)", scope1, streamName1));
        getPositions  =  controller.getPositions(stream1, System.currentTimeMillis(), count1);
        if (getPositions.get().isEmpty()) {
            System.err.println("Fetching positions at given time stamp failed, exiting");
            testFlag = "false";
            return;
        }

        //PS2:get positions of a stream with different count
        final int count2 = 20;
        System.err.println(String.format("Positions at given time stamp (%s, %s)", scope1, streamName1));
        getPositions  =  controller.getPositions(stream1, System.currentTimeMillis(), count2);
        if (getPositions.get().isEmpty()) {
            System.err.println("Fetching positions at given time stamp with different count failed, exiting");
            testFlag = "false";
            return;
        }

        //PS3:get positions of a different stream at a given time stamp
        Stream stream2 = new StreamImpl(scope1, streamName2, config3);
        System.err.println(String.format("Fetching positions at given time stamp of (%s, %s)", scope1, stream2));
        getPositions  =  controller.getPositions(stream2, System.currentTimeMillis(), count1);
        if (getPositions.get().isEmpty()) {
            System.err.println("Fetching positions at given time stamp failed, exiting");
            testFlag = "false";
            return;
        }

        //PS4:get positions at a given timestamp for non-existent stream.
        Stream stream = new StreamImpl("scope", "streamName", config);
        System.err.println(String.format("Fetching positions at given time stamp for  (%s, %s)", "scope", "streamName"));
        getPositions   =  controller.getPositions(stream, System.currentTimeMillis(), count1);
        if (getPositions.get().isEmpty()) {
            System.err.println("Positions cannot be fetched for non existent stream");
        } else {
            System.err.println("Fetching active segments for non existent stream, exiting ");
            testFlag = "false";
            return;
        }

        if (testFlag.equals("true")) {
            System.out.println("All stream metadata tests passed.");
            System.exit(0);
        }
    }
}
