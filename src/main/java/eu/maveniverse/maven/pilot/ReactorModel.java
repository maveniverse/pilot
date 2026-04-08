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

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.maven.project.MavenProject;

/**
 * Tree model representing the reactor module hierarchy.
 *
 * <p>Built from {@code MavenSession.getProjects()}, this model mirrors the
 * {@code <modules>} structure of the reactor. Each node tracks its own
 * update count and the total (own + descendants) for display as {@code "3/12"}.</p>
 */
class ReactorModel {

    static class ModuleNode {
        final MavenProject project;
        final String name;
        final String pomPath;
        final int depth;
        final List<ModuleNode> children = new ArrayList<>();
        boolean expanded;
        int ownUpdateCount;
        int totalUpdateCount;

        ModuleNode(MavenProject project, int depth) {
            this.project = project;
            this.name = project.getArtifactId();
            this.pomPath = project.getFile().getAbsolutePath();
            this.depth = depth;
            this.expanded = depth < 2;
        }

        boolean hasChildren() {
            return !children.isEmpty();
        }

        boolean isLeaf() {
            return children.isEmpty();
        }

        String ga() {
            return project.getGroupId() + ":" + project.getArtifactId();
        }

        String gav() {
            return project.getGroupId() + ":" + project.getArtifactId() + ":" + project.getVersion();
        }
    }

    final ModuleNode root;
    final List<ModuleNode> allModules;

    ReactorModel(ModuleNode root, List<ModuleNode> allModules) {
        this.root = root;
        this.allModules = allModules;
    }

    /**
     * Build a reactor model from the session's project list.
     *
     * <p>The tree is constructed by matching each project's basedir to its parent
     * project's basedir. The first project in the list is assumed to be the root
     * (top-level project).</p>
     */
    static ReactorModel build(List<MavenProject> reactorProjects) {
        if (reactorProjects.isEmpty()) {
            throw new IllegalArgumentException("No projects in reactor");
        }

        List<ModuleNode> allModules = new ArrayList<>();
        Map<File, ModuleNode> byBasedir = new LinkedHashMap<>();

        MavenProject rootProject = reactorProjects.get(0);
        ModuleNode rootNode = new ModuleNode(rootProject, 0);
        byBasedir.put(canonicalBasedir(rootProject), rootNode);
        allModules.add(rootNode);

        for (int i = 1; i < reactorProjects.size(); i++) {
            MavenProject project = reactorProjects.get(i);
            File basedir = canonicalBasedir(project);

            ModuleNode parent = findParentNode(project, byBasedir);
            int depth = parent != null ? parent.depth + 1 : 1;

            ModuleNode node = new ModuleNode(project, depth);
            byBasedir.put(basedir, node);
            allModules.add(node);

            if (parent != null) {
                parent.children.add(node);
            } else {
                rootNode.children.add(node);
            }
        }

        return new ReactorModel(rootNode, allModules);
    }

    /**
     * Returns the flattened list of visible nodes respecting expand/collapse state.
     */
    List<ModuleNode> visibleNodes() {
        List<ModuleNode> visible = new ArrayList<>();
        collectVisible(root, visible);
        return visible;
    }

    private void collectVisible(ModuleNode node, List<ModuleNode> visible) {
        visible.add(node);
        if (node.expanded) {
            for (ModuleNode child : node.children) {
                collectVisible(child, visible);
            }
        }
    }

    /**
     * Filter modules by name substring (case-insensitive).
     */
    List<ModuleNode> filter(String query) {
        String lowerQuery = query.toLowerCase(Locale.ROOT);
        List<ModuleNode> result = new ArrayList<>();
        filterNode(root, lowerQuery, result);
        return result;
    }

    private void filterNode(ModuleNode node, String query, List<ModuleNode> result) {
        if (node.name.toLowerCase(Locale.ROOT).contains(query)
                || node.ga().toLowerCase(Locale.ROOT).contains(query)) {
            result.add(node);
        }
        for (ModuleNode child : node.children) {
            filterNode(child, query, result);
        }
    }

    /**
     * Recompute totalUpdateCount for all nodes bottom-up.
     */
    void recomputeCounts() {
        recomputeCounts(root);
    }

    private int recomputeCounts(ModuleNode node) {
        int total = node.ownUpdateCount;
        for (ModuleNode child : node.children) {
            total += recomputeCounts(child);
        }
        node.totalUpdateCount = total;
        return total;
    }

    private static ModuleNode findParentNode(MavenProject project, Map<File, ModuleNode> byBasedir) {
        File basedir = canonicalBasedir(project);
        File parentDir = basedir.getParentFile();

        while (parentDir != null) {
            ModuleNode parent = byBasedir.get(parentDir);
            if (parent != null) {
                return parent;
            }
            parentDir = parentDir.getParentFile();
        }

        return null;
    }

    private static File canonicalBasedir(MavenProject project) {
        try {
            return project.getBasedir().getCanonicalFile();
        } catch (Exception e) {
            return project.getBasedir().getAbsoluteFile();
        }
    }
}
