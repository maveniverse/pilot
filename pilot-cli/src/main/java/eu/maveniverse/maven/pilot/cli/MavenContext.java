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

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionRequestPopulator;
import org.apache.maven.internal.aether.DefaultRepositorySystemSessionFactory;
import org.apache.maven.model.Dependency;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingResult;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.building.DefaultSettingsBuildingRequest;
import org.apache.maven.settings.building.SettingsBuilder;
import org.apache.maven.settings.building.SettingsBuildingResult;
import org.codehaus.plexus.ContainerConfiguration;
import org.codehaus.plexus.DefaultContainerConfiguration;
import org.codehaus.plexus.DefaultPlexusContainer;
import org.codehaus.plexus.PlexusConstants;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.CollectResult;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.graph.Exclusion;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;

/**
 * Lazy-initialized wrapper around Maven's embedded infrastructure.
 * Bootstraps Plexus, loads settings, and provides project/dependency resolution.
 */
public class MavenContext {

    private DefaultPlexusContainer container;
    private RepositorySystem repoSystem;
    private MavenExecutionRequest executionRequest;

    private synchronized void init() throws Exception {
        if (container != null) return;

        ContainerConfiguration config = new DefaultContainerConfiguration()
                .setClassPathScanning(PlexusConstants.SCANNING_INDEX)
                .setAutoWiring(true)
                .setJSR250Lifecycle(true);

        // Suppress JVM Unsafe deprecation warnings from Guice during init
        PrintStream origErr = System.err;
        System.setErr(new PrintStream(OutputStream.nullOutputStream()));
        try {
            container = new DefaultPlexusContainer(config);

            // Eagerly trigger Guice injection to suppress all Unsafe warnings
            container.lookup(SettingsBuilder.class);
        } finally {
            System.setErr(origErr);
        }

        // Load settings
        SettingsBuilder settingsBuilder = container.lookup(SettingsBuilder.class);
        DefaultSettingsBuildingRequest settingsRequest = new DefaultSettingsBuildingRequest();
        Path userSettings = Path.of(System.getProperty("user.home"), ".m2", "settings.xml");
        if (Files.exists(userSettings)) {
            settingsRequest.setUserSettingsFile(userSettings.toFile());
        }
        SettingsBuildingResult settingsResult = settingsBuilder.build(settingsRequest);
        Settings settings = settingsResult.getEffectiveSettings();

        // Build execution request
        MavenExecutionRequestPopulator populator = container.lookup(MavenExecutionRequestPopulator.class);
        executionRequest = new DefaultMavenExecutionRequest();
        Properties sysProps = new Properties();
        sysProps.putAll(System.getProperties());
        executionRequest.setSystemProperties(sysProps);
        populator.populateFromSettings(executionRequest, settings);
        populator.populateDefaults(executionRequest);

        repoSystem = container.lookup(RepositorySystem.class);

        // Handle CI-friendly ${revision} property (e.g. Nisse extension)
        detectCiFriendlyRevision();

        // Create repository session and attach to project building request
        var sessionFactory = container.lookup(DefaultRepositorySystemSessionFactory.class);
        var repoSession = sessionFactory.newRepositorySession(executionRequest);
        executionRequest.getProjectBuildingRequest().setRepositorySession(repoSession);
    }

    /**
     * Finds pom.xml in the current directory.
     */
    public Path findPom() {
        Path pom = Path.of("pom.xml");
        return Files.exists(pom) ? pom : null;
    }

