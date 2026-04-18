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

import org.junit.jupiter.api.Test;

class PilotShellToolDefTest {

    @Test
    void searchToolIsModuleIndependent() {
        var search = PilotShell.TOOLS.stream()
                .filter(t -> "search".equals(t.id()))
                .findFirst()
                .orElseThrow();
        assertThat(search.isModuleIndependent()).isTrue();
    }

    @Test
    void nonSearchToolsAreNotModuleIndependent() {
        PilotShell.TOOLS.stream().filter(t -> !"search".equals(t.id())).forEach(t -> assertThat(t.isModuleIndependent())
                .as("Tool '%s' should not be module-independent", t.id())
                .isFalse());
    }

    @Test
    void aggregatableToolsIncludeAlignUpdatesConflictsAudit() {
        var aggregatable = PilotShell.TOOLS.stream()
                .filter(PilotShell.ToolDef::aggregatable)
                .map(PilotShell.ToolDef::id)
                .toList();
        assertThat(aggregatable).containsExactlyInAnyOrder("align", "updates", "conflicts", "audit", "dependencies");
    }

    @Test
    void nonAggregatableToolsArePomSearch() {
        var nonAggregatable = PilotShell.TOOLS.stream()
                .filter(t -> !t.aggregatable())
                .map(PilotShell.ToolDef::id)
                .toList();
        assertThat(nonAggregatable).containsExactlyInAnyOrder("pom", "search");
    }

    @Test
    void allToolsHaveUniqueMnemonics() {
        var mnemonics =
                PilotShell.TOOLS.stream().map(PilotShell.ToolDef::mnemonic).toList();
        assertThat(mnemonics).doesNotHaveDuplicates();
    }

    @Test
    void allToolsHaveUniqueIds() {
        var ids = PilotShell.TOOLS.stream().map(PilotShell.ToolDef::id).toList();
        assertThat(ids).doesNotHaveDuplicates();
    }
}
