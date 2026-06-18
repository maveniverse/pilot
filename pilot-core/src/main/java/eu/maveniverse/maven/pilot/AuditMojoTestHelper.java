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

import java.util.List;

/**
 * Test helper for creating AuditEntry instances with vulnerability data.
 * Needed because OsvClient is package-private.
 */
public final class AuditMojoTestHelper {

    private AuditMojoTestHelper() {}

    public static AuditTui.AuditEntry entryWithVuln(
            String groupId,
            String artifactId,
            String version,
            String scope,
            String vulnId,
            String summary,
            String severity) {
        var entry = new AuditTui.AuditEntry(groupId, artifactId, version, scope);
        entry.vulnerabilities =
                List.of(new OsvClient.Vulnerability(vulnId, summary, severity, "2024-01-01", List.of()));
        entry.vulnsLoaded = true;
        entry.licenseLoaded = true;
        return entry;
    }
}
