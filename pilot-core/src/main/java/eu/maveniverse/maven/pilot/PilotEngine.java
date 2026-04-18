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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

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
    private final Map<String, AuditTui.AuditEntry> auditEntryCache = new java.util.concurrent.ConcurrentHashMap<>();

    public PilotEngine(PilotResolver resolver, List<PilotProject> allProjects, String scope) {
        this.resolver = resolver;
        this.allProjects = allProjects;
        this.scope = scope;
    }

    private DependencyTreeModel cachedCollectDependencies(PilotProject project) {
        return treeCache.computeIfAbsent(project.ga(), k -> resolver.collectDependencies(project));
    }

    private void resolveAllDependencies(List<PilotProject> projects, Consumer<String> progress) {
        AtomicInteger loaded = new AtomicInteger();
        int total = projects.size();
        projects.parallelStream().forEach(p -> {
            cachedCollectDependencies(p);
            synchronized (loaded) {
                int count = loaded.incrementAndGet();
                progress.accept("Resolving dependencies… " + count + "/" + total + "\n" + p.artifactId);
            }
        });
    }

    public ToolPanel createPanel(String toolId, PilotProject proj, List<PilotProject> projects) throws Exception {
        return createPanel(toolId, proj, projects, null, null, s -> {});
    }

    public ToolPanel createPanel(
            String toolId,
            PilotProject proj,
            List<PilotProject> projects,
            PomEditSession session,
            java.util.function.Function<java.nio.file.Path, PomEditSession> sessionProvider,
            Consumer<String> progress)
            throws Exception {
        return switch (toolId) {
            case "dependencies" -> createDependenciesPanel(proj, projects, session, progress);
            case "updates" -> createUpdatesPanel(proj, projects, session, sessionProvider, progress);
            case "conflicts" -> createConflictsPanel(proj, projects, session, progress);
            case "audit" -> createAuditPanel(proj, projects, session, progress);
            case "pom" -> createPomPanel(proj, session);
            case "align" -> createAlignPanel(proj, projects);
            case "search" -> createSearchPanel();
            default -> null;
        };
    }

    private ToolPanel createDependenciesPanel(
            PilotProject proj, List<PilotProject> projects, PomEditSession session, Consumer<String> progress)
            throws Exception {
        if (projects.size() > 1) {
            return createReactorDependenciesPanel(projects, progress);
        }
        return createSingleDependenciesPanel(proj, session);
    }

    private ToolPanel createSingleDependenciesPanel(PilotProject proj, PomEditSession session) throws Exception {
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

        List<DependenciesTui.ManagedEntry> managed = buildManagedEntries(proj);

        DependencyTreeModel treeModel = cachedCollectDependencies(proj);
        TreeTui treeTui = new TreeTui(treeModel, scope, proj.gav());

        PomEditSession s = session != null ? session : new PomEditSession(proj.pomPath);
        return new DependenciesTui(declared, transitive, managed, s, proj.gav(), bytecodeAnalyzed, treeTui);
    }

    private ToolPanel createReactorDependenciesPanel(List<PilotProject> projects, Consumer<String> progress)
            throws Exception {
        Map<String, DependenciesTui.DepEntry> unusedMap = new LinkedHashMap<>();
        Map<String, DependenciesTui.DepEntry> usedTransMap = new LinkedHashMap<>();
        int modulesScanned = 0;
        int modulesSkipped = 0;

        for (PilotProject p : projects) {
            Path classesDir = p.outputDirectory;
            Path testClassesDir = p.testOutputDirectory;
            if (classesDir == null || !Files.isDirectory(classesDir)) {
                modulesSkipped++;
                continue;
            }
            modulesScanned++;
            progress.accept("Analyzing dependencies… " + modulesScanned + "/" + projects.size() + "\n" + p.artifactId);

            Set<String> declaredGAs = new HashSet<>();
            List<DependenciesTui.DepEntry> declared = new ArrayList<>();
            for (PilotProject.Dep dep : p.dependencies) {
                DependenciesTui.addDeclaredEntry(
                        declaredGAs,
                        declared,
                        dep.groupId(),
                        dep.artifactId(),
                        dep.classifier(),
                        dep.version(),
                        dep.scope());
            }

            PilotResolver.ResolvedDependencies resolved = resolver.resolveDependencies(p);
            Set<String> transitiveGAs = new HashSet<>();
            List<DependenciesTui.DepEntry> transitive = new ArrayList<>();
            DependenciesTui.collectTransitive(resolved.tree().root, declaredGAs, transitiveGAs, transitive);

            Map<String, java.io.File> gaToJar = resolved.gaToJar();

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
                DependencyUsageAnalyzer.UsageStatus us =
                        usage.declaredUsage().getOrDefault(dep.ga(), DependencyUsageAnalyzer.UsageStatus.UNDETERMINED);
                if (us == DependencyUsageAnalyzer.UsageStatus.UNUSED) {
                    DependenciesTui.DepEntry existing = unusedMap.get(dep.ga());
                    if (existing == null) {
                        dep.usageStatus = DependencyUsageAnalyzer.UsageStatus.UNUSED;
                        dep.modules.add(p.artifactId);
                        unusedMap.put(dep.ga(), dep);
                    } else {
                        if (!existing.modules.contains(p.artifactId)) {
                            existing.modules.add(p.artifactId);
                        }
                    }
                }
            }

            for (var dep : transitive) {
                DependencyUsageAnalyzer.UsageStatus us = usage.transitiveUsage()
                        .getOrDefault(dep.ga(), DependencyUsageAnalyzer.UsageStatus.UNDETERMINED);
                if (us == DependencyUsageAnalyzer.UsageStatus.USED) {
                    DependenciesTui.DepEntry existing = usedTransMap.get(dep.ga());
                    if (existing == null) {
                        dep.usageStatus = DependencyUsageAnalyzer.UsageStatus.USED;
                        dep.modules.add(p.artifactId);
                        usedTransMap.put(dep.ga(), dep);
                    } else {
                        if (!existing.modules.contains(p.artifactId)) {
                            existing.modules.add(p.artifactId);
                        }
                    }
                }
            }
        }

        PilotProject root = projects.get(0);
        String gav = root.gav() + " (reactor: " + projects.size() + " modules)";
        return new DependenciesTui(
                new ArrayList<>(unusedMap.values()),
                new ArrayList<>(usedTransMap.values()),
                gav,
                modulesScanned,
                modulesSkipped);
    }

    @SuppressWarnings("unused")
    private ToolPanel createUpdatesPanel(
            PilotProject proj,
            List<PilotProject> projects,
            PomEditSession session,
            java.util.function.Function<java.nio.file.Path, PomEditSession> sessionProvider,
            Consumer<String> progress) {
        UpdatesTui.VersionResolver versionResolver = resolver::resolveVersions;
        List<PilotProject> targetProjects = projects.size() > 1 ? projects : List.of(proj);
        ReactorCollector.CollectionResult result = ReactorCollector.collect(targetProjects);
        ReactorModel reactorModel = ReactorModel.build(targetProjects);
        String gav = targetProjects.get(0).gav();
        return new UpdatesTui(result, reactorModel, gav, versionResolver, sessionProvider);
    }

    private ToolPanel createConflictsPanel(
            PilotProject proj, List<PilotProject> projects, PomEditSession session, Consumer<String> progress) {
        List<PilotProject> targetProjects = projects.size() > 1 ? projects : List.of(proj);
        resolveAllDependencies(targetProjects, progress);
        String gav = projects.size() > 1
                ? projects.get(0).gav() + " (reactor: " + projects.size() + " modules)"
                : proj.gav();
        PomEditSession s = session != null ? session : new PomEditSession(proj.pomPath);
        return new ConflictsTui(targetProjects, s, gav, this::cachedCollectDependencies);
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

    private ToolPanel createAuditPanel(
            PilotProject proj, List<PilotProject> projects, PomEditSession session, Consumer<String> progress) {
        if (projects.size() > 1) {
            resolveAllDependencies(projects, progress);
            PilotProject root = projects.get(0);
            DependencyTreeModel treeModel = cachedCollectDependencies(root);
            Map<String, AuditTui.AuditEntry> entryMap = new LinkedHashMap<>();
            int emptyTrees = 0;
            for (PilotProject p : projects) {
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
            if (session != null) {
                return new AuditTui(entries, gav, treeModel, session);
            }
            return new AuditTui(entries, gav, treeModel, root.pomPath.toString());
        } else {
            DependencyTreeModel treeModel = cachedCollectDependencies(proj);
            Map<String, AuditTui.AuditEntry> entryMap = new LinkedHashMap<>();
            collectAuditNode(treeModel.root, entryMap, null, true);
            List<AuditTui.AuditEntry> entries = new ArrayList<>(entryMap.values());
            if (session != null) {
                return new AuditTui(entries, proj.gav(), treeModel, session);
            }
            return new AuditTui(entries, proj.gav(), treeModel, proj.pomPath.toString());
        }
    }

    private ToolPanel createPomPanel(PilotProject proj, PomEditSession session) throws java.io.IOException {
        String rawPom = (session != null && session.isDirty()) ? session.currentXml() : Files.readString(proj.pomPath);
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

    /**
     * Build the list of managed dependency entries for the Managed tab.
     * "own" entries come from originalManagedDependencies (declared in this POM),
     * "inherited" entries come from effective managedDependencies not in the original set.
     */
    private static List<DependenciesTui.ManagedEntry> buildManagedEntries(PilotProject proj) {
        List<DependenciesTui.ManagedEntry> managed = new ArrayList<>();
        Set<String> ownGAs = new HashSet<>();

        // Own entries (declared in this POM's dependencyManagement)
        if (proj.originalManagedDependencies != null) {
            for (PilotProject.Dep dep : proj.originalManagedDependencies) {
                ownGAs.add(dep.ga());
                managed.add(new DependenciesTui.ManagedEntry(
                        dep.groupId(), dep.artifactId(), dep.version(), dep.scope(), dep.type(), "own"));
            }
        }

        // Inherited entries (from parent/BOMs, not declared in this POM)
        if (proj.managedDependencies != null) {
            for (PilotProject.Dep dep : proj.managedDependencies) {
                if (!ownGAs.contains(dep.ga())) {
                    managed.add(new DependenciesTui.ManagedEntry(
                            dep.groupId(), dep.artifactId(), dep.version(), dep.scope(), dep.type(), "inherited"));
                }
            }
        }

        return managed;
    }

    // ── Shared helpers ────────────────────────────────────────────────────

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

    private void collectAuditNode(
            DependencyTreeModel.TreeNode node,
            Map<String, AuditTui.AuditEntry> entryMap,
            String moduleName,
            boolean isRoot) {
        if (!isRoot) {
            String gav = node.ga() + ":" + node.version;
            AuditTui.AuditEntry entry = entryMap.computeIfAbsent(
                    gav,
                    k -> auditEntryCache.computeIfAbsent(
                            k, kk -> new AuditTui.AuditEntry(node.groupId, node.artifactId, node.version, node.scope)));
            if (moduleName != null && !entry.modules.contains(moduleName)) {
                entry.modules.add(moduleName);
            }
        }
        for (DependencyTreeModel.TreeNode child : node.children) {
            collectAuditNode(child, entryMap, moduleName, false);
        }
    }
}
