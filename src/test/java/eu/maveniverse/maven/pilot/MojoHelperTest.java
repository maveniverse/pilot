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

import java.util.List;
import org.apache.maven.model.Exclusion;
import org.eclipse.aether.graph.Dependency;
import org.junit.jupiter.api.Test;

class MojoHelperTest {

    @Test
    void convertBasicDependency() {
        var dep = new org.apache.maven.model.Dependency();
        dep.setGroupId("org.slf4j");
        dep.setArtifactId("slf4j-api");
        dep.setVersion("2.0.9");
        dep.setScope("compile");

        Dependency result = MojoHelper.convertDependency(dep);

        assertThat(result.getArtifact().getGroupId()).isEqualTo("org.slf4j");
        assertThat(result.getArtifact().getArtifactId()).isEqualTo("slf4j-api");
        assertThat(result.getArtifact().getVersion()).isEqualTo("2.0.9");
        assertThat(result.getScope()).isEqualTo("compile");
        assertThat(result.isOptional()).isFalse();
    }

    @Test
    void convertDependencyWithNullClassifierAndType() {
        var dep = new org.apache.maven.model.Dependency();
        dep.setGroupId("com.example");
        dep.setArtifactId("lib");
        dep.setVersion("1.0");

        Dependency result = MojoHelper.convertDependency(dep);

        assertThat(result.getArtifact().getClassifier()).isEmpty();
        assertThat(result.getArtifact().getExtension()).isEqualTo("jar");
    }

    @Test
    void convertDependencyWithClassifierAndType() {
        var dep = new org.apache.maven.model.Dependency();
        dep.setGroupId("com.example");
        dep.setArtifactId("lib");
        dep.setVersion("1.0");
        dep.setClassifier("sources");
        dep.setType("test-jar");

        Dependency result = MojoHelper.convertDependency(dep);

        assertThat(result.getArtifact().getClassifier()).isEqualTo("sources");
        assertThat(result.getArtifact().getExtension()).isEqualTo("test-jar");
    }

    @Test
    void convertOptionalDependency() {
        var dep = new org.apache.maven.model.Dependency();
        dep.setGroupId("com.example");
        dep.setArtifactId("optional-lib");
        dep.setVersion("1.0");
        dep.setOptional("true");

        Dependency result = MojoHelper.convertDependency(dep);

        assertThat(result.isOptional()).isTrue();
    }

    @Test
    void convertDependencyWithExclusions() {
        var dep = new org.apache.maven.model.Dependency();
        dep.setGroupId("com.example");
        dep.setArtifactId("lib");
        dep.setVersion("1.0");

        Exclusion exclusion = new Exclusion();
        exclusion.setGroupId("org.unwanted");
        exclusion.setArtifactId("bad-lib");
        dep.addExclusion(exclusion);

        Dependency result = MojoHelper.convertDependency(dep);

        assertThat(result.getExclusions()).hasSize(1);
        var ex = result.getExclusions().iterator().next();
        assertThat(ex.getGroupId()).isEqualTo("org.unwanted");
        assertThat(ex.getArtifactId()).isEqualTo("bad-lib");
        assertThat(ex.getClassifier()).isEqualTo("*");
        assertThat(ex.getExtension()).isEqualTo("*");
    }

    @Test
    void convertDependenciesReturnsList() {
        var dep1 = new org.apache.maven.model.Dependency();
        dep1.setGroupId("g1");
        dep1.setArtifactId("a1");
        dep1.setVersion("1.0");

        var dep2 = new org.apache.maven.model.Dependency();
        dep2.setGroupId("g2");
        dep2.setArtifactId("a2");
        dep2.setVersion("2.0");

        List<Dependency> result = MojoHelper.convertDependencies(List.of(dep1, dep2));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getArtifact().getGroupId()).isEqualTo("g1");
        assertThat(result.get(1).getArtifact().getGroupId()).isEqualTo("g2");
    }

    @Test
    void convertDependenciesReturnsEmptyForNull() {
        assertThat(MojoHelper.convertDependencies(null)).isEmpty();
    }

    @Test
    void convertDependenciesReturnsEmptyForEmptyList() {
        assertThat(MojoHelper.convertDependencies(List.of())).isEmpty();
    }
}
