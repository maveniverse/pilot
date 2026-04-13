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
import java.net.URI;
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
     * Query OSV.dev for vulnerabilities affecting the specified Maven artifact version.
     *
     * Sends a POST request to the OSV API for the package named "groupId:artifactId" and the provided version.
     *
     * @param groupId the Maven groupId of the artifact
     * @param artifactId the Maven artifactId
     * @param version the artifact version to query for known vulnerabilities
     * @return a list of Vulnerability entries returned by OSV; an empty list is returned when the API responds with a non-200 status or when no vulnerabilities are found
     * @throws IOException if an I/O error occurs while communicating with the OSV API
     */
    List<Vulnerability> query(String groupId, String artifactId, String version) throws IOException {
        String packageName = groupId + ":" + artifactId;
        String requestBody = Json.createObjectBuilder()
                .add("version", version)
                .add(
                        "package",
                        Json.createObjectBuilder().add("name", packageName).add("ecosystem", "Maven"))
                .build()
                .toString();

        HttpURLConnection conn = (HttpURLConnection) URI.create(API_URL).toURL().openConnection();
        try {
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

            try (InputStream is = conn.getInputStream();
                    JsonReader reader = Json.createReader(is)) {
                return parseResponse(reader.readObject());
            }
        } finally {
            conn.disconnect();
        }
    }

    static List<Vulnerability> parseResponse(JsonObject response) {
        List<Vulnerability> vulns = new ArrayList<>();
        JsonArray vulnArray = response.getJsonArray("vulns");
        if (vulnArray != null) {
            for (int i = 0; i < vulnArray.size(); i++) {
                JsonObject vuln = vulnArray.getJsonObject(i);
                vulns.add(new Vulnerability(
                        vuln.getString("id", ""),
                        vuln.getString("summary", "").trim(),
                        extractSeverity(vuln),
                        vuln.getString("published", ""),
                        extractAliases(vuln)));
            }
        }
        return vulns;
    }

    static String extractSeverity(JsonObject vuln) {
        // Prefer database_specific.severity (human-readable),
        // fall back to CVSS vector from severity array
        if (vuln.containsKey("database_specific")) {
            JsonObject dbSpecific = vuln.getJsonObject("database_specific");
            if (dbSpecific.containsKey("severity")) {
                String dbSeverity = dbSpecific.getString("severity", "").trim();
                if (!dbSeverity.isEmpty()) {
                    return dbSeverity;
                }
            }
        }
        if (vuln.containsKey("severity")) {
            JsonArray sevArray = vuln.getJsonArray("severity");
            if (sevArray != null && !sevArray.isEmpty()) {
                String score = sevArray.getJsonObject(0).getString("score", null);
                if (score != null) {
                    return cvssToSeverity(score);
                }
            }
        }
        return "UNKNOWN";
    }

    static List<String> extractAliases(JsonObject vuln) {
        List<String> aliases = new ArrayList<>();
        if (vuln.containsKey("aliases")) {
            JsonArray aliasArray = vuln.getJsonArray("aliases");
            for (int j = 0; j < aliasArray.size(); j++) {
                aliases.add(aliasArray.getString(j));
            }
        }
        return aliases;
    }

    /**
     * Convert a CVSS vector string to a human-readable severity level.
     * Extracts the base score from CVSS v3/v4 vectors and maps to CRITICAL/HIGH/MEDIUM/LOW.
     */
    private static String cvssToSeverity(String cvssVector) {
        // Try to extract numeric base score from the vector
        // CVSS v3 format: CVSS:3.1/AV:N/AC:L/... — no explicit score in vector
        // CVSS v4 format: CVSS:4.0/AV:N/AC:L/...
        // Some sources include a score like "CVSS:3.1/AV:N/.../score:7.5"
        // For vectors without an explicit score, estimate from impact metrics
        try {
            if (cvssVector.startsWith("CVSS:")) {
                // Parse attack complexity and impact to estimate severity
                boolean networkAccess = cvssVector.contains("/AV:N");
                boolean lowComplexity = cvssVector.contains("/AC:L");
                boolean highConfidentiality = cvssVector.contains("/C:H");
                boolean highIntegrity = cvssVector.contains("/I:H");
                boolean highAvailability = cvssVector.contains("/A:H");
                boolean noneConfidentiality = cvssVector.contains("/C:N");
                boolean noneIntegrity = cvssVector.contains("/I:N");
                boolean noneAvailability = cvssVector.contains("/A:N");

                int highCount = (highConfidentiality ? 1 : 0) + (highIntegrity ? 1 : 0) + (highAvailability ? 1 : 0);
                int noneCount = (noneConfidentiality ? 1 : 0) + (noneIntegrity ? 1 : 0) + (noneAvailability ? 1 : 0);

                if (networkAccess && lowComplexity && highCount == 3) {
                    return "CRITICAL";
                } else if (networkAccess && highCount >= 2) {
                    return "HIGH";
                } else if (noneCount == 3) {
                    return "LOW";
                } else if (highCount >= 1) {
                    return "MEDIUM";
                } else {
                    return "LOW";
                }
            }
        } catch (Exception e) {
            // fall through
        }
        return cvssVector;
    }
}
