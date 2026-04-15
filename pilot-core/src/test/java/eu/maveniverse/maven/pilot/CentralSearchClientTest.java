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

class CentralSearchClientTest {

    @Test
    void addWildcardWrapsSimpleToken() {
        assertThat(CentralSearchClient.addWildcard("commons")).isEqualTo("*commons*");
    }

    @Test
    void addWildcardWrapsMultipleTokens() {
        assertThat(CentralSearchClient.addWildcard("commons lang")).isEqualTo("*commons* *lang*");
    }

    @Test
    void addWildcardPreservesExistingWildcards() {
        assertThat(CentralSearchClient.addWildcard("*commons")).isEqualTo("*commons");
        assertThat(CentralSearchClient.addWildcard("commons*")).isEqualTo("commons*");
    }

    @Test
    void addWildcardLeavesFieldSyntaxUntouched() {
        assertThat(CentralSearchClient.addWildcard("g:org.apache")).isEqualTo("g:org.apache");
        assertThat(CentralSearchClient.addWildcard("g:org.apache AND a:commons"))
                .isEqualTo("g:org.apache AND a:commons");
    }

    @Test
    void addWildcardReturnsEmptyForEmpty() {
        assertThat(CentralSearchClient.addWildcard("")).isEmpty();
        assertThat(CentralSearchClient.addWildcard("  ")).isEmpty();
    }

    @Test
    void addWildcardTrimsInput() {
        assertThat(CentralSearchClient.addWildcard("  commons  ")).isEqualTo("*commons*");
    }
}
