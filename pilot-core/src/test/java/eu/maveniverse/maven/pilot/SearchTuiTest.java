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

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;

class SearchTuiTest {

    @Test
    void extractArtifactsFromDocs() {
        JsonObject response = Json.createObjectBuilder()
                .add("numFound", 2)
                .add(
                        "docs",
                        Json.createArrayBuilder()
                                .add(Json.createObjectBuilder()
                                        .add("g", "org.slf4j")
                                        .add("a", "slf4j-api")
                                        .add("latestVersion", "2.0.9")
                                        .add("p", "jar")
                                        .add("versionCount", 42)
                                        .add("timestamp", 1696000000000L))
                                .add(Json.createObjectBuilder()
                                        .add("g", "com.google.guava")
                                        .add("a", "guava")
                                        .add("latestVersion", "33.0.0-jre")
                                        .add("p", "bundle")
                                        .add("versionCount", 100)))
                .build();

        List<String[]> results = SearchTui.extractArtifacts(response);

        assertThat(results).hasSize(2);
        assertThat(results.get(0)[0]).isEqualTo("org.slf4j");
        assertThat(results.get(0)[1]).isEqualTo("slf4j-api");
        assertThat(results.get(0)[2]).isEqualTo("2.0.9");
        assertThat(results.get(0)[3]).isEqualTo("jar");
        assertThat(results.get(0)[4]).isEqualTo("42");
        assertThat(results.get(0)[5]).isNotEmpty(); // timestamp formatted as date

        assertThat(results.get(1)[0]).isEqualTo("com.google.guava");
        assertThat(results.get(1)[2]).isEqualTo("33.0.0-jre");
        assertThat(results.get(1)[5]).isEmpty(); // no timestamp
    }

    @Test
    void extractArtifactsSkipsEmptyGroupOrArtifact() {
        JsonObject response = Json.createObjectBuilder()
                .add("numFound", 1)
                .add(
                        "docs",
                        Json.createArrayBuilder()
                                .add(Json.createObjectBuilder().add("g", "").add("a", "something")))
                .build();

        List<String[]> results = SearchTui.extractArtifacts(response);
        assertThat(results).isEmpty();
    }

    @Test
    void extractArtifactsHandlesNullDocs() {
        JsonObject response = Json.createObjectBuilder().add("numFound", 0).build();

        List<String[]> results = SearchTui.extractArtifacts(response);
        assertThat(results).isEmpty();
    }

    @Test
    void extractArtifactsUsesVFieldWhenNoLatestVersion() {
        JsonObject response = Json.createObjectBuilder()
                .add("numFound", 1)
                .add(
                        "docs",
                        Json.createArrayBuilder()
                                .add(Json.createObjectBuilder()
                                        .add("g", "com.example")
                                        .add("a", "lib")
                                        .add("v", "1.0.0")
                                        .add("p", "jar")))
                .build();

        List<String[]> results = SearchTui.extractArtifacts(response);
        assertThat(results).hasSize(1);
        assertThat(results.get(0)[2]).isEqualTo("1.0.0");
    }
}
