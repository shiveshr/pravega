/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.emc.pravega.controller.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.zookeeper.KeeperException;

/**
 * Helper ZK functions.
 */
@Slf4j
public class ZKUtils {

    /**
     * Helper utility to lazily create and fetch only one instance of the Curator client to be used by the controller.
     */
    public enum CuratorSingleton {
        CURATOR_INSTANCE;

        //Single instance of the curator client which we want to be used in all of the controller code.
        private final CuratorFramework zkClient;

        CuratorSingleton() {
            zkClient = CuratorFrameworkFactory.newClient(Config.ZK_URL, new ExponentialBackoffRetry(
                    Config.ZK_RETRY_SLEEP_MS, Config.ZK_MAX_RETRIES));
        }

        public CuratorFramework getCuratorClient() {
            return zkClient;
        }
    }

    /**
     * Creates the znode if is doesn't already exist in zookeeper.
     *
     * @param client    The curator client to access zookeeper.
     * @param basePath  The znode path string.
     */
    public static void createPathIfNotExists(final CuratorFramework client, final String basePath) {
        try {
            if (client.checkExists().forPath(basePath) == null) {
                client.create().creatingParentsIfNeeded().forPath(basePath);
            }
        } catch (KeeperException.NodeExistsException e) {
            log.debug("Path exists {}, ignoring exception", basePath, e);
        } catch (Exception e) {
            log.error("Exception while creating path {}", basePath, e);
            throw new RuntimeException("Exception while creating znode", e);
        }
    }

    /**
     * Creates the znode if is doesn't already exist in zookeeper.
     *
     * @param client    The curator client to access zookeeper.
     * @param basePath  The znode path string.
     * @param initData  Initialize the znode using the supplied data if not already created.
     */
    public static void createPathIfNotExists(final CuratorFramework client, final String basePath,
            final byte[] initData) {
        try {
            if (client.checkExists().forPath(basePath) == null) {
                client.create().creatingParentsIfNeeded().forPath(basePath, initData);
            }
        } catch (KeeperException.NodeExistsException e) {
            log.debug("Path exists {} , ignoring exception", basePath, e);
        } catch (Exception e) {
            log.error("Error while creating node on ZK", e);
            throw new RuntimeException(e);
        }
    }
}