    /**
     * Returns a short project name from the current pom.xml, or null.
     */
    public String projectName() {
        Path pom = findPom();
        if (pom == null) return null;
        try {
            MavenProject project = buildProject(pom);
            return project.getArtifactId();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Builds a MavenProject from the given POM file.
     */
    public MavenProject buildProject(Path pomFile) throws Exception {
        init();
        ProjectBuilder projectBuilder = container.lookup(ProjectBuilder.class);
        ProjectBuildingRequest buildingRequest =
                new DefaultProjectBuildingRequest(executionRequest.getProjectBuildingRequest());
        buildingRequest.setProcessPlugins(false);
        buildingRequest.setResolveDependencies(false);
        ProjectBuildingResult result = projectBuilder.build(pomFile.toFile(), buildingRequest);
        return result.getProject();
    }

    /**
     * Collects the dependency tree for the given project.
     */
    public DependencyNode collectDependencies(MavenProject project) throws Exception {
        init();
        RepositorySystemSession session =
                executionRequest.getProjectBuildingRequest().getRepositorySession();

        CollectRequest collectRequest = new CollectRequest();
        collectRequest.setRootArtifact(new DefaultArtifact(
                project.getGroupId(), project.getArtifactId(),
                project.getPackaging(), project.getVersion()));
        collectRequest.setDependencies(
                project.getDependencies().stream().map(this::convert).collect(Collectors.toList()));
        if (project.getDependencyManagement() != null) {
            collectRequest.setManagedDependencies(project.getDependencyManagement().getDependencies().stream()
                    .map(this::convert)
                    .collect(Collectors.toList()));
        }
        collectRequest.setRepositories(project.getRemoteProjectRepositories());

        try {
            CollectResult result = repoSystem.collectDependencies(session, collectRequest);
            return result.getRoot();
        } catch (DependencyCollectionException e) {
            // Return partial tree — some dependencies may not have been resolved
            // (e.g. CI-friendly ${revision} in installed POMs)
            if (e.getResult() != null && e.getResult().getRoot() != null) {
                return e.getResult().getRoot();
            }
            throw e;
        }
    }

    /**
     * Resolves a POM artifact and returns its path.
     */
    public Path resolveArtifact(String groupId, String artifactId, String version, List<?> remoteRepos)
            throws Exception {
        init();
        RepositorySystemSession session =
                executionRequest.getProjectBuildingRequest().getRepositorySession();
        var artifact = new DefaultArtifact(groupId, artifactId, "pom", version);
        var request = new ArtifactRequest(artifact, null, null);
        if (remoteRepos != null) {
            @SuppressWarnings("unchecked")
            var repos = (List<RemoteRepository>) remoteRepos;
            request.setRepositories(repos);
        }
        var result = repoSystem.resolveArtifact(session, request);
        return result.getArtifact().getFile().toPath();
    }

    /**
     * Detects if the root POM uses {@code ${revision}} and computes
     * the version from git, mimicking the Nisse extension behavior.
     */
    private void detectCiFriendlyRevision() {
        try {
            Path pomFile = findPom();
            if (pomFile == null) return;
            String content = Files.readString(pomFile);
            if (!content.contains("${revision}")) return;

            // Already set (e.g. via -Drevision=...)
            if (executionRequest.getUserProperties().containsKey("revision")) return;
            if (System.getProperties().containsKey("revision")) return;

            String version = computeGitVersion();
            if (version != null) {
                executionRequest.getUserProperties().setProperty("revision", version);
                executionRequest.getSystemProperties().setProperty("revision", version);
                // Also set as JVM system property so it's available during
                // artifact descriptor reading (parent POM resolution)
                System.setProperty("revision", version);
            }
        } catch (Exception e) {
            // ignore — project building will report the error
        }
    }

    /**
     * Computes a version string from git tags, similar to Nisse's jgit.dynamicVersion.
     * Format: tag → "1.0.0", tag+N commits → "1.0.0-N-SNAPSHOT"
     * Falls back to scanning the local Maven repository for installed versions.
     */
    private String computeGitVersion() {
        try {
            String gitCmd = findGitExecutable();
            Process p = new ProcessBuilder(gitCmd, "describe", "--tags", "--long")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(p.getInputStream().readAllBytes()).trim();
            if (p.waitFor() == 0 && !output.isEmpty()) {
                // Strip leading 'v' if present
                if (output.startsWith("v")) output = output.substring(1);

                // Pattern: <tag>-<count>-g<hash>
                int lastDash = output.lastIndexOf('-');
                if (lastDash < 0) return output;
                int secondLastDash = output.lastIndexOf('-', lastDash - 1);
                if (secondLastDash < 0) return output;

                String tag = output.substring(0, secondLastDash);
                String countStr = output.substring(secondLastDash + 1, lastDash);
                int count = Integer.parseInt(countStr);

                return count == 0 ? tag : tag + "-" + count + "-SNAPSHOT";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // fall through to local repo scan
        }

        // No tags — scan local Maven repo for an installed version
        return scanLocalRepoVersion();
    }

    /**
     * Scans the local Maven repository for an installed version of the root project,
     * preferring SNAPSHOT versions.
     */
    private String scanLocalRepoVersion() {
        try {
            Path pomFile = findPom();
            if (pomFile == null) return null;
            String content = Files.readString(pomFile);
            // Extract groupId and artifactId from the root POM
            String groupId = extractXmlValue(content, "groupId");
            String artifactId = extractXmlValue(content, "artifactId");
            if (groupId == null || artifactId == null) return null;

            Path repoDir = Path.of(
                    System.getProperty("user.home"), ".m2", "repository", groupId.replace('.', '/'), artifactId);
            if (!Files.isDirectory(repoDir)) return null;

            // Pick the most recently modified version directory
            String best = null;
            long bestTime = -1;
            try (var dirs = Files.list(repoDir)) {
                for (Path dir : dirs.toList()) {
                    if (!Files.isDirectory(dir)) continue;
                    String name = dir.getFileName().toString();
                    if (name.startsWith("$")) continue;
                    long mtime = Files.getLastModifiedTime(dir).toMillis();
                    if (mtime > bestTime) {
                        bestTime = mtime;
                        best = name;
                    }
                }
            }
            return best;
        } catch (Exception e) {
            return null;
        }
    }

    private static String findGitExecutable() {
        for (String dir : List.of("/usr/bin", "/usr/local/bin", "/opt/homebrew/bin")) {
            Path git = Path.of(dir, "git");
            if (Files.isExecutable(git)) {
                return git.toString();
            }
        }
        return "git"; // fallback to PATH
    }

    private String extractXmlValue(String xml, String tag) {
        // Simple extraction — looks for direct child of <project>,
        // stripping blocks that could contain nested elements with the same tag name
        String stripped = xml.replaceAll("(?s)<parent>.*?</parent>", "")
                .replaceAll("(?s)<dependencies>.*?</dependencies>", "")
                .replaceAll("(?s)<build>.*?</build>", "")
                .replaceAll("(?s)<profiles>.*?</profiles>", "");
        int start = stripped.indexOf("<" + tag + ">");
        if (start < 0) return null;
        start += tag.length() + 2;
        int end = stripped.indexOf("</" + tag + ">", start);
        if (end < 0) return null;
        String value = stripped.substring(start, end).trim();
        return value.contains("$") ? null : value;
    }

    private org.eclipse.aether.graph.Dependency convert(Dependency dep) {
        var artifact = new DefaultArtifact(
                dep.getGroupId(),
                dep.getArtifactId(),
                dep.getClassifier() != null ? dep.getClassifier() : "",
                dep.getType() != null ? dep.getType() : "jar",
                dep.getVersion());
        var d = new org.eclipse.aether.graph.Dependency(artifact, dep.getScope(), dep.isOptional());
        if (dep.getExclusions() != null && !dep.getExclusions().isEmpty()) {
            d = d.setExclusions(dep.getExclusions().stream()
                    .map(e -> new Exclusion(e.getGroupId(), e.getArtifactId(), "*", "*"))
                    .collect(Collectors.toList()));
        }
        return d;
    }
}
