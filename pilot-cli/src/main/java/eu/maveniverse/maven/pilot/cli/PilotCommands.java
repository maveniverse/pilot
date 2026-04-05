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

import eu.maveniverse.maven.pilot.*;
import jakarta.json.JsonObject;
import java.io.File;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.InputLocation;
import org.apache.maven.model.InputLocationTracker;
import org.apache.maven.model.InputSource;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.graph.DependencyNode;
import org.jline.shell.Command;
import org.jline.shell.CommandSession;
import org.jline.shell.impl.AbstractCommand;
import org.jline.terminal.Terminal;

/**
 * All pilot commands, registered as JLine 4 shell commands.
 */
public class PilotCommands {

    private final Terminal terminal;
    private final MavenContext mavenContext;
    private final dev.tamboui.terminal.Backend sharedBackend;

    public PilotCommands(Terminal terminal, MavenContext mavenContext) {
        this.terminal = terminal;
        this.mavenContext = mavenContext;
        this.sharedBackend = new SharedJLineBackend(terminal);
    }

    public Collection<Command> commands() {
        return List.of(
                new SearchCommand(),
                new PomCommand(),
                new TreeCommand(),
                new AnalyzeCommand(),
                new UpdatesCommand(),
                new ConflictsCommand(),
                new AuditCommand());
    }

    private MavenProject requireProject(String[] args) throws Exception {
        File pom = findPomFromArgs(args);
        if (pom == null) {
            pom = mavenContext.findPom();
        }
        if (pom == null) {
            throw new IllegalStateException("No pom.xml found in current directory");
        }
        return mavenContext.buildProject(pom.toPath());
    }

