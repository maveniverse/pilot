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

import eu.maveniverse.maven.pilot.CentralSearchClient;
import eu.maveniverse.maven.pilot.PilotEngine;
import eu.maveniverse.maven.pilot.PilotProject;
import eu.maveniverse.maven.pilot.PilotResolver;
import eu.maveniverse.maven.pilot.PilotShell;
import eu.maveniverse.maven.pilot.PomTui;
import eu.maveniverse.maven.pilot.ReactorModel;
import eu.maveniverse.maven.pilot.SearchTui;
import eu.maveniverse.maven.pilot.XmlTreeModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.maven.api.DownloadedArtifact;
import org.apache.maven.api.Session;
import org.apache.maven.api.model.Model;
import org.apache.maven.api.model.Parent;
import org.apache.maven.api.services.ArtifactResolver;
import org.apache.maven.api.services.ArtifactResolverRequest;
import org.apache.maven.api.services.ModelBuilder;
import org.apache.maven.api.services.ModelBuilderRequest;
import org.apache.maven.api.services.ModelBuilderResult;
import org.apache.maven.api.services.Sources;
import org.apache.maven.api.services.model.RootLocator;
import org.apache.maven.impl.model.rootlocator.DefaultRootLocator;
import org.apache.maven.impl.standalone.ApiRunner;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Standalone CLI entry point for Pilot using Maven 4 API.
 *
 * <p>Usage: {@code java -jar pilot-cli.jar [pom.xml]}</p>
 *
 * <p>The TUI starts immediately with a module tree discovered from raw XML,
 * while Maven model resolution happens asynchronously in the background.
 * Tool panels show "Loading…" until the full Maven session is ready.</p>
 */
public class PilotMain {

    private static final Logger LOGGER = Logger.getLogger(PilotMain.class.getName());
    private static final String POM_XML = "pom.xml";

    record LoadedReactor(Map<Path, PilotProject> projectsByPomPath, PilotEngine engine) {}

