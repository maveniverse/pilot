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
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.graph.DependencyNode;
import org.jline.builtins.Nano;
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
        Path pom = findPomFromArgs(args);
        if (pom == null) {
            pom = mavenContext.findPom();
        }
        if (pom == null) {
            throw new IllegalStateException("No pom.xml found in current directory");
        }
        return mavenContext.buildProject(pom);
    }

    /**
     * Parses {@code -f <path>} from args. The path can point to a pom.xml file
     * or a directory containing one.
     */
    private Path findPomFromArgs(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("-f".equals(args[i]) || "--file".equals(args[i])) {
                Path p = Path.of(args[i + 1]);
                if (Files.isDirectory(p)) {
                    p = p.resolve("pom.xml");
                }
                if (!Files.exists(p)) {
                    throw new IllegalArgumentException("POM file not found: " + p);
                }
                return p;
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
                Path pomPath = project.getFile().toPath();
                String rawPom = Files.readString(pomPath);
                String[] rawLines = rawPom.split("\n");

                StringWriter sw = new StringWriter();
                new MavenXpp3Writer().write(sw, project.getModel());
                String effectivePom = sw.toString();

                Map<String, String[]> parentPomContents = readParentPomContents(project);

                XmlTreeModel effectiveTree = XmlTreeModel.parse(effectivePom);
                var originMap = new IdentityHashMap<XmlTreeModel.XmlNode, PomTui.OriginInfo>();
                PomOriginHelper.attachOrigins(
                        originMap, effectiveTree.root, project.getModel(), rawLines, parentPomContents);

                PomTui tui = new PomTui(
                        rawPom, effectiveTree, originMap, pomPath.getFileName().toString(), parentPomContents);
                tui.setBackend(sharedBackend);
                tui.setEditSupported(true);
                boolean wantsEdit = tui.run();
                if (!wantsEdit) break;

                // Launch JLine Nano editor
                var nano = new Nano(terminal, pomPath.getParent());
                nano.open(pomPath.toAbsolutePath().toString());
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

            List<AnalyzeTui.DepEntry> transitive = DependencyCollectors.collectTransitive(rootNode, declaredGAs);

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

            List<UpdatesTui.DependencyInfo> dependencies = UpdatesCollector.collect(project);

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

            List<ConflictsTui.ConflictGroup> conflicts = DependencyCollectors.collectConflicts(rootNode);

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

            List<AuditTui.AuditEntry> entries = DependencyCollectors.collectAuditEntries(rootNode);

            AuditTui tui = new AuditTui(entries, projectGav(project));
            tui.setBackend(sharedBackend);
            tui.run();
            return null;
        }
    }

    // -- Helpers ------------------------------------------------------------

    private Map<String, String[]> readParentPomContents(MavenProject project) {
        Map<String, String[]> contents = new LinkedHashMap<>();
        MavenProject current = project;
        while (current.getParent() != null) {
            current = current.getParent();
            String modelId = current.getGroupId() + ":" + current.getArtifactId() + ":" + current.getVersion();
            Path parentPath = current.getFile() != null ? current.getFile().toPath() : null;

            if (parentPath == null || !Files.exists(parentPath)) {
                try {
                    parentPath = mavenContext.resolveArtifact(
                            current.getGroupId(),
                            current.getArtifactId(),
                            current.getVersion(),
                            project.getRemoteProjectRepositories());
                } catch (Exception ignored) {
                }
            }

            if (parentPath != null && Files.exists(parentPath)) {
                try {
                    contents.put(modelId, Files.readString(parentPath).split("\n"));
                } catch (Exception ignored) {
                }
            }
        }
        return contents;
    }
}
