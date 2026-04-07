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
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Client for the Sonatype Central Search API (Solr).
 *
 * <p>Note: the {@code core=ga} mode does not support the {@code start} parameter
 * for pagination (any {@code start > 0} returns 0 docs). As a workaround, callers
 * should always use {@code start=0} and increase {@code rows} to fetch more results.</p>
 */
class CentralSearchClient implements SearchTui.SearchClient {

    private static final String SEARCH_URL = "https://central.sonatype.com/solrsearch/select";

    @Override
    public JsonObject query(String searchQuery, int rows, int start) throws Exception {
        String q = addWildcard(searchQuery);
        String url = SEARCH_URL + "?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8) + "&rows=" + rows + "&start="
                + start + "&wt=json&core=ga";
        return executeSearch(url);
    }

    /**
     * For free-text queries (no Solr field syntax), wrap each token with wildcards
     * so partial input like "commons-l" matches "commons-lang3".
     * Queries using Solr field syntax (containing {@code :}) are left as-is.
     */
    static String addWildcard(String query) {
        String q = query.trim();
        if (q.isEmpty() || q.contains(":")) {
            return q;
        }
        StringBuilder sb = new StringBuilder();
        for (String token : q.split("\\s+")) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            if (token.startsWith("*") || token.endsWith("*")) {
                sb.append(token);
            } else {
                sb.append('*').append(token).append('*');
            }
        }
        return sb.toString();
    }

    /**
     * Execute an HTTP GET against the provided search URL and parse the response as JSON.
     *
     * @param url the full HTTP URL to query (including query parameters)
     * @return the root {@code JsonObject} parsed from the response body
     * @throws IOException if the HTTP response code is not 200 or an I/O error occurs while performing the request or reading the response
     */
    private JsonObject executeSearch(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);

            int status = connection.getResponseCode();
            if (status != 200) {
                throw new IOException("Search API returned HTTP " + status);
            }

            try (InputStream is = connection.getInputStream();
                    JsonReader reader = Json.createReader(is)) {
                return reader.readObject();
            }
        } finally {
            connection.disconnect();
        }
    }
}
