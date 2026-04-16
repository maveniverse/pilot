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

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

class ReleaseDateFetcher {

    static LocalDate fetchReleaseDate(String groupId, String artifactId, String version) {
        LocalDate local = fetchFromLocal(groupId, artifactId, version);
        if (local != null) return local;

        try {
            String path = groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/" + artifactId + "-"
                    + version + ".pom";
            String url = "https://repo1.maven.org/maven2/" + path;

            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            try {
                conn.setRequestMethod("HEAD");
                conn.setConnectTimeout(5_000);
                conn.setReadTimeout(5_000);

                if (conn.getResponseCode() != 200) {
                    return null;
                }

                long lastModified = conn.getLastModified();
                if (lastModified > 0) {
                    return Instant.ofEpochMilli(lastModified)
                            .atZone(ZoneId.systemDefault())
                            .toLocalDate();
                }
                return null;
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static LocalDate fetchFromLocal(String groupId, String artifactId, String version) {
        try {
            String localRepo = System.getProperty("maven.repo.local");
            if (localRepo == null) {
                localRepo = System.getProperty("user.home") + "/.m2/repository";
            }
            Path pomFile = Path.of(
                    localRepo, groupId.replace('.', '/'), artifactId, version, artifactId + "-" + version + ".pom");
            if (!pomFile.toFile().isFile()) return null;

            long lastModified = pomFile.toFile().lastModified();
            if (lastModified > 0) {
                return Instant.ofEpochMilli(lastModified)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
