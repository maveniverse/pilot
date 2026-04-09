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

import eu.maveniverse.domtrip.Document;
import eu.maveniverse.domtrip.Element;
import eu.maveniverse.domtrip.Node;
import eu.maveniverse.domtrip.maven.PomEditor;
import java.io.File;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.InputLocation;
import org.apache.maven.model.InputLocationTracker;
import org.apache.maven.model.InputSource;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectResult;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.aether.resolution.VersionRangeRequest;
import org.eclipse.aether.resolution.VersionRangeResult;

/**
 * Interactive launcher that displays the reactor module tree and lets the user
 * pick a tool to run on a selected module.
 *
 * <p>For single-module projects, shows the tool picker directly.
 * For multi-module reactors, shows the module tree first, then the tool picker.</p>
 *
 * <p>Usage:</p>
 * <pre>
 * mvn pilot:pilot
 * </pre>
 *
 * @since 0.2.0
 */
@Mojo(name = "pilot", requiresProject = true, aggregator = true, threadSafe = true)
public class PilotMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    private RepositorySystemSession repoSession;

    @Inject
    private RepositorySystem repoSystem;

    @Parameter(property = "scope", defaultValue = "compile")
    private String scope;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            List<MavenProject> projects = session.getProjects();
            if (projects.size() > 1) {
                executeReactor(projects);
            } else {
                executeSingleProject(project);
            }
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to run pilot: " + e.getMessage(), e);
        }
    }

    private void executeSingleProject(MavenProject proj) throws Exception {
        String gav = gavOf(proj);
        while (true) {
            String tool = new ToolPickerTui(gav, false).pick();
            if (tool == null) break;
            runTool(tool, proj, List.of(proj));
        }
    }

    private void executeReactor(List<MavenProject> projects) throws Exception {
        ReactorModel reactorModel = ReactorModel.build(projects);
        MavenProject root = projects.get(0);
        String reactorGav = gavOf(root);

        while (true) {
            ModulePickerTui.PickResult result = new ModulePickerTui(reactorModel, reactorGav, "pilot").pick();
            if (result == null) break;
            List<MavenProject> selected = result.projects();
            String tool = new ToolPickerTui(gavOf(selected.get(0)), true).pick();
            if (tool == null) continue;
            switch (tool) {
                case "updates", "conflicts", "audit" ->
                    // Aggregate tools: call once with the selected subset
                    runTool(tool, selected.get(0), selected);
                default -> {
                    // Per-project tools: iterate over each selected project
                    for (MavenProject p : selected) {
                        runTool(tool, p, selected);
                    }
                }
            }
        }
    }

    private void runTool(String tool, MavenProject proj, List<MavenProject> projects) throws Exception {
        switch (tool) {
            case "tree" -> runTree(proj);
            case "dependencies" -> runDependencies(proj);
            case "pom" -> runPom(proj);
            case "align" -> runAlign(proj);
            case "updates" -> runUpdates(proj, projects);
            case "conflicts" -> runConflicts(proj, projects);
            case "audit" -> runAudit(proj, projects);
            default -> getLog().warn("Unknown tool: " + tool);
        }
    }

    // ── tree ────────────────────────────────────────────────────────────────

    private void runTree(MavenProject proj) throws Exception {
        CollectResult result = repoSystem.collectDependencies(repoSession, MojoHelper.buildCollectRequest(proj));
        new TreeTui(result.getRoot(), scope, gavOf(proj)).run();
    }

    // ── dependencies ────────────────────────────────────────────────────────

    private void runDependencies(MavenProject proj) throws Exception {
        Set<String> declaredGAs = new HashSet<>();
        List<DependenciesTui.DepEntry> declared = new ArrayList<>();
        for (Dependency dep : proj.getDependencies()) {
            DependenciesTui.addDeclaredEntry(
                    declaredGAs,
                    declared,
                    dep.getGroupId(),
                    dep.getArtifactId(),
                    dep.getClassifier(),
                    dep.getVersion(),
                    dep.getScope());
        }

        DependencyRequest depRequest = new DependencyRequest(MojoHelper.buildCollectRequest(proj), null);
        DependencyResult depResult = repoSystem.resolveDependencies(repoSession, depRequest);

        Set<String> transitiveGAs = new HashSet<>();
        List<DependenciesTui.DepEntry> transitive = new ArrayList<>();
        DependenciesTui.collectTransitive(depResult.getRoot(), declaredGAs, transitiveGAs, transitive);

        Map<String, File> gaToJar = new HashMap<>();
        for (ArtifactResult ar : depResult.getArtifactResults()) {
            var art = ar.getArtifact();
            if (art != null && art.getFile() != null && art.getFile().getName().endsWith(".jar")) {
                gaToJar.put(art.getGroupId() + ":" + art.getArtifactId(), art.getFile());
            }
        }

        Path classesDir = Path.of(proj.getBuild().getOutputDirectory());
        Path testClassesDir = Path.of(proj.getBuild().getTestOutputDirectory());
        boolean bytecodeAnalyzed = false;

        if (Files.isDirectory(classesDir)) {
            bytecodeAnalyzed = true;
            ClassFileScanner.ScanResult mainScan = ClassFileScanner.scanDirectory(classesDir);
            ClassFileScanner.ScanResult testScan = Files.isDirectory(testClassesDir)
                    ? ClassFileScanner.scanDirectory(testClassesDir)
                    : new ClassFileScanner.ScanResult(Set.of(), Map.of());
            Map<String, String> classIndex = DependencyUsageAnalyzer.buildClassIndex(gaToJar);
            DependencyUsageAnalyzer.AnalysisResult usage = DependencyUsageAnalyzer.analyze(
                    mainScan.referencedClasses(),
                    testScan.referencedClasses(),
                    classIndex,
                    gaToJar,
                    declared,
                    transitive);
            for (var dep : declared) {
                dep.usageStatus =
                        usage.declaredUsage().getOrDefault(dep.ga(), DependencyUsageAnalyzer.UsageStatus.UNDETERMINED);
            }
            for (var dep : transitive) {
                dep.usageStatus = usage.transitiveUsage()
                        .getOrDefault(dep.ga(), DependencyUsageAnalyzer.UsageStatus.UNDETERMINED);
            }
        } else {
            getLog().warn("target/classes not found \u2014 skipping bytecode analysis. Run 'mvn compile' first.");
        }

        String pomPath = proj.getFile().getAbsolutePath();
        new DependenciesTui(declared, transitive, pomPath, gavOf(proj), bytecodeAnalyzed).run();
    }

    // ── pom ─────────────────────────────────────────────────────────────────

    private void runPom(MavenProject proj) throws Exception {
        File pomFile = proj.getFile();
        String rawPom = Files.readString(pomFile.toPath());
        String[] rawLines = rawPom.split("\n");

        StringWriter sw = new StringWriter();
        new MavenXpp3Writer().write(sw, proj.getModel());
        String effectivePom = sw.toString();

        Map<String, String[]> parentPomContents = readParentPomContents(proj);

        XmlTreeModel effectiveTree = XmlTreeModel.parse(effectivePom);
        var originMap = new IdentityHashMap<Node, PomTui.OriginInfo>();
        attachOrigins(originMap, effectiveTree.root, proj.getModel(), rawLines, parentPomContents);

        new PomTui(rawPom, effectiveTree, originMap, pomFile.getName(), parentPomContents).run();
    }

    private Map<String, String[]> readParentPomContents(MavenProject proj) {
        Map<String, String[]> contents = new LinkedHashMap<>();
        MavenProject current = proj;
        while (current.getParent() != null) {
            current = current.getParent();
            String modelId = current.getGroupId() + ":" + current.getArtifactId() + ":" + current.getVersion();
            File parentFile = current.getFile();

            if (parentFile == null || !parentFile.exists()) {
                parentFile = resolveParentPom(current.getGroupId(), current.getArtifactId(), current.getVersion());
            }

            if (parentFile != null && parentFile.exists()) {
                try {
                    contents.put(modelId, Files.readString(parentFile.toPath()).split("\n"));
                } catch (Exception ignored) {
                }
            }
        }
        return contents;
    }

    private File resolveParentPom(String groupId, String artifactId, String version) {
        try {
            var artifact = new DefaultArtifact(groupId, artifactId, "pom", version);
            var request = new ArtifactRequest(artifact, project.getRemoteProjectRepositories(), null);
            var result = repoSystem.resolveArtifact(repoSession, request);
            return result.getArtifact().getFile();
        } catch (Exception e) {
            return null;
        }
    }

    private void attachOrigins(
            IdentityHashMap<Node, PomTui.OriginInfo> map,
            Element xmlElement,
            InputLocationTracker tracker,
            String[] rawLines,
            Map<String, String[]> parentPomContents) {
        for (Node child : XmlTreeModel.treeChildren(xmlElement)) {
            if (!(child instanceof Element childElement)) continue;

            InputLocation loc = tracker.getLocation(childElement.name());
            if (loc != null) {
                map.put(childElement, buildOriginInfo(loc, rawLines, parentPomContents));
            }

            try {
                String name = childElement.name();
                String getterName = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
                Method getter = tracker.getClass().getMethod(getterName);
                Object value = getter.invoke(tracker);

                if (value instanceof InputLocationTracker subTracker) {
                    attachOrigins(map, childElement, subTracker, rawLines, parentPomContents);
                } else if (value instanceof List<?> list) {
                    int listIdx = 0;
                    for (Node grandchild : XmlTreeModel.treeChildren(childElement)) {
                        if (grandchild instanceof Element grandchildElement) {
                            if (listIdx < list.size()
                                    && list.get(listIdx) instanceof InputLocationTracker itemTracker) {
                                InputLocation itemLoc = itemTracker.getLocation("");
                                if (itemLoc != null) {
                                    map.put(grandchildElement, buildOriginInfo(itemLoc, rawLines, parentPomContents));
                                }
                                attachOrigins(map, grandchildElement, itemTracker, rawLines, parentPomContents);
                            }
                            listIdx++;
                        }
                    }
                }
            } catch (NoSuchMethodException ignored) {
            } catch (Exception ignored) {
            }
        }
    }

    private PomTui.OriginInfo buildOriginInfo(
            InputLocation location, String[] rawLines, Map<String, String[]> parentPomContents) {
        InputSource source = location.getSource();
        String sourceName = (source != null && source.getModelId() != null) ? source.getModelId() : "this POM";
        int lineNum = location.getLineNumber();

        String[] sourceLines;
        if ("this POM".equals(sourceName)) {
            sourceLines = rawLines;
        } else if (parentPomContents != null && parentPomContents.containsKey(sourceName)) {
            sourceLines = parentPomContents.get(sourceName);
        } else {
            return new PomTui.OriginInfo(sourceName, lineNum, List.of());
        }

        return new PomTui.OriginInfo(sourceName, lineNum, buildSnippet(sourceLines, lineNum));
    }

    private List<String> buildSnippet(String[] lines, int targetLine) {
        if (targetLine <= 0 || lines == null || lines.length == 0) {
            return List.of();
        }
        List<String> snippet = new ArrayList<>();
        int start = Math.max(0, targetLine - 3);
        int end = Math.min(lines.length - 1, targetLine + 1);
        for (int i = start; i <= end; i++) {
            String prefix = (i == targetLine - 1) ? "\u2192 " : "  ";
            String lineNum = String.format("%4d", i + 1);
            snippet.add(prefix + lineNum + " \u2502 " + lines[i]);
        }
        return snippet;
    }

    // ── align ───────────────────────────────────────────────────────────────

    private void runAlign(MavenProject proj) throws Exception {
        String pomPath = proj.getFile().getAbsolutePath();
        String pomContent = Files.readString(Path.of(pomPath));
        PomEditor editor = new PomEditor(Document.of(pomContent));
        var detectedOptions = editor.dependencies().detectConventions();

        AlignTui.ParentPomInfo parentInfo = AlignHelper.findParentPomInfo(proj, session.getProjects());
        new AlignTui(pomPath, gavOf(proj), detectedOptions, parentInfo).run();
    }

    // ── updates ─────────────────────────────────────────────────────────────

    private void runUpdates(MavenProject proj, List<MavenProject> projects) throws Exception {
        UpdatesTui.VersionResolver versionResolver = createVersionResolver();
        if (projects.size() > 1) {
            ReactorCollector.CollectionResult result = ReactorCollector.collect(projects);
            ReactorModel reactorModel = ReactorModel.build(projects);
            String reactorGav = gavOf(projects.get(0));
            new ReactorUpdatesTui(result, reactorModel, reactorGav, versionResolver).run();
        } else {
            List<UpdatesTui.DependencyInfo> dependencies = new ArrayList<>();
            for (Dependency dep : proj.getDependencies()) {
                dependencies.add(new UpdatesTui.DependencyInfo(
                        dep.getGroupId(), dep.getArtifactId(), dep.getVersion(), dep.getScope(), dep.getType()));
            }
            if (proj.getDependencyManagement() != null) {
                for (Dependency dep : proj.getDependencyManagement().getDependencies()) {
                    boolean alreadyListed = dependencies.stream()
                            .anyMatch(d ->
                                    d.groupId.equals(dep.getGroupId()) && d.artifactId.equals(dep.getArtifactId()));
                    if (!alreadyListed) {
                        var info = new UpdatesTui.DependencyInfo(
                                dep.getGroupId(), dep.getArtifactId(), dep.getVersion(), dep.getScope(), dep.getType());
                        info.managed = true;
                        dependencies.add(info);
                    }
                }
            }
            String pomPath = proj.getFile().getAbsolutePath();
            new UpdatesTui(dependencies, pomPath, gavOf(proj), versionResolver).run();
        }
    }

    private UpdatesTui.VersionResolver createVersionResolver() {
        return (groupId, artifactId) -> {
            try {
                VersionRangeRequest request = new VersionRangeRequest();
                request.setArtifact(new DefaultArtifact(groupId, artifactId, "jar", "[0,)"));
                request.setRepositories(project.getRemoteProjectRepositories());
                VersionRangeResult result = repoSystem.resolveVersionRange(repoSession, request);
                return UpdatesTui.versionsNewestFirst(result.getVersions());
            } catch (Exception e) {
                throw new IllegalStateException("Failed to resolve versions for " + groupId + ":" + artifactId, e);
            }
        };
    }

    // ── conflicts ───────────────────────────────────────────────────────────

    private void runConflicts(MavenProject proj, List<MavenProject> projects) throws Exception {
        if (projects.size() > 1) {
            Map<String, List<ConflictsTui.ConflictEntry>> mergedMap = new HashMap<>();
            for (MavenProject p : projects) {
                CollectResult result = repoSystem.collectDependencies(repoSession, MojoHelper.buildCollectRequest(p));
                List<String> modulePath = new ArrayList<>();
                modulePath.add("[" + p.getArtifactId() + "]");
                collectConflicts(result.getRoot(), mergedMap, modulePath);
            }
            List<ConflictsTui.ConflictGroup> conflicts = mergedMap.entrySet().stream()
                    .filter(e -> e.getValue().size() > 1
                            || e.getValue().stream()
                                    .anyMatch(c -> c.requestedVersion != null
                                            && !c.requestedVersion.equals(c.resolvedVersion)))
                    .map(e -> new ConflictsTui.ConflictGroup(e.getKey(), e.getValue()))
                    .collect(Collectors.toList());
            MavenProject root = projects.get(0);
            String pomPath = root.getFile().getAbsolutePath();
            String gav = gavOf(root) + " (reactor: " + projects.size() + " modules)";
            new ConflictsTui(conflicts, pomPath, gav).run();
        } else {
            CollectResult result = repoSystem.collectDependencies(repoSession, MojoHelper.buildCollectRequest(proj));
            Map<String, List<ConflictsTui.ConflictEntry>> conflictMap = new HashMap<>();
            collectConflicts(result.getRoot(), conflictMap, new ArrayList<>());
            List<ConflictsTui.ConflictGroup> conflicts = conflictMap.entrySet().stream()
                    .filter(e -> e.getValue().size() > 1
                            || e.getValue().stream()
                                    .anyMatch(c -> c.requestedVersion != null
                                            && !c.requestedVersion.equals(c.resolvedVersion)))
                    .map(e -> new ConflictsTui.ConflictGroup(e.getKey(), e.getValue()))
                    .collect(Collectors.toList());
            String pomPath = proj.getFile().getAbsolutePath();
            new ConflictsTui(conflicts, pomPath, gavOf(proj)).run();
        }
    }

    private void collectConflicts(
            DependencyNode node, Map<String, List<ConflictsTui.ConflictEntry>> conflicts, List<String> path) {
        for (DependencyNode child : node.getChildren()) {
            if (child.getDependency() == null) continue;
            var art = child.getDependency().getArtifact();
            String ga = art.getGroupId() + ":" + art.getArtifactId();

            String requestedVersion = art.getVersion();
            String resolvedVersion = requestedVersion;

            if (child.getData().get("conflict.originalVersion") instanceof String original) {
                requestedVersion = original;
            }

            List<String> currentPath = new ArrayList<>(path);
            currentPath.add(ga);

            var entry = new ConflictsTui.ConflictEntry(
                    art.getGroupId(),
                    art.getArtifactId(),
                    requestedVersion,
                    resolvedVersion,
                    String.join(" \u2192 ", currentPath),
                    child.getDependency().getScope());

            conflicts.computeIfAbsent(ga, k -> new ArrayList<>()).add(entry);
            collectConflicts(child, conflicts, currentPath);
        }
    }

    // ── audit ───────────────────────────────────────────────────────────────

    private void runAudit(MavenProject proj, List<MavenProject> projects) throws Exception {
        if (projects.size() > 1) {
            MavenProject root = projects.get(0);
            CollectResult rootResult =
                    repoSystem.collectDependencies(repoSession, MojoHelper.buildCollectRequest(root));
            DependencyTreeModel treeModel = DependencyTreeModel.fromDependencyNode(rootResult.getRoot());

            List<AuditTui.AuditEntry> entries = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (MavenProject p : projects) {
                CollectResult result = repoSystem.collectDependencies(repoSession, MojoHelper.buildCollectRequest(p));
                collectAuditNode(result.getRoot(), entries, seen, true);
            }
            String gav = gavOf(root) + " (reactor: " + projects.size() + " modules)";
            String pomPath = root.getFile().getAbsolutePath();
            new AuditTui(entries, gav, treeModel, pomPath).run();
        } else {
            CollectResult result = repoSystem.collectDependencies(repoSession, MojoHelper.buildCollectRequest(proj));
            DependencyTreeModel treeModel = DependencyTreeModel.fromDependencyNode(result.getRoot());
            List<AuditTui.AuditEntry> entries = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            collectAuditNode(result.getRoot(), entries, seen, true);
            String pomPath = proj.getFile().getAbsolutePath();
            new AuditTui(entries, gavOf(proj), treeModel, pomPath).run();
        }
    }

    private void collectAuditNode(
            DependencyNode node, List<AuditTui.AuditEntry> entries, Set<String> seen, boolean isRoot) {
        if (!isRoot && node.getDependency() != null) {
            var art = node.getDependency().getArtifact();
            String ga = art.getGroupId() + ":" + art.getArtifactId();
            if (seen.add(ga)) {
                entries.add(new AuditTui.AuditEntry(
                        art.getGroupId(),
                        art.getArtifactId(),
                        art.getVersion(),
                        node.getDependency().getScope()));
            }
        }
        for (DependencyNode child : node.getChildren()) {
            collectAuditNode(child, entries, seen, false);
        }
    }

    // ── utils ───────────────────────────────────────────────────────────────

    private static String gavOf(MavenProject proj) {
        return proj.getGroupId() + ":" + proj.getArtifactId() + ":" + proj.getVersion();
    }
}
