/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package eu.maveniverse.maven.pilot;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Shared utilities for the Pilot core that have no Maven dependencies.
 */
public final class PilotUtil {

    private static final int MAX_CONCURRENT = 32;

    private PilotUtil() {}

    /**
     * Create a bounded ExecutorService for HTTP I/O.
     * Uses virtual threads (Java 21+) if available, falling back to platform threads.
     * Concurrency is capped to avoid overwhelming remote APIs and the render thread.
     */
    public static ExecutorService newHttpPool() {
        try {
            // Thread.ofVirtual().factory() — Java 21+
            Object builder = Thread.class.getMethod("ofVirtual").invoke(null);
            ThreadFactory factory =
                    (ThreadFactory) builder.getClass().getMethod("factory").invoke(builder);
            return Executors.newFixedThreadPool(MAX_CONCURRENT, factory);
        } catch (Exception e) {
            return Executors.newFixedThreadPool(MAX_CONCURRENT, r -> {
                Thread t = new Thread(r);
                t.setDaemon(true);
                return t;
            });
        }
    }
}
