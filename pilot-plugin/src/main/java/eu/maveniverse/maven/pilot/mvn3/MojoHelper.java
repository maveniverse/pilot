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

import eu.maveniverse.maven.pilot.*;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.graph.Exclusion;

/**
 * Shared utilities for Mojo implementations.
 */
public final class MojoHelper {

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private MojoHelper() {}

    /**
     * Create an ExecutorService using virtual threads (Java 21+) if available,
     * falling back to a fixed thread pool sized at 2x available processors.
     */
    static ExecutorService newHttpPool() {
        try {
            Method m = Executors.class.getMethod("newVirtualThreadPerTaskExecutor");
            return (ExecutorService) m.invoke(null);
        } catch (Exception e) {
            return Executors.newFixedThreadPool(2 * Runtime.getRuntime().availableProcessors());
        }
    }

    /**
     * Convert a Maven model Dependency into an Aether Dependency.
     *
     * @param dep the Maven model Dependency to convert
     * @return an Aether Dependency with the same coordinates, scope and optional flag; if the Maven dependency's classifier is null it becomes an empty string, if its type is null it defaults to "jar", and any Maven exclusions are mapped to Aether exclusions
     */
    static Dependency convertDependency(org.apache.maven.model.Dependency dep) {
        var artifact = new DefaultArtifact(
                dep.getGroupId(),
                dep.getArtifactId(),
                dep.getClassifier() != null ? dep.getClassifier() : "",
                dep.getType() != null ? dep.getType() : "jar",
                dep.getVersion());
        var d = new Dependency(artifact, dep.getScope(), dep.isOptional());
        if (dep.getExclusions() != null && !dep.getExclusions().isEmpty()) {
            d = d.setExclusions(dep.getExclusions().stream()
                    .map(e -> new Exclusion(e.getGroupId(), e.getArtifactId(), "*", "*"))
                    .toList());
        }
        return d;
    }

    /**
     * Convert a list of Maven model Dependency objects into Aether Dependency objects.
     *
     * @param deps the Maven dependencies to convert; if {@code null} an empty list is returned
     * @return a list of corresponding Aether {@code Dependency} objects (empty if {@code deps} is {@code null} or empty)
     */
    static List<Dependency> convertDependencies(List<org.apache.maven.model.Dependency> deps) {
        if (deps == null) {
            return Collections.emptyList();
        }
        return deps.stream().map(MojoHelper::convertDependency).toList();
    }

    /**
     * Build a CollectRequest populated from the given MavenProject.
     *
     * @param project the Maven project whose artifact, dependencies, managed dependencies, and remote repositories will populate the request
     * @return a CollectRequest with the project's root artifact, converted dependencies, managed dependencies (if present), and repositories
     */
    public static CollectRequest buildCollectRequest(MavenProject project) {
        CollectRequest collectRequest = new CollectRequest();
        collectRequest.setRootArtifact(new DefaultArtifact(
                project.getGroupId(), project.getArtifactId(),
                project.getPackaging(), project.getVersion()));
        collectRequest.setDependencies(convertDependencies(project.getDependencies()));
        if (project.getDependencyManagement() != null) {
            collectRequest.setManagedDependencies(
                    convertDependencies(project.getDependencyManagement().getDependencies()));
        }
        collectRequest.setRepositories(project.getRemoteProjectRepositories());
        return collectRequest;
    }

    // ── MavenProject → PilotProject conversion ────────────────────────────

    public static List<PilotProject> toPilotProjects(List<MavenProject> mavenProjects) {
        IdentityHashMap<MavenProject, PilotProject> cache = new IdentityHashMap<>();
        return mavenProjects.stream().map(mp -> toPilotProject(mp, cache)).toList();
    }

    public static PilotProject toPilotProject(MavenProject mp, IdentityHashMap<MavenProject, PilotProject> cache) {
        PilotProject cached = cache.get(mp);
        if (cached != null) return cached;

        List<PilotProject.Dep> deps = mp.getDependencies() != null
                ? mp.getDependencies().stream().map(MojoHelper::toPilotDep).toList()
                : List.of();
        List<PilotProject.Dep> mgmtDeps = List.of();
        if (mp.getDependencyManagement() != null && mp.getDependencyManagement().getDependencies() != null) {
            mgmtDeps = mp.getDependencyManagement().getDependencies().stream()
                    .map(MojoHelper::toPilotDep)
                    .toList();
        }
        List<PilotProject.Dep> origDeps = mp.getOriginalModel().getDependencies() != null
                ? mp.getOriginalModel().getDependencies().stream()
                        .map(MojoHelper::toPilotDep)
                        .toList()
                : List.of();
        List<PilotProject.Dep> origMgmtDeps = List.of();
        if (mp.getOriginalModel().getDependencyManagement() != null
                && mp.getOriginalModel().getDependencyManagement().getDependencies() != null) {
            origMgmtDeps = mp.getOriginalModel().getDependencyManagement().getDependencies().stream()
                    .map(MojoHelper::toPilotDep)
                    .toList();
        }
        Properties origProps = mp.getOriginalModel().getProperties();
        Path basedir = mp.getBasedir() != null ? mp.getBasedir().toPath() : null;
        Path pomPath = mp.getFile() != null ? mp.getFile().toPath() : null;
        Path outputDir = mp.getBuild() != null && mp.getBuild().getOutputDirectory() != null
                ? Path.of(mp.getBuild().getOutputDirectory())
                : null;
        Path testOutputDir = mp.getBuild() != null && mp.getBuild().getTestOutputDirectory() != null
                ? Path.of(mp.getBuild().getTestOutputDirectory())
                : null;

        PilotProject pp = new PilotProject(
                mp.getGroupId(),
                mp.getArtifactId(),
                mp.getVersion(),
                mp.getPackaging(),
                basedir,
                pomPath,
                deps,
                mgmtDeps,
                origDeps,
                origMgmtDeps,
                origProps,
                outputDir,
                testOutputDir);

        cache.put(mp, pp);

        if (mp.getParent() != null) {
            pp.parent = toPilotProject(mp.getParent(), cache);
        }
        return pp;
    }

