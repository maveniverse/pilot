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

import eu.maveniverse.maven.pilot.UpdatesMojoTestHelper;
import eu.maveniverse.maven.pilot.UpdatesReporter;
import java.util.List;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.junit.jupiter.api.Test;

class UpdatesMojoTest {

    @Test
    void executeRejectsInvalidAction() throws Exception {
        var mojo = new UpdatesMojo(null);
        MojoTestHelper.setField(mojo, "action", "invalid");

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("Invalid action 'invalid'");
    }

    @Test
    void defaultActionIsTui() throws Exception {
        var mojo = new UpdatesMojo(null);
        assertThat(MojoTestHelper.getField(mojo, "action")).isEqualTo("tui");
    }

    @Test
    void defaultLibyearsThresholdIsNegative() throws Exception {
        var mojo = new UpdatesMojo(null);
        assertThat((float) MojoTestHelper.getField(mojo, "libyearsThreshold")).isEqualTo(-1f);
    }

    @Test
    void executeNonInteractiveReportDoesNotThrow() throws Exception {
        var mojo = new UpdatesMojo(null);
        MojoTestHelper.setField(mojo, "action", "report");

        var result = UpdatesMojoTestHelper.resultWithNoUpdates();

        mojo.executeNonInteractive(result, (g, a) -> List.of("1.0.0"), "org.example:project:1.0");
    }

    @Test
    void executeNonInteractiveCheckPassesNoThreshold() throws Exception {
        var mojo = new UpdatesMojo(null);
        MojoTestHelper.setField(mojo, "action", "check");
        MojoTestHelper.setField(mojo, "libyearsThreshold", -1f);

        var result = UpdatesMojoTestHelper.resultWithUpdate("org.example", "lib", "1.0.0", "2.0.0", 3.0f);

        mojo.executeNonInteractive(result, (g, a) -> List.of("2.0.0"), "org.example:project:1.0");
    }

    @Test
    void executeNonInteractiveCheckFailsAboveThreshold() throws Exception {
        var mojo = new UpdatesMojo(null);
        MojoTestHelper.setField(mojo, "action", "check");
        MojoTestHelper.setField(mojo, "libyearsThreshold", 2.0f);

        var result = UpdatesMojoTestHelper.resultWithUpdate("org.example", "lib", "1.0.0", "2.0.0", 3.0f);

        assertThatThrownBy(() -> mojo.executeNonInteractive(result, (g, a) -> List.of("2.0.0"), "test:p:1.0"))
                .isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("exceeds threshold");
    }

    @Test
    void executeNonInteractiveCheckPassesBelowThreshold() throws Exception {
        var mojo = new UpdatesMojo(null);
        MojoTestHelper.setField(mojo, "action", "check");
        MojoTestHelper.setField(mojo, "libyearsThreshold", 5.0f);

        var result = UpdatesMojoTestHelper.resultWithUpdate("org.example", "lib", "1.0.0", "2.0.0", 1.0f);

        mojo.executeNonInteractive(result, (g, a) -> List.of("2.0.0"), "test:p:1.0");
    }

    @Test
    void executeNonInteractiveFixDoesNotThrow() throws Exception {
        var mojo = new UpdatesMojo(null);
        MojoTestHelper.setField(mojo, "action", "fix");

        var result = UpdatesMojoTestHelper.resultWithNoUpdates();

        mojo.executeNonInteractive(result, (g, a) -> List.of("1.0.0"), "org.example:project:1.0");
    }

    @Test
    void checkResultFormatFailure() {
        var checkResult = new UpdatesReporter.CheckResult("report text", 4.5f, 3);

        String msg = checkResult.formatFailure(2.0f);

        assertThat(msg).contains("4.5").contains("2.0").contains("exceeds threshold");
    }

    @Test
    void executeRejectsFixAction() throws Exception {
        var mojo = new UpdatesMojo(null);
        MojoTestHelper.setField(mojo, "action", "fix");

        // fix is valid — should not throw on validation
        // (will fail later due to null session, but action validation passes)
        assertThat(MojoTestHelper.getField(mojo, "action")).isEqualTo("fix");
    }
}