    /**
     * Parses {@code -f <path>} from args. The path can point to a pom.xml file
     * or a directory containing one.
     */
    private File findPomFromArgs(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("-f".equals(args[i]) || "--file".equals(args[i])) {
                Path p = Path.of(args[i + 1]);
                if (Files.isDirectory(p)) {
                    p = p.resolve("pom.xml");
                }
                File f = p.toFile();
                if (!f.exists()) {
                    throw new IllegalArgumentException("POM file not found: " + f);
                }
                return f;
            }
        }
        return null;
    }

    /**
     * Returns the args with {@code -f <path>} stripped out, so commands
     * only see their own arguments.
     */
    private String[] stripFileArg(String[] args) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (("-f".equals(args[i]) || "--file".equals(args[i])) && i + 1 < args.length) {
                i++; // skip the value too
            } else {
                result.add(args[i]);
            }
        }
        return result.toArray(String[]::new);
    }

    private String projectGav(MavenProject project) {
        return project.getGroupId() + ":" + project.getArtifactId() + ":" + project.getVersion();
    }

    // -- Commands -----------------------------------------------------------

    private class SearchCommand extends AbstractCommand {
        SearchCommand() {
            super("search", "search");
        }

        @Override
        public String description() {
            return "Search Maven Central for artifacts";
        }

        @Override
        public Object execute(CommandSession session, String[] args) throws Exception {
            CentralSearchClient client = new CentralSearchClient();
            String query = String.join(" ", stripFileArg(args)).trim();
            List<String[]> initialResults = List.of();
            int totalHits = 0;

            if (!query.isEmpty()) {
                JsonObject response = client.query(query, 100, 0);
                JsonObject responseBody = response.getJsonObject("response");
                totalHits = responseBody.getInt("numFound");
                initialResults = SearchTui.extractArtifacts(responseBody);
            }

            SearchTui tui = new SearchTui(client, query, initialResults, totalHits);
            tui.setBackend(sharedBackend);
            String selected = tui.run();
            if (selected != null) {
                session.out().println("Selected: " + selected);
                if (tui.getSelectedCentralUrl() != null) {
                    session.out().println("Maven Central: " + tui.getSelectedCentralUrl());
                }
            }
            return null;
        }
    }

    private class PomCommand extends AbstractCommand {
        PomCommand() {
            super("pom", "pom");
        }

        @Override
        public String description() {
            return "Interactive POM viewer with syntax highlighting";
        }

        @Override
        public Object execute(CommandSession session, String[] args) throws Exception {
            while (true) {
                MavenProject project = requireProject(args);
                File pomFile = project.getFile();
                String rawPom = Files.readString(pomFile.toPath());
                String[] rawLines = rawPom.split("\n");

                StringWriter sw = new StringWriter();
                new MavenXpp3Writer().write(sw, project.getModel());
                String effectivePom = sw.toString();

                Map<String, String[]> parentPomContents = readParentPomContents(project);

                XmlTreeModel effectiveTree = XmlTreeModel.parse(effectivePom);
                var originMap = new IdentityHashMap<XmlTreeModel.XmlNode, PomTui.OriginInfo>();
                attachOrigins(originMap, effectiveTree.root, project.getModel(), rawLines, parentPomContents);

                PomTui tui = new PomTui(rawPom, effectiveTree, originMap, pomFile.getName(), parentPomContents);
                tui.setBackend(sharedBackend);
                tui.setEditSupported(true);
                boolean wantsEdit = tui.run();
                if (!wantsEdit) break;

                // Launch JLine Nano editor
                var nano =
                        new org.jline.builtins.Nano(terminal, pomFile.toPath().getParent());
                nano.open(pomFile.getAbsolutePath());
                nano.run();
                // Loop back to re-read the (possibly modified) POM
            }
            return null;
        }
    }

    private class TreeCommand extends AbstractCommand {
        TreeCommand() {
            super("tree", "tree");
        }

        @Override
        public String description() {
            return "Browse the project dependency tree";
        }

        @Override
        public Object execute(CommandSession session, String[] args) throws Exception {
            MavenProject project = requireProject(args);
            DependencyNode rootNode = mavenContext.collectDependencies(project);
            DependencyTreeModel model = DependencyNodeConverter.convert(rootNode);
            TreeTui tui = new TreeTui(model, projectGav(project));
            tui.setBackend(sharedBackend);
            tui.run();
            return null;
        }
    }

    private class AnalyzeCommand extends AbstractCommand {
        AnalyzeCommand() {
            super("analyze", "analyze");
        }

        @Override
        public String description() {
            return "Analyze declared vs transitive dependencies";
        }

        @Override
        public Object execute(CommandSession session, String[] args) throws Exception {
            MavenProject project = requireProject(args);

            Set<String> declaredGAs = new HashSet<>();
            List<AnalyzeTui.DepEntry> declared = new ArrayList<>();
            for (Dependency dep : project.getDependencies()) {
                String ga = dep.getGroupId() + ":" + dep.getArtifactId();
                declaredGAs.add(ga);
                declared.add(new AnalyzeTui.DepEntry(
                        dep.getGroupId(), dep.getArtifactId(), dep.getVersion(), dep.getScope(), true));
            }

            DependencyNode rootNode = mavenContext.collectDependencies(project);

            Set<String> transitiveGAs = new HashSet<>();
            List<AnalyzeTui.DepEntry> transitive = new ArrayList<>();
            collectTransitive(rootNode, declaredGAs, transitiveGAs, transitive);

            String pomPath = project.getFile().getAbsolutePath();
            AnalyzeTui tui = new AnalyzeTui(declared, transitive, pomPath, projectGav(project));
            tui.setBackend(sharedBackend);
            tui.run();
            return null;
        }
    }

    private class UpdatesCommand extends AbstractCommand {
        UpdatesCommand() {
            super("updates", "updates");
        }

        @Override
        public String description() {
            return "Check for dependency version updates";
        }

        @Override
        public Object execute(CommandSession session, String[] args) throws Exception {
            MavenProject project = requireProject(args);

            // Build a map of raw (un-interpolated) versions from the original model
            Map<String, String> rawVersions = new HashMap<>();
            if (project.getOriginalModel().getDependencies() != null) {
                for (Dependency dep : project.getOriginalModel().getDependencies()) {
                    rawVersions.put(dep.getGroupId() + ":" + dep.getArtifactId(), dep.getVersion());
                }
            }
            if (project.getOriginalModel().getDependencyManagement() != null
                    && project.getOriginalModel().getDependencyManagement().getDependencies() != null) {
                for (Dependency dep :
                        project.getOriginalModel().getDependencyManagement().getDependencies()) {
                    rawVersions.put(dep.getGroupId() + ":" + dep.getArtifactId(), dep.getVersion());
                }
            }

            List<UpdatesTui.DependencyInfo> dependencies = new ArrayList<>();

            for (Dependency dep : project.getDependencies()) {
                var info = new UpdatesTui.DependencyInfo(
                        dep.getGroupId(), dep.getArtifactId(), dep.getVersion(), dep.getScope(), dep.getType());
                setPropertyExpression(info, rawVersions);
                dependencies.add(info);
            }

            if (project.getDependencyManagement() != null) {
                for (Dependency dep : project.getDependencyManagement().getDependencies()) {
                    boolean alreadyListed = dependencies.stream()
                            .anyMatch(d ->
                                    d.groupId.equals(dep.getGroupId()) && d.artifactId.equals(dep.getArtifactId()));
                    if (!alreadyListed) {
                        var info = new UpdatesTui.DependencyInfo(
                                dep.getGroupId(), dep.getArtifactId(), dep.getVersion(), dep.getScope(), dep.getType());
                        info.managed = true;
                        setPropertyExpression(info, rawVersions);
                        dependencies.add(info);
                    }
                }
            }

            // Collect plugins
            collectPlugins(project, dependencies, rawVersions);

            String pomPath = project.getFile().getAbsolutePath();
            UpdatesTui tui = new UpdatesTui(dependencies, pomPath, projectGav(project));
            tui.setBackend(sharedBackend);
            tui.run();
            return null;
        }
    }

    private class ConflictsCommand extends AbstractCommand {
        ConflictsCommand() {
            super("conflicts", "conflicts");
        }

        @Override
        public String description() {
            return "Detect and browse dependency version conflicts";
        }

        @Override
        public Object execute(CommandSession session, String[] args) throws Exception {
            MavenProject project = requireProject(args);
            DependencyNode rootNode = mavenContext.collectDependencies(project);

            Map<String, List<ConflictsTui.ConflictEntry>> conflictMap = new HashMap<>();
            collectConflicts(rootNode, conflictMap, new ArrayList<>());

            List<ConflictsTui.ConflictGroup> conflicts = conflictMap.entrySet().stream()
                    .filter(e -> e.getValue().size() > 1
                            || e.getValue().stream()
                                    .anyMatch(c -> c.requestedVersion != null
                                            && !c.requestedVersion.equals(c.resolvedVersion)))
                    .map(e -> new ConflictsTui.ConflictGroup(e.getKey(), e.getValue()))
                    .collect(Collectors.toList());

            String pomPath = project.getFile().getAbsolutePath();
            ConflictsTui tui = new ConflictsTui(conflicts, pomPath, projectGav(project));
            tui.setBackend(sharedBackend);
            tui.run();
            return null;
        }
    }

    private class AuditCommand extends AbstractCommand {
        AuditCommand() {
            super("audit", "audit");
        }

        @Override
        public String description() {
            return "License and security audit dashboard";
        }

        @Override
        public Object execute(CommandSession session, String[] args) throws Exception {
            MavenProject project = requireProject(args);
            DependencyNode rootNode = mavenContext.collectDependencies(project);

            List<AuditTui.AuditEntry> entries = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            collectAuditEntries(rootNode, entries, seen, true);

            AuditTui tui = new AuditTui(entries, projectGav(project));
            tui.setBackend(sharedBackend);
            tui.run();
            return null;
        }
    }

    // -- Helpers ------------------------------------------------------------

    private void collectPlugins(
            MavenProject project, List<UpdatesTui.DependencyInfo> dependencies, Map<String, String> rawVersions) {
        // Collect raw plugin versions from original model
        if (project.getOriginalModel().getBuild() != null) {
            if (project.getOriginalModel().getBuild().getPlugins() != null) {
                for (Plugin p : project.getOriginalModel().getBuild().getPlugins()) {
                    if (p.getVersion() != null) {
                        rawVersions.put(p.getGroupId() + ":" + p.getArtifactId(), p.getVersion());
                    }
                }
            }
            if (project.getOriginalModel().getBuild().getPluginManagement() != null
                    && project.getOriginalModel()
                                    .getBuild()
                                    .getPluginManagement()
                                    .getPlugins()
                            != null) {
                for (Plugin p : project.getOriginalModel()
                        .getBuild()
                        .getPluginManagement()
                        .getPlugins()) {
                    if (p.getVersion() != null) {
                        rawVersions.put(p.getGroupId() + ":" + p.getArtifactId(), p.getVersion());
                    }
                }
            }
        }

        // Add build plugins with versions
        if (project.getBuild() != null && project.getBuild().getPlugins() != null) {
            for (Plugin p : project.getBuild().getPlugins()) {
                if (p.getVersion() == null || p.getVersion().isEmpty()) continue;
                var info = new UpdatesTui.DependencyInfo(
                        p.getGroupId(), p.getArtifactId(), p.getVersion(), "", "maven-plugin");
                info.plugin = true;
                setPropertyExpression(info, rawVersions);
                dependencies.add(info);
            }
        }

        // Add managed plugins
        if (project.getPluginManagement() != null
                && project.getPluginManagement().getPlugins() != null) {
            for (Plugin p : project.getPluginManagement().getPlugins()) {
                if (p.getVersion() == null || p.getVersion().isEmpty()) continue;
                boolean alreadyListed = dependencies.stream()
                        .anyMatch(d -> d.groupId.equals(p.getGroupId()) && d.artifactId.equals(p.getArtifactId()));
                if (!alreadyListed) {
                    var info = new UpdatesTui.DependencyInfo(
                            p.getGroupId(), p.getArtifactId(), p.getVersion(), "", "maven-plugin");
                    info.plugin = true;
                    info.managed = true;
                    setPropertyExpression(info, rawVersions);
                    dependencies.add(info);
                }
            }
        }
    }

    private static void setPropertyExpression(UpdatesTui.DependencyInfo info, Map<String, String> rawVersions) {
        String rawVersion = rawVersions.get(info.groupId + ":" + info.artifactId);
        if (rawVersion != null && rawVersion.startsWith("${") && rawVersion.endsWith("}")) {
            info.propertyExpression = rawVersion;
        }
    }

    private void collectTransitive(
            DependencyNode node, Set<String> declaredGAs, Set<String> seen, List<AnalyzeTui.DepEntry> result) {
        for (DependencyNode child : node.getChildren()) {
            if (child.getDependency() == null) continue;
            var art = child.getDependency().getArtifact();
            String ga = art.getGroupId() + ":" + art.getArtifactId();

            if (!declaredGAs.contains(ga) && seen.add(ga)) {
                String via = "";
                if (node.getDependency() != null) {
                    via = node.getDependency().getArtifact().getGroupId() + ":"
                            + node.getDependency().getArtifact().getArtifactId();
                }
                var entry = new AnalyzeTui.DepEntry(
                        art.getGroupId(),
                        art.getArtifactId(),
                        art.getVersion(),
                        child.getDependency().getScope(),
                        false);
                entry.pulledBy = via;
                result.add(entry);
            }
            collectTransitive(child, declaredGAs, seen, result);
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

    private void collectAuditEntries(
            DependencyNode node, List<AuditTui.AuditEntry> entries, Set<String> seen, boolean isRoot) {
        if (!isRoot && node.getDependency() != null) {
            var art = node.getDependency().getArtifact();
            String ga = art.getGroupId() + ":" + art.getArtifactId();
            if (seen.add(ga)) {
                entries.add(new AuditTui.AuditEntry(
                        art.getGroupId(), art.getArtifactId(),
                        art.getVersion(), node.getDependency().getScope()));
            }
        }
        for (DependencyNode child : node.getChildren()) {
            collectAuditEntries(child, entries, seen, false);
        }
    }

    private Map<String, String[]> readParentPomContents(MavenProject project) {
        Map<String, String[]> contents = new LinkedHashMap<>();
        MavenProject current = project;
        while (current.getParent() != null) {
            current = current.getParent();
            String modelId = current.getGroupId() + ":" + current.getArtifactId() + ":" + current.getVersion();
            File parentFile = current.getFile();

            if (parentFile == null || !parentFile.exists()) {
                try {
                    parentFile = mavenContext.resolveArtifact(
                            current.getGroupId(),
                            current.getArtifactId(),
                            current.getVersion(),
                            project.getRemoteProjectRepositories());
                } catch (Exception ignored) {
                }
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

    private void attachOrigins(
            IdentityHashMap<XmlTreeModel.XmlNode, PomTui.OriginInfo> map,
            XmlTreeModel.XmlNode xmlNode,
            InputLocationTracker tracker,
            String[] rawLines,
            Map<String, String[]> parentPomContents) {
        for (var child : xmlNode.children) {
            if (child.isComment) continue;

            InputLocation loc = tracker.getLocation(child.tagName);
            if (loc != null) {
                map.put(child, buildOriginInfo(loc, rawLines, parentPomContents));
            }

            try {
                String getterName = "get" + Character.toUpperCase(child.tagName.charAt(0)) + child.tagName.substring(1);
                Method getter = tracker.getClass().getMethod(getterName);
                Object value = getter.invoke(tracker);

                if (value instanceof InputLocationTracker subTracker) {
                    attachOrigins(map, child, subTracker, rawLines, parentPomContents);
                } else if (value instanceof List<?> list) {
                    int listIdx = 0;
                    for (var grandchild : child.children) {
                        if (listIdx < list.size() && list.get(listIdx) instanceof InputLocationTracker itemTracker) {
                            InputLocation itemLoc = itemTracker.getLocation("");
                            if (itemLoc != null) {
                                map.put(grandchild, buildOriginInfo(itemLoc, rawLines, parentPomContents));
                            }
                            attachOrigins(map, grandchild, itemTracker, rawLines, parentPomContents);
                        }
                        listIdx++;
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
}
