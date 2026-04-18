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
package eu.maveniverse.maven.pilot.mvn4;

import eu.maveniverse.maven.pilot.DependencyTreeModel;
import eu.maveniverse.maven.pilot.PilotProject;
import eu.maveniverse.maven.pilot.PilotResolver;
import eu.maveniverse.maven.pilot.UpdatesTui;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import org.apache.maven.api.Artifact;
import org.apache.maven.api.DependencyCoordinates;
import org.apache.maven.api.DownloadedArtifact;
import org.apache.maven.api.Node;
import org.apache.maven.api.PathScope;
import org.apache.maven.api.Session;
import org.apache.maven.api.Version;
import org.apache.maven.api.model.Model;
import org.apache.maven.api.services.ArtifactResolver;
import org.apache.maven.api.services.ArtifactResolverRequest;
import org.apache.maven.api.services.DependencyCoordinatesFactory;
import org.apache.maven.api.services.DependencyResolver;
import org.apache.maven.api.services.DependencyResolverRequest;
import org.apache.maven.api.services.DependencyResolverResult;
import org.apache.maven.api.services.SuperPomProvider;
import org.apache.maven.api.services.VersionRangeResolver;
import org.apache.maven.api.services.VersionRangeResolverRequest;
import org.apache.maven.api.services.VersionRangeResolverResult;
import org.apache.maven.api.services.xml.ModelXmlFactory;

/**
 * Maven 4 implementation of {@link PilotResolver} using the standalone Maven 4 API.
 * Works with {@link Model} instead of {@code Project} since {@code ProjectBuilder}
 * is not available in the standalone API.
 */
class Maven4PilotResolver implements PilotResolver {

    private static final Logger LOGGER = Logger.getLogger(Maven4PilotResolver.class.getName());

    private final Session session;
    private final Map<String, Model> effectiveModels;

    Maven4PilotResolver(Session session, Map<String, Model> effectiveModels) {
        this.session = session;
        this.effectiveModels = effectiveModels;
    }

    Maven4PilotResolver(Session session, Model effectiveModel) {
        this(session, Map.of(effectiveModel.getGroupId() + ":" + effectiveModel.getArtifactId(), effectiveModel));
    }

    private Model modelFor(PilotProject project) {
        Model model = effectiveModels.get(project.ga());
        if (model == null) {
            throw new IllegalStateException("No effective model for " + project.ga());
        }
        return model;
    }

    private List<DependencyCoordinates> toDependencyCoordinates(List<org.apache.maven.api.model.Dependency> modelDeps) {
        if (modelDeps == null || modelDeps.isEmpty()) {
            return List.of();
        }
        DependencyCoordinatesFactory factory = session.getService(DependencyCoordinatesFactory.class);
        return modelDeps.stream().map(dep -> factory.create(session, dep)).toList();
    }

    @Override
    public DependencyTreeModel collectDependencies(PilotProject project) {
        Model model = modelFor(project);
        try {
            DependencyResolver resolver = session.getService(DependencyResolver.class);
            DependencyResolverRequest request = DependencyResolverRequest.builder()
                    .session(session)
                    .requestType(DependencyResolverRequest.RequestType.COLLECT)
                    .rootArtifact(session.createArtifact(
                            model.getGroupId(),
                            model.getArtifactId(),
                            model.getVersion(),
                            model.getPackaging() != null ? model.getPackaging() : "jar"))
                    .dependencies(toDependencyCoordinates(model.getDependencies()))
                    .managedDependencies(toDependencyCoordinates(
                            model.getDependencyManagement() != null
                                    ? model.getDependencyManagement().getDependencies()
                                    : null))
                    .pathScope(PathScope.TEST_RUNTIME)
                    .build();
            DependencyResolverResult result = resolver.resolve(request);
            return convertTree(result.getRoot());
        } catch (Exception e) {
            LOGGER.warning(
                    "collectDependencies failed for " + model.getGroupId() + ":" + model.getArtifactId() + ": " + e);
            DependencyTreeModel.TreeNode root = new DependencyTreeModel.TreeNode(
                    model.getGroupId(),
                    model.getArtifactId(),
                    "",
                    model.getVersion() != null ? model.getVersion() : "?",
                    "",
                    false,
                    0);
            return new DependencyTreeModel(root, List.of(), 1);
        }
    }

