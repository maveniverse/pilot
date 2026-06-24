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

import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/**
 * Maven-agnostic project representation used throughout the TUI layer.
 *
 * <p>This class decouples the TUI code from {@code MavenProject} so that
 * Pilot can be driven from either a Maven plugin context or a standalone
 * Maven 4 API session.</p>
 */
public class PilotProject {

    final String groupId;
    final String artifactId;
    final String version;
    final String packaging;
    public final Path basedir;
    public final Path pomPath;
    final List<Dep> dependencies;
    final List<Dep> managedDependencies;
    final List<Dep> originalDependencies;
    final List<Dep> originalManagedDependencies;
    final Properties originalProperties;
    final Path outputDirectory;
    final Path testOutputDirectory;
    public PilotProject parent;
    private List<Plugin> plugins = List.of();
    private List<Plugin> managedPlugins = List.of();

    public List<Plugin> getPlugins() {
        return plugins;
    }

    public void setPlugins(List<Plugin> plugins) {
        this.plugins = plugins;
    }

    public List<Plugin> getManagedPlugins() {
        return managedPlugins;
    }

    public void setManagedPlugins(List<Plugin> managedPlugins) {
        this.managedPlugins = managedPlugins;
    }

    public PilotProject(
            String groupId,
            String artifactId,
            String version,
            String packaging,
            Path basedir,
            Path pomPath,
            List<Dep> dependencies,
            List<Dep> managedDependencies,
            List<Dep> originalDependencies,
            List<Dep> originalManagedDependencies,
            Properties originalProperties,
            Path outputDirectory,
            Path testOutputDirectory) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
        this.packaging = packaging;
        this.basedir = basedir;
        this.pomPath = pomPath;
        this.dependencies = dependencies;
        this.managedDependencies = managedDependencies;
        this.originalDependencies = originalDependencies;
        this.originalManagedDependencies = originalManagedDependencies;
        this.originalProperties = originalProperties;
        this.outputDirectory = outputDirectory;
        this.testOutputDirectory = testOutputDirectory;
    }

    public String ga() {
        return groupId + ":" + artifactId;
    }

    public String gav() {
        return groupId + ":" + artifactId + ":" + version;
    }

    /**
     * Simplified dependency representation, independent of Maven model types.
     */
    public record Dep(
            String groupId,
            String artifactId,
            String version,
            String scope,
            String type,
            String classifier,
            boolean optional,
            List<Excl> exclusions) {

        Dep(String groupId, String artifactId, String version, String scope, String type) {
            this(groupId, artifactId, version, scope, type, null, false, List.of());
        }

        Dep(String groupId, String artifactId, String version) {
            this(groupId, artifactId, version, null, null);
        }

        String ga() {
            return groupId + ":" + artifactId;
        }

        boolean isBomImport() {
            return "pom".equals(type) && "import".equals(scope);
        }
    }

    /**
     * Simplified exclusion representation.
     */
    public record Excl(String groupId, String artifactId) {}

    /**
     * Simplified plugin representation, independent of Maven model types.
     */
    public record Plugin(
            String groupId, String artifactId, String version, List<Dep> dependencies, List<Excl> exclusions) {

        Plugin(String groupId, String artifactId, String version) {
            this(groupId, artifactId, version, List.of(), List.of());
        }

        String ga() {
            return groupId + ":" + artifactId;
        }

        String gav() {
            return groupId + ":" + artifactId + ":" + version;
        }
    }
}
