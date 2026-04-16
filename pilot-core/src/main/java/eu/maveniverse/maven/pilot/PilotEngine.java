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
import eu.maveniverse.domtrip.maven.PomEditor;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Panel creation logic decoupled from Maven plugin API.
 *
 * <p>Uses {@link PilotResolver} for dependency resolution and works
 * entirely with {@link PilotProject} — no Maven model types.</p>
 */
public class PilotEngine {

    private final PilotResolver resolver;
    private final List<PilotProject> allProjects;
    private final String scope;
    private final Map<String, DependencyTreeModel> treeCache = new java.util.concurrent.ConcurrentHashMap<>();

    public PilotEngine(PilotResolver resolver, List<PilotProject> allProjects, String scope) {
        this.resolver = resolver;
        this.allProjects = allProjects;
        this.scope = scope;
    }

    private DependencyTreeModel cachedCollectDependencies(PilotProject project) {
        return treeCache.computeIfAbsent(project.ga(), k -> resolver.collectDependencies(project));
    }

    public ToolPanel createPanel(String toolId, PilotProject proj, List<PilotProject> projects) throws Exception {
        return createPanel(toolId, proj, projects, s -> {});
    }

    public ToolPanel createPanel(
            String toolId, PilotProject proj, List<PilotProject> projects, Consumer<String> progress) throws Exception {
        return switch (toolId) {
            case "tree" -> createTreePanel(proj);
            case "dependencies" -> createDependenciesPanel(proj);
            case "updates" -> createUpdatesPanel(proj, projects, progress);
            case "conflicts" -> createConflictsPanel(proj, projects, progress);
            case "align" -> createAlignPanel(proj, projects);
            case "audit" -> createAuditPanel(proj, projects, progress);
            case "pom" -> createPomPanel(proj);
            case "search" -> createSearchPanel();
            default -> null;
        };
    }

    private ToolPanel createTreePanel(PilotProject proj) {
        DependencyTreeModel treeModel = cachedCollectDependencies(proj);
        return new TreeTui(treeModel, scope, proj.gav());
    }

    private ToolPanel createDependenciesPanel(PilotProject proj) throws Exception {
        Set<String> declaredGAs = new HashSet<>();
        List<DependenciesTui.DepEntry> declared = new ArrayList<>();
        for (PilotProject.Dep dep : proj.dependencies) {
            DependenciesTui.addDeclaredEntry(
                    declaredGAs,
                    declared,
                    dep.groupId(),
                    dep.artifactId(),
                    dep.classifier(),
                    dep.version(),
                    dep.scope());
        }

        PilotResolver.ResolvedDependencies resolved = resolver.resolveDependencies(proj);

        Set<String> transitiveGAs = new HashSet<>();
        List<DependenciesTui.DepEntry> transitive = new ArrayList<>();
        DependenciesTui.collectTransitive(resolved.tree().root, declaredGAs, transitiveGAs, transitive);

        Map<String, File> gaToJar = resolved.gaToJar();

        Path classesDir = proj.outputDirectory;
        Path testClassesDir = proj.testOutputDirectory;
        boolean bytecodeAnalyzed = false;

        if (classesDir != null && Files.isDirectory(classesDir)) {
            bytecodeAnalyzed = true;
            ClassFileScanner.ScanResult mainScan = ClassFileScanner.scanDirectory(classesDir);
            ClassFileScanner.ScanResult testScan = testClassesDir != null && Files.isDirectory(testClassesDir)
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
        }

        String pomPath = proj.pomPath.toString();
        return new DependenciesTui(declared, transitive, pomPath, proj.gav(), bytecodeAnalyzed);
    }

    private ToolPanel createUpdatesPanel(PilotProject proj, List<PilotProject> projects, Consumer<String> progress) {
        UpdatesTui.VersionResolver versionResolver = resolver::resolveVersions;
        ReactorCollector.CollectionResult result = ReactorCollector.collect(projects);
        ReactorModel reactorModel = ReactorModel.build(projects);
        String reactorGav = projects.get(0).gav();
        return new ReactorUpdatesTui(result, reactorModel, reactorGav, versionResolver);
    }