    public static void main(String[] args) throws Exception {
        // Suppress noisy Maven resolver warnings (e.g., strict POM validation of third-party deps)
        System.setProperty("maven.logger.defaultLogLevel", "ERROR");

        Path pomPath = args.length > 0 ? Path.of(args[0]) : Path.of(POM_XML);
        pomPath = pomPath.toAbsolutePath().normalize();

        if (!Files.isRegularFile(pomPath)) {
            System.err.println("POM file not found: " + pomPath);
            System.exit(1);
        }

        // Find project root by walking up (like Maven bootstrap)
        Path rootPom = findRootPom(pomPath);

        // Phase 1: Quick reactor discovery from root POM (instant, no Maven API needed)
        List<PilotProject> stubProjects = discoverReactorFromXml(rootPom);

        // Phase 2: Start heavy Maven loading in background (from root for full context)
        final Path pom = rootPom;
        AtomicReference<String> loadingStatus = new AtomicReference<>("Initializing Maven session…");
        CompletableFuture<LoadedReactor> loadingFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return loadReactor(pom, loadingStatus);
            } catch (Throwable e) {
                throw new RuntimeException("Failed to load reactor: " + e, e);
            }
        });

        // Phase 3: Create lazy panel factory that blocks on first use
        // (called from PilotShell's panel executor thread, not the UI thread)
        PilotShell.ToolPanelFactory panelFactory = (toolId, proj, scope, session, sessionProvider, progress) -> {
            if ("search".equals(toolId)) {
                return new SearchTui(new CentralSearchClient(), "", java.util.List.of(), 0);
            }
            if ("pom".equals(toolId) && !loadingFuture.isDone()) {
                String rawPom = Files.readString(proj.pomPath);
                XmlTreeModel tree = XmlTreeModel.parse(rawPom);
                return new PomTui(rawPom, tree, null, proj.pomPath.getFileName().toString(), Map.of());
            }
            LoadedReactor reactor = loadingFuture.join();
            loadingStatus.set("Creating " + toolId + " panel…");
            PilotProject realProject = reactor.projectsByPomPath.getOrDefault(
                    proj.pomPath.toAbsolutePath().normalize(), proj);
            List<PilotProject> realScope = scope.stream()
                    .map(s -> reactor.projectsByPomPath.getOrDefault(
                            s.pomPath.toAbsolutePath().normalize(), s))
                    .toList();
            long unmapped = scope.stream()
                    .filter(s -> !reactor.projectsByPomPath.containsKey(
                            s.pomPath.toAbsolutePath().normalize()))
                    .count();
            if (unmapped > 0) {
                System.err.println("[Pilot] Warning: " + unmapped + "/" + scope.size()
                        + " scope projects not found in reactor (path mismatch)");
            }
            try {
                var panel =
                        reactor.engine.createPanel(toolId, realProject, realScope, session, sessionProvider, progress);
                loadingStatus.set(null);
                return panel;
            } catch (Exception e) {
                loadingStatus.set(null);
                System.err.println(
                        "[Pilot] Failed to create '" + toolId + "' panel for " + realProject.ga() + ": " + e);
                e.printStackTrace(System.err);
                throw e;
            }
        };

        // Phase 4: Launch TUI immediately with module tree visible
        ReactorModel reactorModel = stubProjects.size() > 1 ? ReactorModel.build(stubProjects) : null;
        new PilotShell(reactorModel, stubProjects, panelFactory, loadingStatus::get).run();
    }

    // ── Root POM discovery ──────────────────────────────────────────────

    /**
     * Find the project root using Maven 4's {@link RootLocator} API,
     * which checks for {@code .mvn} directory and {@code root="true"} POM attribute.
     * Falls back to the given POM if no root is found.
     */
    private static Path findRootPom(Path pomPath) {
        RootLocator locator = new DefaultRootLocator();
        Path rootDir = locator.findRoot(pomPath.getParent());
        if (rootDir != null) {
            Path rootPom = rootDir.resolve(POM_XML);
            if (Files.isRegularFile(rootPom)) {
                return rootPom;
            }
        }
        return pomPath;
    }

    // ── Quick reactor discovery ─────────────────────────────────────────

    private static List<PilotProject> discoverReactorFromXml(Path pomPath) throws IOException {
        List<PilotProject> projects = new ArrayList<>();
        Map<PilotProject, String> declaredParentGa = new LinkedHashMap<>();
        Path rootDir = pomPath.getParent().toRealPath();
        discoverModulesRecursive(pomPath.toRealPath(), null, null, projects, declaredParentGa, rootDir);
        // Wire parent references by matching declared <parent> GA
        Map<String, PilotProject> projectsByGa = new LinkedHashMap<>();
        for (PilotProject p : projects) {
            projectsByGa.put(p.ga(), p);
        }
        for (PilotProject child : projects) {
            String parentGa = declaredParentGa.get(child);
            if (parentGa != null) {
                child.parent = projectsByGa.get(parentGa);
            }
        }
        return projects;
    }

    private static void discoverModulesRecursive(
            Path pomPath,
            String parentGroupId,
            String parentVersion,
            List<PilotProject> projects,
            Map<PilotProject, String> declaredParentGa,
            Path rootDir) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = factory.newDocumentBuilder().parse(pomPath.toFile());
            Element root = doc.getDocumentElement();

            // Extract parent-inherited values
            String inheritedGroupId = parentGroupId;
            String inheritedVersion = parentVersion;
            String declaredParentGroupId = null;
            String declaredParentArtifactId = null;
            Element parentEl = getDirectChildElement(root, "parent");
            if (parentEl != null) {
                declaredParentGroupId = getDirectChildText(parentEl, "groupId");
                declaredParentArtifactId = getDirectChildText(parentEl, "artifactId");
                if (declaredParentGroupId != null) inheritedGroupId = declaredParentGroupId;
                String pv = getDirectChildText(parentEl, "version");
                if (pv != null) inheritedVersion = pv;
            }

            String groupId = getDirectChildText(root, "groupId");
            if (groupId == null) groupId = inheritedGroupId;
            String artifactId = getDirectChildText(root, "artifactId");
            String version = getDirectChildText(root, "version");
            if (version == null) version = inheritedVersion;
            String packaging = getDirectChildText(root, "packaging");
            if (packaging == null) packaging = "jar";

            Path basedir = pomPath.getParent();
            PilotProject project = new PilotProject(
                    groupId != null ? groupId : "?",
                    artifactId != null ? artifactId : "?",
                    version != null ? version : "?",
                    packaging,
                    basedir,
                    pomPath,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    new Properties(),
                    null,
                    null);
            projects.add(project);

            if (declaredParentGroupId != null && declaredParentArtifactId != null) {
                declaredParentGa.put(project, declaredParentGroupId + ":" + declaredParentArtifactId);
            }

            recurseIntoModules(root, basedir, groupId, version, projects, declaredParentGa, rootDir);
        } catch (Exception e) {
            LOGGER.warning("Skipping unparseable module: " + pomPath + " (" + e.getMessage() + ")");
        }
    }

    private static void recurseIntoModules(
            Element root,
            Path basedir,
            String groupId,
            String version,
            List<PilotProject> projects,
            Map<PilotProject, String> declaredParentGa,
            Path rootDir)
            throws IOException {
        Element modulesEl = getDirectChildElement(root, "modules");
        if (modulesEl == null) return;
        NodeList moduleNodes = modulesEl.getElementsByTagName("module");
        for (int i = 0; i < moduleNodes.getLength(); i++) {
            String moduleName = moduleNodes.item(i).getTextContent().trim();
            Path modulePom = basedir.resolve(moduleName)
                    .resolve(POM_XML)
                    .toAbsolutePath()
                    .normalize();
            if (Files.isRegularFile(modulePom)) {
                Path realModulePom = modulePom.toRealPath();
                if (realModulePom.startsWith(rootDir)) {
                    discoverModulesRecursive(realModulePom, groupId, version, projects, declaredParentGa, rootDir);
                }
            }
        }
    }

    private static Element getDirectChildElement(Element parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element el && tagName.equals(el.getTagName())) {
                return el;
            }
        }
        return null;
    }

    private static String getDirectChildText(Element parent, String tagName) {
        Element el = getDirectChildElement(parent, tagName);
        return el != null ? el.getTextContent().trim() : null;
    }

    // ── Full Maven loading ──────────────────────────────────────────────

    static LoadedReactor loadReactor(Path pomPath, AtomicReference<String> status) throws Exception {
        status.set("Initializing Maven session…");
        Path localRepo = Path.of(System.getProperty("user.home"), ".m2", "repository");
        Session session = ApiRunner.createSession(
                injector -> {
                    injector.bindImplicit(SecDispatcherBindings.class);
                    injector.bindImplicit(org.apache.maven.api.spi.ModelParser.class);
                    injector.bindImplicit(org.apache.maven.api.spi.ModelTransformer.class);
                    injector.bindImplicit(FallbackTypeProvider.class);
                    injector.bindImplicit(TransporterBindings.class);
                },
                localRepo);

        Path projectDir = pomPath.getParent();
        Map<String, String> extensionProperties =
                ExtensionLoader.loadExtensionProperties(session, projectDir, status::set);

        // Discover all reactor modules via BUILD_PROJECT (only this mode recurses)
        status.set("Discovering reactor modules…");
        ModelBuilder modelBuilder = session.getService(ModelBuilder.class);
        ModelBuilder.ModelBuilderSession discoverySession = modelBuilder.newSession();
        ModelBuilderRequest.ModelBuilderRequestBuilder discoveryBuilder = ModelBuilderRequest.builder()
                .session(session)
                .requestType(ModelBuilderRequest.RequestType.BUILD_PROJECT)
                .recursive(true)
                .source(Sources.buildSource(pomPath));
        if (!extensionProperties.isEmpty()) {
            discoveryBuilder.userProperties(extensionProperties);
        }
        ModelBuilderResult discoveryResult = discoverySession.build(discoveryBuilder.build());

        List<Path> pomPaths = new ArrayList<>();
        collectPomPaths(discoveryResult, pomPaths);

        // Build effective models for each module (reuse one session for shared cache)
        status.set("Building effective models (0/" + pomPaths.size() + ")…");
        ModelBuilder.ModelBuilderSession effectiveSession = modelBuilder.newSession();
        List<PilotProject> projects = new ArrayList<>();
        Map<String, Model> effectiveModels = new LinkedHashMap<>();
        Map<String, PilotProject> projectsByGa = new LinkedHashMap<>();
        for (Path modulePom : pomPaths) {
            ModelBuilderRequest.ModelBuilderRequestBuilder effectiveBuilder = ModelBuilderRequest.builder()
                    .session(session)
                    .requestType(ModelBuilderRequest.RequestType.BUILD_EFFECTIVE)
                    .source(Sources.buildSource(modulePom));
            if (!extensionProperties.isEmpty()) {
                effectiveBuilder.userProperties(extensionProperties);
            }
            ModelBuilderResult effectiveResult = effectiveSession.build(effectiveBuilder.build());
            Model effectiveModel = effectiveResult.getEffectiveModel();
            Model fileModel = effectiveResult.getFileModel();

            PilotProject project = toPilotProject(effectiveModel, fileModel, modulePom);
            if (effectiveModel.getParent() != null) {
                String parentGa = effectiveModel.getParent().getGroupId() + ":"
                        + effectiveModel.getParent().getArtifactId();
                PilotProject parentProject = projectsByGa.get(parentGa);
                if (parentProject != null) {
                    project.parent = parentProject;
                }
            }
            projects.add(project);
            projectsByGa.put(project.ga(), project);
            effectiveModels.put(project.ga(), effectiveModel);
            status.set("Building effective models (" + projects.size() + "/" + pomPaths.size() + ")…");
        }

        // Extend parent chains beyond reactor for external parents
        status.set("Resolving parent chains…");
        extendExternalParents(session, effectiveSession, projects, projectsByGa, extensionProperties);

        status.set("Ready");
        PilotResolver resolver = new Maven4PilotResolver(session, effectiveModels);
        PilotEngine engine = new PilotEngine(resolver, projects, "compile");

        Map<Path, PilotProject> projectsByPomPath = new LinkedHashMap<>();
        for (PilotProject p : projects) {
            projectsByPomPath.put(p.pomPath.toAbsolutePath().normalize(), p);
        }
        return new LoadedReactor(projectsByPomPath, engine);
    }

    // ── External parent chain extension ────────────────────────────────

    private static void extendExternalParents(
            Session session,
            ModelBuilder.ModelBuilderSession mbs,
            List<PilotProject> projects,
            Map<String, PilotProject> projectsByGa,
            Map<String, String> extensionProperties) {
        for (PilotProject project : new ArrayList<>(projects)) {
            // Walk to the top of the chain
            PilotProject top = project;
            while (top.parent != null) {
                top = top.parent;
            }
            if (projectsByGa.containsKey(top.ga())) {
                // Already processed or is a reactor project — check if it needs extension
                resolveExternalParentChain(session, mbs, top, projectsByGa, extensionProperties);
            }
        }
    }

    private static void resolveExternalParentChain(
            Session session,
            ModelBuilder.ModelBuilderSession mbs,
            PilotProject project,
            Map<String, PilotProject> projectsByGa,
            Map<String, String> extensionProperties) {
        if (project.parent != null) return; // already wired

        // Resolve this project's parent POM to find properties defined in external parents
        try {
            // Build the file model for this project to get its declared parent
            ModelBuilderRequest.ModelBuilderRequestBuilder builder = ModelBuilderRequest.builder()
                    .session(session)
                    .requestType(ModelBuilderRequest.RequestType.BUILD_EFFECTIVE)
                    .source(Sources.buildSource(project.pomPath));
            if (!extensionProperties.isEmpty()) {
                builder.userProperties(extensionProperties);
            }
            ModelBuilderResult result = mbs.build(builder.build());
            Parent parentRef = result.getFileModel().getParent();
            if (parentRef == null) return;

            String parentGa = parentRef.getGroupId() + ":" + parentRef.getArtifactId();
            if (projectsByGa.containsKey(parentGa)) {
                project.parent = projectsByGa.get(parentGa);
                return;
            }

            // Resolve the parent POM from the repository
            Path parentPom = resolveArtifactPom(
                    session, parentRef.getGroupId(), parentRef.getArtifactId(), parentRef.getVersion());
            if (parentPom == null) return;

            // Build the parent's model
            ModelBuilderRequest.ModelBuilderRequestBuilder parentBuilder = ModelBuilderRequest.builder()
                    .session(session)
                    .requestType(ModelBuilderRequest.RequestType.BUILD_EFFECTIVE)
                    .source(Sources.buildSource(parentPom));
            if (!extensionProperties.isEmpty()) {
                parentBuilder.userProperties(extensionProperties);
            }
            ModelBuilderResult parentResult = mbs.build(parentBuilder.build());

            PilotProject parentProject =
                    toPilotProject(parentResult.getEffectiveModel(), parentResult.getFileModel(), parentPom);
            projectsByGa.put(parentGa, parentProject);
            project.parent = parentProject;

            // Recursively extend the parent's parent chain
            resolveExternalParentChain(session, mbs, parentProject, projectsByGa, extensionProperties);
        } catch (Exception e) {
            LOGGER.warning("Could not resolve external parent for " + project.ga() + ": " + e.getMessage());
        }
    }

    private static Path resolveArtifactPom(Session session, String groupId, String artifactId, String version) {
        try {
            var result = session.getService(ArtifactResolver.class)
                    .resolve(ArtifactResolverRequest.build(
                            session, List.of(session.createArtifactCoordinates(groupId, artifactId, version, "pom"))));
            DownloadedArtifact artifact = result.getArtifacts().iterator().next();
            return artifact.getPath();
        } catch (Exception e) {
            return null;
        }
    }

    // ── Reactor collection ───────────────────────────────────────────────

    private static void collectPomPaths(ModelBuilderResult result, List<Path> pomPaths) {
        Path path = result.getSource().getPath();
        if (path != null) {
            pomPaths.add(path);
        }
        for (ModelBuilderResult child : result.getChildren()) {
            collectPomPaths(child, pomPaths);
        }
    }

    // ── Model conversion ────────────────────────────────────────────────

    static PilotProject toPilotProject(Model effectiveModel, Model fileModel, Path pomPath) {
        List<PilotProject.Dep> deps = effectiveModel.getDependencies() != null
                ? effectiveModel.getDependencies().stream()
                        .map(PilotMain::modelDepToPilotDep)
                        .toList()
                : List.of();

        List<PilotProject.Dep> mgmtDeps = List.of();
        if (effectiveModel.getDependencyManagement() != null
                && effectiveModel.getDependencyManagement().getDependencies() != null) {
            mgmtDeps = effectiveModel.getDependencyManagement().getDependencies().stream()
                    .map(PilotMain::modelDepToPilotDep)
                    .toList();
        }

        List<PilotProject.Dep> origDeps = fileModel.getDependencies() != null
                ? fileModel.getDependencies().stream()
                        .map(PilotMain::modelDepToPilotDep)
                        .toList()
                : List.of();

        List<PilotProject.Dep> origMgmtDeps = List.of();
        if (fileModel.getDependencyManagement() != null
                && fileModel.getDependencyManagement().getDependencies() != null) {
            origMgmtDeps = fileModel.getDependencyManagement().getDependencies().stream()
                    .map(PilotMain::modelDepToPilotDep)
                    .toList();
        }

        Properties origProps = new Properties();
        if (fileModel.getProperties() != null) {
            origProps.putAll(fileModel.getProperties());
        }

        Path basedir = pomPath.getParent();

        Path outputDir =
                effectiveModel.getBuild() != null && effectiveModel.getBuild().getOutputDirectory() != null
                        ? Path.of(effectiveModel.getBuild().getOutputDirectory())
                        : null;
        Path testOutputDir =
                effectiveModel.getBuild() != null && effectiveModel.getBuild().getTestOutputDirectory() != null
                        ? Path.of(effectiveModel.getBuild().getTestOutputDirectory())
                        : null;

        String packaging = effectiveModel.getPackaging() != null ? effectiveModel.getPackaging() : "jar";

        PilotProject pp = new PilotProject(
                effectiveModel.getGroupId(),
                effectiveModel.getArtifactId(),
                effectiveModel.getVersion(),
                packaging,
                basedir,
                pomPath,
                deps,
                mgmtDeps,
                origDeps,
                origMgmtDeps,
                origProps,
                outputDir,
                testOutputDir);
        pp.plugins = extractPlugins(effectiveModel);
        pp.managedPlugins = extractManagedPlugins(effectiveModel);
        return pp;
    }

    private static List<PilotProject.Plugin> extractPlugins(Model model) {
        if (model.getBuild() == null || model.getBuild().getPlugins() == null) return List.of();
        return model.getBuild().getPlugins().stream()
                .map(PilotMain::modelPluginToPilotPlugin)
                .toList();
    }

    private static List<PilotProject.Plugin> extractManagedPlugins(Model model) {
        if (model.getBuild() == null
                || model.getBuild().getPluginManagement() == null
                || model.getBuild().getPluginManagement().getPlugins() == null) return List.of();
        return model.getBuild().getPluginManagement().getPlugins().stream()
                .map(PilotMain::modelPluginToPilotPlugin)
                .toList();
    }

    private static PilotProject.Plugin modelPluginToPilotPlugin(org.apache.maven.api.model.Plugin plugin) {
        List<PilotProject.Dep> deps = plugin.getDependencies() != null
                ? plugin.getDependencies().stream()
                        .map(PilotMain::modelDepToPilotDep)
                        .toList()
                : List.of();
        return new PilotProject.Plugin(
                plugin.getGroupId(), plugin.getArtifactId(), plugin.getVersion(), deps, List.of());
    }

    private static PilotProject.Dep modelDepToPilotDep(org.apache.maven.api.model.Dependency dep) {
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
                dep.getType() != null && !dep.getType().isEmpty() ? dep.getType() : "jar",
                dep.getClassifier(),
                "true".equals(dep.getOptional()),
                exclusions);
    }
}
