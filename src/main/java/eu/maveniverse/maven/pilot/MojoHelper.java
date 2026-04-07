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

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.Exclusion;

/**
 * Shared utilities for Mojo implementations.
 */
final class MojoHelper {

    /**
 * Private constructor to prevent instantiation of this utility class.
 */
private MojoHelper() {}

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
                    .collect(Collectors.toList()));
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
        return deps.stream().map(MojoHelper::convertDependency).collect(Collectors.toList());
    }

    /**
     * Build a CollectRequest populated from the given MavenProject.
     *
     * @param project the Maven project whose artifact, dependencies, managed dependencies, and remote repositories will populate the request
     * @return a CollectRequest with the project's root artifact, converted dependencies, managed dependencies (if present), and repositories
     */
    static CollectRequest buildCollectRequest(MavenProject project) {
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
}
