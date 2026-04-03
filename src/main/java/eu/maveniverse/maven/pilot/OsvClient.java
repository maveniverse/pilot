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

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Client for the OSV.dev vulnerability database API.
 */
class OsvClient {

    private static final String API_URL = "https://api.osv.dev/v1/query";

    static class Vulnerability {
        final String id;
        final String summary;
        final String severity;
        final String published;
        final List<String> aliases;

        Vulnerability(String id, String summary, String severity, String published, List<String> aliases) {
            this.id = id;
            this.summary = summary;
            this.severity = severity;
            this.published = published;
            this.aliases = aliases;
        }
    }

    /**
     * Query OSV.dev for vulnerabilities affecting a Maven artifact.
     */
    List<Vulnerability> query(String groupId, String artifactId, String version) throws IOException {
        String packageName = groupId + ":" + artifactId;
        String requestBody = """
                {
                  "version": "%s",
                  "package": {
                    "name": "%s",
                    "ecosystem": "Maven"
                  }
                }
                """.formatted(version, packageName);

        HttpURLConnection conn = (HttpURLConnection) new URL(API_URL).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(requestBody.getBytes(StandardCharsets.UTF_8));
        }

        int status = conn.getResponseCode();
        if (status != 200) {
            return List.of();
        }

        List<Vulnerability> vulns = new ArrayList<>();
        try (InputStream is = conn.getInputStream();
                JsonReader reader = Json.createReader(is)) {
            JsonObject response = reader.readObject();
            JsonArray vulnArray = response.getJsonArray("vulns");
            if (vulnArray != null) {
                for (int i = 0; i < vulnArray.size(); i++) {
                    JsonObject vuln = vulnArray.getJsonObject(i);

                    String id = vuln.getString("id", "");
                    String summary = vuln.getString("summary", "");
                    String published = vuln.getString("published", "");

                    // Extract severity from database_specific or severity array
                    String severity = "UNKNOWN";
                    if (vuln.containsKey("database_specific")) {
                        JsonObject dbSpecific = vuln.getJsonObject("database_specific");
                        if (dbSpecific.containsKey("severity")) {
                            severity = dbSpecific.getString("severity");
                        }
                    }
                    if (vuln.containsKey("severity")) {
                        JsonArray sevArray = vuln.getJsonArray("severity");
                        if (sevArray != null && !sevArray.isEmpty()) {
                            severity = sevArray.getJsonObject(0).getString("score", severity);
                        }
                    }

                    List<String> aliases = new ArrayList<>();
                    if (vuln.containsKey("aliases")) {
                        JsonArray aliasArray = vuln.getJsonArray("aliases");
                        for (int j = 0; j < aliasArray.size(); j++) {
                            aliases.add(aliasArray.getString(j));
                        }
                    }

                    vulns.add(new Vulnerability(id, summary, severity, published, aliases));
                }
            }
        }

        return vulns;
    }
}