    @Override
    public ResolvedDependencies resolveDependencies(PilotProject project) {
        Model model = modelFor(project);
        try {
            DependencyResolver resolver = session.getService(DependencyResolver.class);
            // Use COLLECT (not RESOLVE) to avoid duplicate key bug in DefaultArtifactResolver.toResult()
            DependencyResolverRequest request = DependencyResolverRequest.builder()
                    .session(session)
                    .requestType(DependencyResolverRequest.RequestType.COLLECT)
                    .rootArtifact(session.createArtifact(
                            model.getGroupId(),
                            model.getArtifactId(),
                            model.getVersion(),
                            model.getPackaging() != null ? model.getPackaging() : "jar"))
                    .dependencies(toDependencyCoordinates(model.getDependencies()))
                    .managedDependencies(toDependencyCoordinates(
                            model.getDependencyManagement() != null
                                    ? model.getDependencyManagement().getDependencies()
                                    : null))
                    .pathScope(PathScope.MAIN_RUNTIME)
                    .build();
            DependencyResolverResult result = resolver.resolve(request);
            DependencyTreeModel tree = convertTree(result.getRoot());
            // Resolve JARs individually from the tree to avoid duplicate key issues
            Map<String, File> gaToJar = new HashMap<>();
            resolveTreeJars(tree.root, gaToJar);
            return new ResolvedDependencies(tree, gaToJar);
        } catch (Exception e) {
            DependencyTreeModel.TreeNode root = new DependencyTreeModel.TreeNode(
                    model.getGroupId(),
                    model.getArtifactId(),
                    "",
                    model.getVersion() != null ? model.getVersion() : "?",
                    "",
                    false,
                    0);
            return new ResolvedDependencies(new DependencyTreeModel(root, List.of(), 1), Map.of());
        }
    }

    private void resolveTreeJars(DependencyTreeModel.TreeNode node, Map<String, File> gaToJar) {
        String ga = node.ga();
        if (!gaToJar.containsKey(ga)) {
            String gav = node.gav();
            String version = gav.substring(gav.lastIndexOf(':') + 1);
            String groupId = ga.substring(0, ga.indexOf(':'));
            String artifactId = ga.substring(ga.indexOf(':') + 1);
            Path path = resolveArtifact(groupId, artifactId, version, "jar");
            if (path != null) {
                gaToJar.put(ga, path.toFile());
            }
        }
        for (DependencyTreeModel.TreeNode child : node.children) {
            resolveTreeJars(child, gaToJar);
        }
    }

    @Override
    public List<String> resolveVersions(String groupId, String artifactId) {
        VersionRangeResolverResult result = session.getService(VersionRangeResolver.class)
                .resolve(VersionRangeResolverRequest.build(
                        session, session.createArtifactCoordinates(groupId, artifactId, "[0,)", "jar")));
        List<String> versions =
                result.getVersions().stream().map(Version::toString).toList();
        return UpdatesTui.versionsNewestFirst(new ArrayList<>(versions));
    }

    @Override
    public Path resolveArtifact(String groupId, String artifactId, String version, String type) {
        try {
            var result = session.getService(ArtifactResolver.class)
                    .resolve(ArtifactResolverRequest.build(
                            session, List.of(session.createArtifactCoordinates(groupId, artifactId, version, type))));
            DownloadedArtifact artifact = result.getArtifacts().iterator().next();
            return artifact.getPath();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String effectivePom(PilotProject project) {
        return session.getService(ModelXmlFactory.class).toXmlString(modelFor(project));
    }

    @Override
    public String superPom() {
        Model superModel = session.getService(SuperPomProvider.class).getSuperPom("4.0.0");
        return session.getService(ModelXmlFactory.class).toXmlString(superModel);
    }

    // ── Tree conversion ──────────────────────────────────────────────────

    private static DependencyTreeModel convertTree(Node rootNode) {
        int[] counter = {0};
        List<DependencyTreeModel.TreeNode> conflicts = new ArrayList<>();
        DependencyTreeModel.TreeNode root = convertNode(rootNode, 0, counter, new HashSet<>(), conflicts);
        return new DependencyTreeModel(root, conflicts, counter[0]);
    }

    private static DependencyTreeModel.TreeNode convertNode(
            Node node, int depth, int[] counter, Set<String> visited, List<DependencyTreeModel.TreeNode> conflicts) {
        counter[0]++;
        DependencyTreeModel.TreeNode treeNode = createTreeNode(node, depth);

        try {
            treeNode.repository = node.getRepository().map(r -> r.getId()).orElse(null);
        } catch (UnsupportedOperationException ignored) {
            // Maven API does not guarantee getRepository() support on all node types
        }

        String nodeKey = treeNode.gav();
        if (!visited.add(nodeKey)) {
            return treeNode;
        }

        for (Node child : node.getChildren()) {
            treeNode.children.add(convertNode(child, depth + 1, counter, visited, conflicts));
        }

        visited.remove(nodeKey);
        return treeNode;
    }

    private static DependencyTreeModel.TreeNode createTreeNode(Node node, int depth) {
        if (node.getDependency() != null) {
            var dep = node.getDependency();
            return new DependencyTreeModel.TreeNode(
                    dep.getGroupId(),
                    dep.getArtifactId(),
                    dep.getClassifier() != null ? dep.getClassifier() : "",
                    dep.getVersion() != null ? dep.getVersion().toString() : "?",
                    dep.getScope() != null ? dep.getScope().id() : "",
                    dep.isOptional(),
                    depth);
        }
        if (node.getArtifact() != null) {
            Artifact a = node.getArtifact();
            return new DependencyTreeModel.TreeNode(
                    a.getGroupId(),
                    a.getArtifactId(),
                    a.getClassifier() != null ? a.getClassifier() : "",
                    a.getVersion() != null ? a.getVersion().toString() : "?",
                    "",
                    false,
                    depth);
        }
        return new DependencyTreeModel.TreeNode("?", "?", "", "?", "", false, depth);
    }
}