    static PilotProject.Dep toPilotDep(org.apache.maven.model.Dependency dep) {
        List<PilotProject.Excl> exclusions = dep.getExclusions() != null
                ? dep.getExclusions().stream()
                        .map(e -> new PilotProject.Excl(e.getGroupId(), e.getArtifactId()))
                        .toList()
                : List.of();
        return new PilotProject.Dep(
                dep.getGroupId(),
                dep.getArtifactId(),
                dep.getVersion(),
                dep.getScope(),
                dep.getType(),
                dep.getClassifier(),
                dep.isOptional(),
                exclusions);
    }

    // ── DependencyNode → DependencyTreeModel conversion ──────────────────

    private static final Map<String, Set<String>> SCOPE_INCLUSIONS = Map.of(
            "compile", Set.of("compile", "provided", "system", ""),
            "runtime", Set.of("compile", "runtime", ""),
            "test", Set.of("compile", "provided", "system", "runtime", "test", ""));

    public static DependencyTreeModel fromDependencyNode(DependencyNode rootNode) {
        return fromDependencyNode(rootNode, null);
    }

    public static DependencyTreeModel fromDependencyNode(DependencyNode rootNode, String scope) {
        Set<String> includedScopes = null;
        if (scope != null) {
            String normalized = scope.toLowerCase(Locale.ROOT);
            includedScopes = SCOPE_INCLUSIONS.get(normalized);
            if (includedScopes == null) {
                throw new IllegalArgumentException(
                        "Unsupported scope '" + scope + "'. Supported values: " + SCOPE_INCLUSIONS.keySet());
            }
        }
        int[] counter = {0};
        List<DependencyTreeModel.TreeNode> conflicts = new ArrayList<>();
        DependencyTreeModel.TreeNode root =
                convertNode(rootNode, 0, counter, new HashSet<>(), conflicts, includedScopes);

        return new DependencyTreeModel(root, conflicts, counter[0]);
    }

    private static DependencyTreeModel.TreeNode convertNode(
            DependencyNode node,
            int depth,
            int[] counter,
            Set<String> visited,
            List<DependencyTreeModel.TreeNode> conflicts,
            Set<String> includedScopes) {
        counter[0]++;

        String groupId, artifactId, classifier, version, scope;
        boolean optional = false;

        if (node.getDependency() != null) {
            var artifact = node.getDependency().getArtifact();
            groupId = artifact.getGroupId();
            artifactId = artifact.getArtifactId();
            classifier = artifact.getClassifier();
            version = artifact.getVersion();
            scope = node.getDependency().getScope();
            optional = node.getDependency().isOptional();
        } else if (node.getArtifact() != null) {
            groupId = node.getArtifact().getGroupId();
            artifactId = node.getArtifact().getArtifactId();
            classifier = node.getArtifact().getClassifier();
            version = node.getArtifact().getVersion();
            scope = "";
        } else {
            groupId = "?";
            artifactId = "?";
            classifier = "";
            version = "?";
            scope = "";
        }

        DependencyTreeModel.TreeNode treeNode =
                new DependencyTreeModel.TreeNode(groupId, artifactId, classifier, version, scope, optional, depth);

        if (node.getData().get("conflict.originalVersion") instanceof String originalVersion) {
            treeNode.requestedVersion = originalVersion;
            conflicts.add(treeNode);
        }

        var repos = node.getRepositories();
        if (repos != null && !repos.isEmpty()) {
            treeNode.repository = repos.get(0).getId();
        }

        String nodeKey = treeNode.ga() + ":" + version;
        if (!visited.add(nodeKey)) {
            return treeNode;
        }

        for (DependencyNode child : node.getChildren()) {
            if (includedScopes != null && child.getDependency() != null) {
                String childScope = child.getDependency().getScope();
                if (childScope != null && !includedScopes.contains(childScope)) {
                    continue;
                }
            }
            treeNode.children.add(convertNode(child, depth + 1, counter, visited, conflicts, includedScopes));
        }

        visited.remove(nodeKey);
        return treeNode;
    }
}