    private ToolPanel createConflictsPanel(PilotProject proj, List<PilotProject> projects, Consumer<String> progress) {
        if (projects.size() > 1) {
            Map<String, List<ConflictsTui.ConflictEntry>> mergedMap = new HashMap<>();
            for (int i = 0; i < projects.size(); i++) {
                PilotProject p = projects.get(i);
                if (projects.size() >= 100) {
                    progress.accept("Collecting conflicts… " + (i + 1) + "/" + projects.size() + "\n" + p.artifactId);
                }
                DependencyTreeModel tree = cachedCollectDependencies(p);
                List<String> modulePath = new ArrayList<>();
                modulePath.add("[" + p.artifactId + "]");
                collectConflicts(tree.root, mergedMap, modulePath);
            }
            List<ConflictsTui.ConflictGroup> conflicts = filterConflictGroups(mergedMap);
            PilotProject root = projects.get(0);
            String pomPath = root.pomPath.toString();
            String gav = root.gav() + " (reactor: " + projects.size() + " modules)";
            return new ConflictsTui(conflicts, pomPath, gav);
        } else {
            DependencyTreeModel tree = cachedCollectDependencies(proj);
            Map<String, List<ConflictsTui.ConflictEntry>> conflictMap = new HashMap<>();
            collectConflicts(tree.root, conflictMap, new ArrayList<>());
            List<ConflictsTui.ConflictGroup> conflicts = filterConflictGroups(conflictMap);
            String pomPath = proj.pomPath.toString();
            return new ConflictsTui(conflicts, pomPath, proj.gav());
        }
    }

    private ToolPanel createAlignPanel(PilotProject proj, List<PilotProject> projects) throws Exception {
        if (projects.size() > 1) {
            PilotProject managementTarget = findManagementPom(projects);
            String mgmtPomPath = managementTarget.pomPath.toString();
            String mgmtContent = Files.readString(managementTarget.pomPath);
            PomEditor mgmtEditor = new PomEditor(Document.of(mgmtContent));
            var detectedOptions = mgmtEditor.dependencies().detectConventions();
            List<String> childPomPaths = projects.stream()
                    .filter(p -> p != managementTarget)
                    .map(p -> p.pomPath.toString())
                    .toList();
            return new AlignTui(mgmtPomPath, childPomPaths, managementTarget.gav(), detectedOptions, null);
        } else {
            String pomPath = proj.pomPath.toString();
            String pomContent = Files.readString(proj.pomPath);
            PomEditor editor = new PomEditor(Document.of(pomContent));
            var detectedOptions = editor.dependencies().detectConventions();
            AlignTui.ParentPomInfo parentInfo = AlignHelper.findParentPomInfo(proj, allProjects);
            return new AlignTui(pomPath, proj.gav(), detectedOptions, parentInfo);
        }
    }

    private ToolPanel createAuditPanel(PilotProject proj, List<PilotProject> projects, Consumer<String> progress) {
        if (projects.size() > 1) {
            PilotProject root = projects.get(0);
            DependencyTreeModel treeModel = cachedCollectDependencies(root);
            Map<String, AuditTui.AuditEntry> entryMap = new LinkedHashMap<>();
            int emptyTrees = 0;
            for (int i = 0; i < projects.size(); i++) {
                PilotProject p = projects.get(i);
                if (projects.size() >= 100) {
                    progress.accept("Collecting audit data… " + (i + 1) + "/" + projects.size() + "\n" + p.artifactId);
                }
                DependencyTreeModel tree = cachedCollectDependencies(p);
                if (tree.root.children.isEmpty()) {
                    emptyTrees++;
                }
                collectAuditNode(tree.root, entryMap, p.artifactId, true);
            }
            System.err.println("[Pilot] Audit: " + projects.size() + " modules, "
                    + entryMap.size() + " unique dependencies, "
                    + emptyTrees + " modules with empty trees");
            List<AuditTui.AuditEntry> entries = new ArrayList<>(entryMap.values());
            String gav = root.gav() + " (reactor: " + projects.size() + " modules)";
            String pomPath = root.pomPath.toString();
            return new AuditTui(entries, gav, treeModel, pomPath);
        } else {
            DependencyTreeModel treeModel = cachedCollectDependencies(proj);
            Map<String, AuditTui.AuditEntry> entryMap = new LinkedHashMap<>();
            collectAuditNode(treeModel.root, entryMap, null, true);
            List<AuditTui.AuditEntry> entries = new ArrayList<>(entryMap.values());
            String pomPath = proj.pomPath.toString();
            return new AuditTui(entries, proj.gav(), treeModel, pomPath);
        }
    }

    private ToolPanel createPomPanel(PilotProject proj) throws Exception {
        String rawPom = Files.readString(proj.pomPath);
        String effectivePom = resolver.effectivePom(proj);
        XmlTreeModel effectiveTree = XmlTreeModel.parse(effectivePom);

        // Read parent POM contents for snippet display
        Map<String, String[]> parentPomContents = new LinkedHashMap<>();
        PilotProject current = proj.parent;
        while (current != null) {
            String modelId = current.gav();
            Path pomFile = current.pomPath;
            if (pomFile == null) {
                pomFile = resolver.resolveArtifact(current.groupId, current.artifactId, current.version, "pom");
            }
            if (pomFile != null && Files.exists(pomFile)) {
                try {
                    parentPomContents.put(modelId, Files.readString(pomFile).split("\n"));
                } catch (Exception ignored) {
                }
            }
            current = current.parent;
        }

        // Add super POM content for elements inherited from the built-in POM
        String superPomXml = resolver.superPom();
        if (superPomXml != null) {
            parentPomContents.put("super POM (built-in)", superPomXml.split("\n"));
        }

        return new PomTui(
                rawPom, effectiveTree, null, proj.pomPath.getFileName().toString(), parentPomContents);
    }

