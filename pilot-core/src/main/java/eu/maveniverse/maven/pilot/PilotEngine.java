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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Panel creation logic decoupled from Maven plugin API.
 *
 * <p>Uses {@link PilotResolver} for dependency resolution and works
 * entirely with {@link PilotProject} — no Maven model types.</p>
 */
public class PilotEngine {

    private static final Logger LOGGER = Logger.getLogger(PilotEngine.class.getName());
    private final PilotResolver resolver;
    private final List<PilotProject> allProjects;
    private final String scope;
    private final Map<String, DependencyTreeModel> treeCache = new ConcurrentHashMap<>();
    private final Map<String, AuditTui.AuditEntry> auditEntryCache = new ConcurrentHashMap<>();
    private static final Set<String> TEST_SCOPES = Set.of("test", "test-only", "test-runtime");

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
            int count = loaded.incrementAndGet();
            progress.accept("Resolving dependencies… " + count + "/" + total + "\n" + p.artifactId);
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
            Function<Path, PomEditSession> sessionProvider,
            Consumer<String> progress)
            throws Exception {
        return switch (toolId) {
            case "dependencies" -> createDependenciesPanel(proj, projects, session, sessionProvider, progress);
            case "updates" -> createUpdatesPanel(proj, projects, session, sessionProvider, progress);
            case "conflicts" -> createConflictsPanel(proj, projects, session, sessionProvider, progress);
            case "audit" -> createAuditPanel(proj, projects, session, sessionProvider, progress);
            case "pom" -> createPomPanel(proj, session);
            case "align" -> createAlignPanel(proj, projects);
            case "search" -> createSearchPanel();
            default -> null;
        };
    }

    private ToolPanel createDependenciesPanel(
            PilotProject proj,
            List<PilotProject> projects,
            PomEditSession session,
            Function<Path, PomEditSession> sessionProvider,
            Consumer<String> progress)
            throws Exception {
        if (projects.size() > 1) {
            return createReactorDependenciesPanel(projects, sessionProvider, progress);
        }
        return createSingleDependenciesPanel(proj, session, projects, sessionProvider);
    }

    @SuppressWarnings("squid:S112") // private method; callers throw mixed checked exceptions
    private ToolPanel createSingleDependenciesPanel(
            PilotProject proj,
            PomEditSession session,
            List<PilotProject> projects,
            Function<Path, PomEditSession> sessionProvider)
            throws Exception {
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
            populateDepDetails(declared, transitive, classIndex, gaToJar, mainScan, testScan);
        }

        List<DependenciesTui.ManagedEntry> managed = buildManagedEntries(proj);

        DependencyTreeModel treeModel = cachedCollectDependencies(proj);
        TreeTui treeTui = new TreeTui(treeModel, scope, proj.gav());

        PomEditSession s = session != null ? session : new PomEditSession(proj.pomPath);
        PomEditSession mgmtSession = resolveManagementSession(proj, projects, s, sessionProvider);
        return new DependenciesTui(
                declared, transitive, managed, s, mgmtSession, proj.gav(), bytecodeAnalyzed, treeTui);
    }

    private ToolPanel createReactorDependenciesPanel(
            List<PilotProject> projects, Function<Path, PomEditSession> sessionProvider, Consumer<String> progress)
            throws Exception {
        Map<String, DependenciesTui.DepEntry> unusedMap = new LinkedHashMap<>();
        Map<String, DependenciesTui.DepEntry> usedTransMap = new LinkedHashMap<>();
        int modulesScanned = 0;
        int modulesSkipped = 0;

        for (PilotProject p : projects) {
            if (p.outputDirectory == null || !Files.isDirectory(p.outputDirectory)) {
                modulesSkipped++;
                continue;
            }
            modulesScanned++;
            progress.accept("Analyzing dependencies… " + modulesScanned + "/" + projects.size() + "\n" + p.artifactId);
            analyzeModuleUsage(p, unusedMap, usedTransMap);
        }

        PilotProject mgmtProject = findManagementPom(projects);
        PomEditSession mgmtSession = sessionProvider != null ? sessionProvider.apply(mgmtProject.pomPath) : null;
        return new DependenciesTui(
                new ArrayList<>(unusedMap.values()),
                new ArrayList<>(usedTransMap.values()),
                reactorGav(projects),
                modulesScanned,
                modulesSkipped,
                sessionProvider,
                mgmtSession);
    }

    private void analyzeModuleUsage(
            PilotProject p,
            Map<String, DependenciesTui.DepEntry> unusedMap,
            Map<String, DependenciesTui.DepEntry> usedTransMap)
            throws Exception {
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

        Map<String, File> gaToJar = resolved.gaToJar();
        ClassFileScanner.ScanResult mainScan = ClassFileScanner.scanDirectory(p.outputDirectory);
        ClassFileScanner.ScanResult testScan = p.testOutputDirectory != null && Files.isDirectory(p.testOutputDirectory)
                ? ClassFileScanner.scanDirectory(p.testOutputDirectory)
                : new ClassFileScanner.ScanResult(Set.of(), Map.of());
        Map<String, String> classIndex = DependencyUsageAnalyzer.buildClassIndex(gaToJar);
        DependencyUsageAnalyzer.AnalysisResult usage = DependencyUsageAnalyzer.analyze(
                mainScan.referencedClasses(), testScan.referencedClasses(), classIndex, gaToJar, declared, transitive);
        populateDepDetails(declared, transitive, classIndex, gaToJar, mainScan, testScan);

        accumulateEntries(
                declared,
                usage.declaredUsage(),
                DependencyUsageAnalyzer.UsageStatus.UNUSED,
                unusedMap,
                p.artifactId,
                p.pomPath);
        accumulateEntries(
                transitive,
                usage.transitiveUsage(),
                DependencyUsageAnalyzer.UsageStatus.USED,
                usedTransMap,
                p.artifactId,
                p.pomPath);
    }

    private static void accumulateEntries(
            List<DependenciesTui.DepEntry> deps,
            Map<String, DependencyUsageAnalyzer.UsageStatus> usageMap,
            DependencyUsageAnalyzer.UsageStatus targetStatus,
            Map<String, DependenciesTui.DepEntry> accumulator,
            String moduleName,
            Path pomPath) {
        for (var dep : deps) {
            var us = usageMap.getOrDefault(dep.ga(), DependencyUsageAnalyzer.UsageStatus.UNDETERMINED);
            if (us == targetStatus) {
                var existing = accumulator.get(dep.ga());
                if (existing == null) {
                    dep.usageStatus = targetStatus;
                    dep.modules.add(moduleName);
                    dep.modulePomPaths.add(pomPath);
                    accumulator.put(dep.ga(), dep);
                } else if (!existing.modules.contains(moduleName)) {
                    existing.modules.add(moduleName);
                    existing.modulePomPaths.add(pomPath);
                }
            }
        }
    }

    /**
     * Populate detail fields (totalClasses, usedMembers, spiServices) on each DepEntry
     * using bytecode scan results and the class index.
     */
    private static void populateDepDetails(
            List<DependenciesTui.DepEntry> declared,
            List<DependenciesTui.DepEntry> transitive,
            Map<String, String> classIndex,
            Map<String, File> gaToJar,
            ClassFileScanner.ScanResult mainScan,
            ClassFileScanner.ScanResult testScan) {
        Map<String, Set<String>> gaToClasses = new HashMap<>();
        for (var entry : classIndex.entrySet()) {
            gaToClasses.computeIfAbsent(entry.getValue(), k -> new HashSet<>()).add(entry.getKey());
        }
        Set<String> mainRefs = mainScan.referencedClasses();
        Set<String> allRefs = new HashSet<>(mainRefs);
        allRefs.addAll(testScan.referencedClasses());

        for (var deps : List.of(declared, transitive)) {
            for (var dep : deps) {
                Set<String> depClasses = gaToClasses.get(dep.ga());
                dep.totalClasses = depClasses != null ? depClasses.size() : 0;
                if (depClasses != null) {
                    boolean isTest = TEST_SCOPES.contains(dep.scope);
                    dep.usedMembers = findUsedMembers(
                            depClasses,
                            isTest,
                            mainRefs,
                            allRefs,
                            mainScan.memberReferences(),
                            testScan.memberReferences());
                }
                dep.spiServices = findSpiServices(gaToJar, dep.ga());
            }
        }
    }

    private static Map<String, List<String>> findUsedMembers(
            Set<String> depClasses,
            boolean isTest,
            Set<String> mainRefs,
            Set<String> allRefs,
            Map<String, Set<String>> mainMembers,
            Map<String, Set<String>> testMembers) {
        Set<String> refs = isTest ? allRefs : mainRefs;
        Map<String, List<String>> used = new LinkedHashMap<>();
        for (String cls : depClasses) {
            if (!refs.contains(cls)) continue;
            Set<String> memberSet = new HashSet<>();
            Set<String> m = mainMembers.get(cls);
            if (m != null) memberSet.addAll(m);
            if (isTest) {
                Set<String> t = testMembers.get(cls);
                if (t != null) memberSet.addAll(t);
            }
            used.put(cls, new ArrayList<>(memberSet));
        }
        return used.isEmpty() ? null : used;
    }

    private static List<String> findSpiServices(Map<String, File> gaToJar, String ga) {
        File jarFile = gaToJar.get(ga);
        if (jarFile != null) {
            Set<String> spi = DependencyUsageAnalyzer.getRuntimeDiscoveryClasses(jarFile);
            if (!spi.isEmpty()) {
                return new ArrayList<>(spi);
            }
        }
        return null;
    }

    private static String reactorGav(List<PilotProject> projects) {
        if (projects.isEmpty()) {
            throw new IllegalArgumentException("projects must not be empty");
        }
        return projects.get(0).gav() + " (reactor: " + projects.size() + " modules)";
    }

    @SuppressWarnings("unused")
    private ToolPanel createUpdatesPanel(
            PilotProject proj,
            List<PilotProject> projects,
            PomEditSession session,
            Function<Path, PomEditSession> sessionProvider,
            Consumer<String> progress) {
        UpdatesTui.VersionResolver versionResolver = resolver::resolveVersions;
        List<PilotProject> targetProjects = projects.size() > 1 ? projects : List.of(proj);
        ReactorCollector.CollectionResult result = ReactorCollector.collect(targetProjects);
        ReactorModel reactorModel = ReactorModel.build(targetProjects);
        String gav = targetProjects.get(0).gav();
        return new UpdatesTui(result, reactorModel, gav, versionResolver, sessionProvider);
    }

    private ToolPanel createConflictsPanel(
            PilotProject proj,
            List<PilotProject> projects,
            PomEditSession session,
            Function<Path, PomEditSession> sessionProvider,
            Consumer<String> progress) {
        List<PilotProject> targetProjects = projects.size() > 1 ? projects : List.of(proj);
        resolveAllDependencies(targetProjects, progress);
        String gav = projects.size() > 1 ? reactorGav(projects) : proj.gav();
        PomEditSession s = session != null ? session : new PomEditSession(proj.pomPath);
        PomEditSession mgmtSession = resolveManagementSession(proj, projects, s, sessionProvider);
        return new ConflictsTui(targetProjects, s, mgmtSession, gav, this::cachedCollectDependencies);
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
            PilotProject proj,
            List<PilotProject> projects,
            PomEditSession session,
            Function<Path, PomEditSession> sessionProvider,
            Consumer<String> progress) {
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
            String gav = reactorGav(projects);
            if (session != null) {
                PomEditSession mgmtSession = resolveManagementSession(proj, projects, session, sessionProvider);
                return new AuditTui(entries, gav, treeModel, session, mgmtSession);
            }
            return new AuditTui(entries, gav, treeModel, root.pomPath.toString());
        } else {
            DependencyTreeModel treeModel = cachedCollectDependencies(proj);
            Map<String, AuditTui.AuditEntry> entryMap = new LinkedHashMap<>();
            collectAuditNode(treeModel.root, entryMap, null, true);
            List<AuditTui.AuditEntry> entries = new ArrayList<>(entryMap.values());
            if (session != null) {
                PomEditSession mgmtSession = resolveManagementSession(proj, projects, session, sessionProvider);
                return new AuditTui(entries, proj.gav(), treeModel, session, mgmtSession);
            }
            return new AuditTui(entries, proj.gav(), treeModel, proj.pomPath.toString());
        }
    }

    private ToolPanel createPomPanel(PilotProject proj, PomEditSession session) throws IOException {
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
                } catch (Exception e) {
                    LOGGER.warning("Could not read parent POM " + modelId + ": " + e.getMessage());
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

    private PomEditSession resolveManagementSession(
            PilotProject proj,
            List<PilotProject> projects,
            PomEditSession currentSession,
            Function<Path, PomEditSession> sessionProvider) {
        if (sessionProvider == null) {
            return currentSession;
        }
        PilotProject mgmtProject = findManagementPom(projects.size() > 1 ? projects : List.of(proj));
        if (mgmtProject.pomPath.equals(proj.pomPath)) {
            return currentSession;
        }
        return sessionProvider.apply(mgmtProject.pomPath);
    }

    static PilotProject findManagementPom(List<PilotProject> projects) {
        if (projects.isEmpty()) {
            throw new IllegalArgumentException("projects must not be empty");
        }
        PilotProject child = projects.size() > 1 ? projects.get(1) : projects.get(0);
        PilotProject current = child.parent;

        while (current != null && current.pomPath != null) {
            if (isRepositoryPom(current.pomPath)) {
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

    /**
     * Check if a POM path points to a Maven local repository artifact
     * (which should never be modified).
     */
    static boolean isRepositoryPom(Path pomPath) {
        String path = pomPath.toString();
        return path.contains("/.m2/repository/") || path.contains("\\.m2\\repository\\");
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
