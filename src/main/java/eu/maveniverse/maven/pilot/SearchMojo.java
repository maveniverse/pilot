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

import jakarta.json.JsonObject;
import java.util.List;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Interactive TUI for searching Maven Central.
 *
 * <p>Usage:</p>
 * <pre>
 * mvn pilot:search
 * mvn pilot:search -Dquery=guava
 * mvn pilot:search -Dquery="g:com.google.guava AND a:guava"
 * </pre>
 *
 * @since 0.1.0
 */
@Mojo(name = "search", requiresProject = false, threadSafe = true)
public class SearchMojo extends AbstractMojo {

    /**
     * The search query. Supports free-text (e.g., {@code guava}) or Solr field syntax
     * (e.g., {@code g:com.google.guava AND a:guava}).
     */
    @Parameter(property = "query")
    private String query;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        CentralSearchClient client = new CentralSearchClient();

        try {
            String q = query != null ? query.trim() : "";
            List<String[]> initialResults = List.of();
            int totalHits = 0;

            if (!q.isEmpty()) {
                JsonObject response = client.query(q, 100, 0);
                JsonObject responseBody = response.getJsonObject("response");
                totalHits = responseBody.getInt("numFound");
                initialResults = SearchTui.extractArtifacts(responseBody);
            }

            SearchTui tui = new SearchTui(client, q, initialResults, totalHits);
            String selectedGav = tui.run();
            if (selectedGav != null) {
                getLog().info("Selected: " + selectedGav);
            }
        } catch (Exception e) {
            throw new MojoExecutionException("Search failed: " + e.getMessage(), e);
        }
    }
}
