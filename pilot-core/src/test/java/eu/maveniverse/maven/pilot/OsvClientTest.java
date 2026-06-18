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
import java.io.StringReader;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class OsvClientTest {

    private JsonObject buildResponse(String json) {
        try (var reader = Json.createReader(new StringReader(json))) {
            return reader.readObject();
        }
    }

    private JsonObject buildVuln(String severityJson) {
        String json = "{\"id\": \"CVE-2024-0001\", \"summary\": \"test\", \"published\": \"2024-01-01\""
                + (severityJson.isEmpty() ? "" : ", " + severityJson) + "}";
        return buildResponse(json);
    }

    @Test
    void parseResponseEmptyObject() {
        assertThat(OsvClient.parseResponse(buildResponse("{}"))).isEmpty();
    }

    @Test
    void parseResponseEmptyVulns() {
        assertThat(OsvClient.parseResponse(buildResponse("{\"vulns\": []}"))).isEmpty();
    }

    @Test
    void parseResponseBasicVulnerability() {
        List<OsvClient.Vulnerability> result = OsvClient.parseResponse(buildResponse(
                "{\"vulns\": [{\"id\": \"GHSA-1234\", \"summary\": \"Test vuln\", \"published\": \"2024-01-01\"}]}"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id).isEqualTo("GHSA-1234");
        assertThat(result.get(0).summary).isEqualTo("Test vuln");
        assertThat(result.get(0).published).isEqualTo("2024-01-01");
        assertThat(result.get(0).severity).isEqualTo("UNKNOWN");
        assertThat(result.get(0).aliases).isEmpty();
    }

    @Test
    void parseResponseTrimsSummaryWhitespace() {
        List<OsvClient.Vulnerability> result = OsvClient.parseResponse(
                buildResponse(
                        "{\"vulns\": [{\"id\": \"CVE-2024-0001\", \"summary\": \" Apache ActiveMQ vulnerability \", \"published\": \"2024-01-01\"}]}"));

        assertThat(result.get(0).summary).isEqualTo("Apache ActiveMQ vulnerability");
    }

    @Test
    void parseResponseAliases() {
        List<OsvClient.Vulnerability> result = OsvClient.parseResponse(buildResponse(
                "{\"vulns\": [{\"id\": \"GHSA-1234\", \"summary\": \"test\", \"published\": \"2024-01-01\","
                        + "\"aliases\": [\"CVE-2024-0001\", \"CVE-2024-0002\"]}]}"));

        assertThat(result.get(0).aliases).containsExactly("CVE-2024-0001", "CVE-2024-0002");
    }

    @Test
    void parseResponseMultipleVulnerabilities() {
        List<OsvClient.Vulnerability> result = OsvClient.parseResponse(buildResponse("{\"vulns\": ["
                + "{\"id\": \"GHSA-1\", \"summary\": \"first\", \"published\": \"2024-01-01\"},"
                + "{\"id\": \"GHSA-2\", \"summary\": \"second\", \"published\": \"2024-02-01\"}"
                + "]}"));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).id).isEqualTo("GHSA-1");
        assertThat(result.get(1).id).isEqualTo("GHSA-2");
    }

    @Test
    void parseResponseMissingFields() {
        List<OsvClient.Vulnerability> result = OsvClient.parseResponse(buildResponse("{\"vulns\": [{}]}"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id).isEmpty();
        assertThat(result.get(0).summary).isEmpty();
        assertThat(result.get(0).published).isEmpty();
        assertThat(result.get(0).severity).isEqualTo("UNKNOWN");
        assertThat(result.get(0).aliases).isEmpty();
    }

    @Test
    void extractAliasesEmpty() {
        assertThat(OsvClient.extractAliases(buildVuln(""))).isEmpty();
    }

    @Test
    void extractAliasesPresent() {
        assertThat(OsvClient.extractAliases(buildVuln("\"aliases\": [\"CVE-1\", \"CVE-2\"]")))
                .containsExactly("CVE-1", "CVE-2");
    }

    static Stream<Arguments> severityCases() {
        return Stream.of(
                // No severity info → UNKNOWN
                Arguments.of("", "UNKNOWN"),
                // database_specific.severity takes precedence
                Arguments.of("\"database_specific\": {\"severity\": \"HIGH\"}", "HIGH"),
                // database_specific.severity takes precedence over CVSS
                Arguments.of(
                        "\"database_specific\": {\"severity\": \"LOW\"}, "
                                + "\"severity\": [{\"score\": \"CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H\"}]",
                        "LOW"),
                // Blank database_specific.severity falls through to CVSS
                Arguments.of(
                        "\"database_specific\": {\"severity\": \"\"}, "
                                + "\"severity\": [{\"score\": \"CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H\"}]",
                        "CRITICAL"),
                // database_specific without severity key → UNKNOWN
                Arguments.of("\"database_specific\": {\"other\": \"value\"}", "UNKNOWN"),
                // Empty severity array → UNKNOWN
                Arguments.of("\"severity\": []", "UNKNOWN"),
                // Severity array entry without score → UNKNOWN
                Arguments.of("\"severity\": [{\"type\": \"CVSS_V3\"}]", "UNKNOWN"),
                // CVSS: network + low complexity + all high → CRITICAL
                Arguments.of(
                        "\"severity\": [{\"score\": \"CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H\"}]", "CRITICAL"),
                // CVSS: network + 2 high but high complexity → HIGH
                Arguments.of("\"severity\": [{\"score\": \"CVSS:3.1/AV:N/AC:H/PR:N/UI:N/S:U/C:H/I:H/A:N\"}]", "HIGH"),
                // CVSS: local + 1 high → MEDIUM
                Arguments.of("\"severity\": [{\"score\": \"CVSS:3.1/AV:L/AC:H/PR:N/UI:N/S:U/C:H/I:N/A:N\"}]", "MEDIUM"),
                // CVSS: all none → LOW
                Arguments.of("\"severity\": [{\"score\": \"CVSS:3.1/AV:L/AC:H/PR:N/UI:N/S:U/C:N/I:N/A:N\"}]", "LOW"),
                // CVSS: no high, not all none → LOW fallback
                Arguments.of("\"severity\": [{\"score\": \"CVSS:3.1/AV:L/AC:H/PR:N/UI:N/S:U/C:L/I:N/A:N\"}]", "LOW"),
                // Non-CVSS score string returned as-is
                Arguments.of("\"severity\": [{\"score\": \"7.5\"}]", "7.5"));
    }

    @ParameterizedTest
    @MethodSource("severityCases")
    void extractSeverity(String severityJson, String expected) {
        assertThat(OsvClient.extractSeverity(buildVuln(severityJson))).isEqualTo(expected);
    }
}
