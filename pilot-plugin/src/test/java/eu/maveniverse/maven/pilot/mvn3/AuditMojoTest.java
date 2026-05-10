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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import eu.maveniverse.maven.pilot.AuditMojoTestHelper;
import eu.maveniverse.maven.pilot.AuditTui;
import java.lang.reflect.Field;
import java.util.List;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.junit.jupiter.api.Test;

class AuditMojoTest {

    @Test
    void executeRejectsInvalidAction() throws Exception {
        var mojo = new AuditMojo();
        setField(mojo, "action", "invalid");

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("Invalid action 'invalid'");
    }

    @Test
    void defaultActionIsTui() throws Exception {
        var mojo = new AuditMojo();
        assertThat(getField(mojo, "action")).isEqualTo("tui");
    }

    @Test
    void defaultSeverityIsHigh() throws Exception {
        var mojo = new AuditMojo();
        assertThat(getField(mojo, "severityThreshold")).isEqualTo("HIGH");
    }

    @Test
    void executeNonInteractiveReportNoVulns() throws Exception {
        var mojo = new AuditMojo();
        setField(mojo, "action", "report");

        var entry = new AuditTui.AuditEntry("org.example", "safe-lib", "1.0.0", "compile");
        entry.vulnerabilities = List.of();
        entry.vulnsLoaded = true;
        entry.licenseLoaded = true;

        mojo.executeNonInteractive(List.of(entry));
    }

    @Test
    void executeNonInteractiveCheckPassesNoVulns() throws Exception {
        var mojo = new AuditMojo();
        setField(mojo, "action", "check");
        setField(mojo, "severityThreshold", "HIGH");

        var entry = new AuditTui.AuditEntry("org.example", "safe-lib", "1.0.0", "compile");
        entry.vulnerabilities = List.of();
        entry.vulnsLoaded = true;
        entry.licenseLoaded = true;

        mojo.executeNonInteractive(List.of(entry));
    }

    @Test
    void executeNonInteractiveCheckFailsAboveThreshold() throws Exception {
        var mojo = new AuditMojo();
        setField(mojo, "action", "check");
        setField(mojo, "severityThreshold", "HIGH");

        var entry = AuditMojoTestHelper.entryWithVuln(
                "org.example", "vuln-lib", "1.0.0", "compile", "CVE-2024-0001", "Bad vuln", "HIGH");

        assertThatThrownBy(() -> mojo.executeNonInteractive(List.of(entry)))
                .isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("1 vulnerability(ies)")
                .hasMessageContaining("HIGH or above");
    }

    @Test
    void executeNonInteractiveCheckPassesBelowThreshold() throws Exception {
        var mojo = new AuditMojo();
        setField(mojo, "action", "check");
        setField(mojo, "severityThreshold", "HIGH");

        var entry = AuditMojoTestHelper.entryWithVuln(
                "org.example", "low-vuln-lib", "1.0.0", "compile", "CVE-2024-0002", "Low vuln", "LOW");

        mojo.executeNonInteractive(List.of(entry));
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Object getField(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }
}
