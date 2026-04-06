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

import java.util.ArrayList;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.DefaultDependencyNode;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.junit.jupiter.api.Test;

class DependencyNodeConverterTest {

    @Test
    void convertSimpleNode() {
        DependencyNode root = nodeWithArtifact("com.example", "root", "1.0");
        DependencyTreeModel model = DependencyNodeConverter.convert(root);

        assertThat(model.root.groupId).isEqualTo("com.example");
        assertThat(model.root.artifactId).isEqualTo("root");
        assertThat(model.root.version).isEqualTo("1.0");
        assertThat(model.totalNodes).isEqualTo(1);
    }

    @Test
    void convertPreservesScope() {
        DependencyNode root = nodeWithDependency("com.example", "lib", "1.0", "test");
        DependencyTreeModel model = DependencyNodeConverter.convert(root);

        assertThat(model.root.scope).isEqualTo("test");
    }

    @Test
    void convertPreservesOptional() {
        var artifact = new DefaultArtifact("com.example", "lib", "jar", "1.0");
        var dep = new Dependency(artifact, "compile", true);
        var node = new DefaultDependencyNode(dep);

        DependencyTreeModel model = DependencyNodeConverter.convert(node);
        assertThat(model.root.optional).isTrue();
    }

    @Test
    void convertTreeStructure() {
        DependencyNode root = nodeWithArtifact("com.example", "root", "1.0");
        DependencyNode child1 = nodeWithDependency("com.example", "child1", "2.0", "compile");
        DependencyNode child2 = nodeWithDependency("com.example", "child2", "3.0", "test");
        root.getChildren().add(child1);
        root.getChildren().add(child2);

        DependencyTreeModel model = DependencyNodeConverter.convert(root);
        assertThat(model.root.children).hasSize(2);
        assertThat(model.root.children.get(0).artifactId).isEqualTo("child1");
        assertThat(model.root.children.get(1).artifactId).isEqualTo("child2");
        assertThat(model.totalNodes).isEqualTo(3);
    }

    @Test
    void convertCalculatesDepth() {
        DependencyNode root = nodeWithArtifact("g", "root", "1.0");
        DependencyNode child = nodeWithDependency("g", "child", "1.0", "compile");
        DependencyNode grandchild = nodeWithDependency("g", "grandchild", "1.0", "compile");
        child.getChildren().add(grandchild);
        root.getChildren().add(child);

        DependencyTreeModel model = DependencyNodeConverter.convert(root);
        assertThat(model.root.depth).isEqualTo(0);
        assertThat(model.root.children.get(0).depth).isEqualTo(1);
        assertThat(model.root.children.get(0).children.get(0).depth).isEqualTo(2);
    }

    @Test
    void convertDetectsCycles() {
        // A -> B -> A (cycle)
        DependencyNode root = nodeWithArtifact("g", "a", "1.0");
        DependencyNode child = nodeWithDependency("g", "b", "1.0", "compile");
        DependencyNode cycleBack = nodeWithDependency("g", "a", "1.0", "compile");
        // Give cycleBack a child that should NOT be traversed
        DependencyNode shouldNotAppear = nodeWithDependency("g", "c", "1.0", "compile");
        cycleBack.getChildren().add(shouldNotAppear);
        child.getChildren().add(cycleBack);
        root.getChildren().add(child);

        DependencyTreeModel model = DependencyNodeConverter.convert(root);
        // root(a) -> child(b) -> cycleBack(a, no children due to cycle)
        assertThat(model.root.children).hasSize(1);
        var b = model.root.children.get(0);
        assertThat(b.children).hasSize(1);
        var cyclicA = b.children.get(0);
        assertThat(cyclicA.children).isEmpty();
    }

    @Test
    void convertHandlesNullDependencyAndArtifact() {
        var node = new DefaultDependencyNode((Dependency) null);

        DependencyTreeModel model = DependencyNodeConverter.convert(node);
        assertThat(model.root.groupId).isEqualTo("?");
        assertThat(model.root.artifactId).isEqualTo("?");
        assertThat(model.root.version).isEqualTo("?");
    }

    private DependencyNode nodeWithArtifact(String groupId, String artifactId, String version) {
        var artifact = new DefaultArtifact(groupId, artifactId, "jar", version);
        var node = new DefaultDependencyNode(artifact);
        node.setChildren(new ArrayList<>());
        return node;
    }

    private DependencyNode nodeWithDependency(String groupId, String artifactId, String version, String scope) {
        var artifact = new DefaultArtifact(groupId, artifactId, "jar", version);
        var dep = new Dependency(artifact, scope);
        var node = new DefaultDependencyNode(dep);
        node.setChildren(new ArrayList<>());
        return node;
    }
}
