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
package eu.maveniverse.maven.pilot.cli;

import eu.maveniverse.maven.pilot.DependencyTreeModel;
import eu.maveniverse.maven.pilot.DependencyTreeModel.TreeNode;
import java.util.HashSet;
import java.util.Set;
import org.eclipse.aether.graph.DependencyNode;

/**
 * Converts Aether DependencyNode to pilot-core's DependencyTreeModel.
 */
class DependencyNodeConverter {

    static DependencyTreeModel convert(DependencyNode rootNode) {
        TreeNode root = convertNode(rootNode, 0, new HashSet<>());
        return DependencyTreeModel.fromTree(root);
    }

    private static TreeNode convertNode(DependencyNode node, int depth, Set<String> visited) {
        String groupId, artifactId, version, scope;
        boolean optional = false;

        if (node.getDependency() != null) {
            var artifact = node.getDependency().getArtifact();
            groupId = artifact.getGroupId();
            artifactId = artifact.getArtifactId();
            version = artifact.getVersion();
            scope = node.getDependency().getScope();
            optional = node.getDependency().isOptional();
        } else if (node.getArtifact() != null) {
            groupId = node.getArtifact().getGroupId();
            artifactId = node.getArtifact().getArtifactId();
            version = node.getArtifact().getVersion();
            scope = "";
        } else {
            groupId = "?";
            artifactId = "?";
            version = "?";
            scope = "";
        }

        TreeNode treeNode = new TreeNode(groupId, artifactId, version, scope, optional, depth);

        String nodeKey = treeNode.ga() + ":" + version;
        if (!visited.add(nodeKey)) {
            return treeNode;
        }

        for (DependencyNode child : node.getChildren()) {
            treeNode.children.add(convertNode(child, depth + 1, visited));
        }

        visited.remove(nodeKey);
        return treeNode;
    }
}
