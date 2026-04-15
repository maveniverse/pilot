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
import java.io.File;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectResult;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.aether.resolution.VersionRangeRequest;
import org.eclipse.aether.resolution.VersionRangeResult;

/**
 * Maven 3 implementation of {@link PilotResolver} using the Aether
 * {@link RepositorySystem} and existing {@link MavenProject} instances.
 */
class Maven3PilotResolver implements PilotResolver {

    private final RepositorySystem repoSystem;
    private final RepositorySystemSession repoSession;
    private final MavenProject rootProject;
    private final IdentityHashMap<PilotProject, MavenProject> pilotToMaven;

    Maven3PilotResolver(
            RepositorySystem repoSystem,
            RepositorySystemSession repoSession,
            MavenProject rootProject,
            IdentityHashMap<PilotProject, MavenProject> pilotToMaven) {
        this.repoSystem = repoSystem;
        this.repoSession = repoSession;
        this.rootProject = rootProject;
        this.pilotToMaven = pilotToMaven;
    }

    @Override
    public DependencyTreeModel collectDependencies(PilotProject project) {
        try {
            MavenProject mp = requireMaven(project);
            CollectResult result = repoSystem.collectDependencies(repoSession, MojoHelper.buildCollectRequest(mp));
            return MojoHelper.fromDependencyNode(result.getRoot());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to collect dependencies for " + project.gav(), e);
        }
    }

    @Override
    public ResolvedDependencies resolveDependencies(PilotProject project) {
        try {
            MavenProject mp = requireMaven(project);
            DependencyRequest depRequest = new DependencyRequest(MojoHelper.buildCollectRequest(mp), null);
            DependencyResult depResult = repoSystem.resolveDependencies(repoSession, depRequest);
            DependencyTreeModel tree = MojoHelper.fromDependencyNode(depResult.getRoot());
            Map<String, File> gaToJar = new HashMap<>();
            for (ArtifactResult ar : depResult.getArtifactResults()) {
                var art = ar.getArtifact();
                if (art != null
                        && art.getFile() != null
                        && art.getFile().getName().endsWith(".jar")) {
                    gaToJar.put(art.getGroupId() + ":" + art.getArtifactId(), art.getFile());
                }
            }
            return new ResolvedDependencies(tree, gaToJar);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to resolve dependencies for " + project.gav(), e);
        }
    }

    @Override
    public List<String> resolveVersions(String groupId, String artifactId) {
        try {
            VersionRangeRequest request = new VersionRangeRequest();
            request.setArtifact(new DefaultArtifact(groupId, artifactId, "jar", "[0,)"));
            request.setRepositories(rootProject.getRemoteProjectRepositories());
            VersionRangeResult result = repoSystem.resolveVersionRange(repoSession, request);
            return UpdatesTui.versionsNewestFirst(result.getVersions());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to resolve versions for " + groupId + ":" + artifactId, e);
        }
    }

    @Override
    public Path resolveArtifact(String groupId, String artifactId, String version, String type) {
        try {
            var artifact = new DefaultArtifact(groupId, artifactId, type, version);
            var request = new ArtifactRequest(artifact, rootProject.getRemoteProjectRepositories(), null);
            var result = repoSystem.resolveArtifact(repoSession, request);
            return result.getArtifact().getFile().toPath();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String effectivePom(PilotProject project) {
        try {
            MavenProject mp = requireMaven(project);
            StringWriter sw = new StringWriter();
            new MavenXpp3Writer().write(sw, mp.getModel());
            return sw.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize effective POM for " + project.gav(), e);
        }
    }

    @Override
    public String superPom() {
        try (var in = getClass().getResourceAsStream("/org/apache/maven/model/pom-4.0.0.xml")) {
            if (in != null) {
                return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private MavenProject requireMaven(PilotProject project) {
        MavenProject mp = pilotToMaven.get(project);
        if (mp == null) {
            throw new IllegalStateException("No MavenProject mapping for " + project.gav());
        }
        return mp;
    }
}
