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
package eu.maveniverse.maven.pilot.mvn3;

import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.transfer.AbstractTransferListener;
import org.eclipse.aether.transfer.TransferEvent;
import org.eclipse.aether.transfer.TransferListener;

/**
 * Inline progress display for artifact resolution in standalone Mojos.
 * Renders a single spinner line on stderr, avoiding alternate-screen flicker.
 */
class ResolutionProgress {

    private static final String[] SPINNER = {
        "\u280b", "\u2819", "\u2839", "\u2838", "\u283c", "\u2834", "\u2826", "\u2827", "\u2807", "\u280f"
    };

    private final String title;
    private int spinnerFrame;
    private final ConcurrentHashMap<String, TransferInfo> activeTransfers = new ConcurrentHashMap<>();
    private final AtomicInteger completedCount = new AtomicInteger();
    private final AtomicLong totalBytes = new AtomicLong();

    private static class TransferInfo {
        final String name;
        final long contentLength;
        volatile long transferred;

        TransferInfo(String name, long contentLength) {
            this.name = name;
            this.contentLength = contentLength;
        }
    }

    ResolutionProgress(String title) {
        this.title = title;
    }

    TransferListener createListener() {
        return new AbstractTransferListener() {
            @Override
            public void transferStarted(TransferEvent event) {
                String key = event.getResource().getResourceName();
                String name = key.substring(key.lastIndexOf('/') + 1);
                activeTransfers.put(
                        key, new TransferInfo(name, event.getResource().getContentLength()));
            }

            @Override
            public void transferProgressed(TransferEvent event) {
                TransferInfo info = activeTransfers.get(event.getResource().getResourceName());
                if (info != null) {
                    info.transferred = event.getTransferredBytes();
                }
            }

            @Override
            public void transferSucceeded(TransferEvent event) {
                activeTransfers.remove(event.getResource().getResourceName());
                completedCount.incrementAndGet();
                totalBytes.addAndGet(event.getTransferredBytes());
            }

            @Override
            public void transferFailed(TransferEvent event) {
                activeTransfers.remove(event.getResource().getResourceName());
            }
        };
    }

    <T> T run(Callable<T> work) throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pilot-progress");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::printProgress, 100, 100, TimeUnit.MILLISECONDS);
        try {
            return work.call();
        } finally {
            scheduler.shutdownNow();
            clearProgress();
        }
    }

    @FunctionalInterface
    interface SessionCallable<T> {
        T call(RepositorySystemSession session) throws Exception;
    }

    /**
     * Creates a progress session and runs the work with inline progress display.
     * The work receives the wrapped session with the progress listener installed.
     */
    static <T> T resolve(String title, RepositorySystemSession repoSession, SessionCallable<T> work) throws Exception {
        ResolutionProgress progress = new ResolutionProgress(title);
        DefaultRepositorySystemSession ps = new DefaultRepositorySystemSession(repoSession);
        ps.setTransferListener(progress.createListener());
        return progress.run(() -> work.call(ps));
    }

    private void printProgress() {
        spinnerFrame++;
        String spinner = SPINNER[spinnerFrame % SPINNER.length];
        int completed = completedCount.get();

        StringBuilder sb = new StringBuilder();
        sb.append("\r\033[K");
        sb.append(spinner).append(" ").append(title);

        var iter = activeTransfers.values().iterator();
        if (iter.hasNext()) {
            TransferInfo info = iter.next();
            sb.append("  \u2193 ").append(info.name);
            if (info.contentLength > 0) {
                sb.append(" (")
                        .append(formatBytes(info.transferred))
                        .append("/")
                        .append(formatBytes(info.contentLength))
                        .append(")");
            }
        }

        if (completed > 0) {
            sb.append("  ")
                    .append(completed)
                    .append(" done (")
                    .append(formatBytes(totalBytes.get()))
                    .append(")");
        }

        System.err.print(sb);
        System.err.flush();
    }

    private void clearProgress() {
        System.err.print("\r\033[K");
        System.err.flush();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