    private ToolPanel createSearchPanel() {
        CentralSearchClient client = new CentralSearchClient();
        return new SearchTui(client, "", List.of(), 0);
    }

    // ── Shared helpers ────────────────────────────────────────────────────

    static void collectManagedDependencies(
            List<PilotProject.Dep> originalMgmtDeps,
            List<PilotProject.Dep> effectiveMgmtDeps,
            List<UpdatesTui.DependencyInfo> dependencies) {
        if (originalMgmtDeps == null) {
            return;
        }
        Map<String, String> effectiveVersions = new HashMap<>();
        if (effectiveMgmtDeps != null) {
            for (PilotProject.Dep d : effectiveMgmtDeps) {
                effectiveVersions.put(d.ga(), d.version());
            }
        }
        for (PilotProject.Dep dep : originalMgmtDeps) {
            if (dep.isBomImport()) {
                continue;
            }
            boolean alreadyListed = dependencies.stream()
                    .anyMatch(d -> d.groupId.equals(dep.groupId()) && d.artifactId.equals(dep.artifactId()));
            if (!alreadyListed) {
                String version = effectiveVersions.getOrDefault(dep.ga(), dep.version());
                var info = new UpdatesTui.DependencyInfo(
                        dep.groupId(), dep.artifactId(), version, dep.scope(), dep.type());
                info.managed = true;
                dependencies.add(info);
            }
        }
    }

    static PilotProject findManagementPom(List<PilotProject> projects) {
        Set<String> projectPaths = new HashSet<>();
        for (PilotProject p : projects) {
            projectPaths.add(p.pomPath.toString());
        }

        PilotProject child = projects.size() > 1 ? projects.get(1) : projects.get(0);
        PilotProject current = child.parent;

        while (current != null && current.pomPath != null) {
            if (!projectPaths.contains(current.pomPath.toString())) {
                break;
            }
            var mgmt = current.originalManagedDependencies;
            if (mgmt != null && !mgmt.isEmpty()) {
                return current;
            }
            current = current.parent;
        }

        return projects.get(0);
    }

    private static void collectConflicts(
            DependencyTreeModel.TreeNode node,
            Map<String, List<ConflictsTui.ConflictEntry>> conflicts,
            List<String> path) {
        for (DependencyTreeModel.TreeNode child : node.children) {
            String ga = child.ga();
            String requestedVersion = child.version;
            String resolvedVersion = child.version;
            if (child.requestedVersion != null) {
                requestedVersion = child.requestedVersion;
            }

            List<String> currentPath = new ArrayList<>(path);
            currentPath.add(ga);

            var entry = new ConflictsTui.ConflictEntry(
                    child.groupId,
                    child.artifactId,
                    requestedVersion,
                    resolvedVersion,
                    String.join(" → ", currentPath),
                    child.scope);

            conflicts.computeIfAbsent(ga, k -> new ArrayList<>()).add(entry);
            collectConflicts(child, conflicts, currentPath);
        }
    }

    private static List<ConflictsTui.ConflictGroup> filterConflictGroups(
            Map<String, List<ConflictsTui.ConflictEntry>> conflictMap) {
        return conflictMap.entrySet().stream()
                .filter(e -> e.getValue().size() > 1
                        || e.getValue().stream()
                                .anyMatch(c ->
                                        c.requestedVersion != null && !c.requestedVersion.equals(c.resolvedVersion)))
                .map(e -> new ConflictsTui.ConflictGroup(e.getKey(), e.getValue()))
                .collect(Collectors.toList());
    }

    private static void collectAuditNode(
            DependencyTreeModel.TreeNode node,
            Map<String, AuditTui.AuditEntry> entryMap,
            String moduleName,
            boolean isRoot) {
        if (!isRoot) {
            String ga = node.ga();
            AuditTui.AuditEntry entry = entryMap.get(ga);
            if (entry == null) {
                entry = new AuditTui.AuditEntry(node.groupId, node.artifactId, node.version, node.scope);
                entryMap.put(ga, entry);
            }
            if (moduleName != null && !entry.modules.contains(moduleName)) {
                entry.modules.add(moduleName);
            }
        }
        for (DependencyTreeModel.TreeNode child : node.children) {
            collectAuditNode(child, entryMap, moduleName, false);
        }
    }
}
