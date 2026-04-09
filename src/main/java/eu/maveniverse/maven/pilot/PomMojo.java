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

import eu.maveniverse.domtrip.Element;
import eu.maveniverse.domtrip.Node;
import java.io.File;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import org.apache.maven.execution.MavenSession;
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
import org.eclipse.aether.resolution.ArtifactRequest;

/**
 * Interactive POM viewer with syntax highlighting and effective POM comparison.
 *
 * <p>Usage:</p>
 * <pre>
 * mvn pilot:pom
 * </pre>
 *
 * @since 0.1.0
 */
@Mojo(name = "pom", requiresProject = true, aggregator = true, threadSafe = true)
public class PomMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    private RepositorySystemSession repoSession;

    @Inject
    private RepositorySystem repoSystem;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            List<MavenProject> projects = session.getProjects();
            if (projects.size() > 1) {
                executeReactor(projects);
            } else {
                executeForProject(project);
            }
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to display POM: " + e.getMessage(), e);
        }
    }

    private void executeReactor(List<MavenProject> projects) throws Exception {
        ReactorModel reactorModel = ReactorModel.build(projects);
        MavenProject root = projects.get(0);
        String reactorGav = root.getGroupId() + ":" + root.getArtifactId() + ":" + root.getVersion();

        ModulePickerTui picker = new ModulePickerTui(reactorModel, reactorGav, "pom");
        while (true) {
            MavenProject selected = picker.pick();
            if (selected == null) break;
            executeForProject(selected);
        }
    }

    private void executeForProject(MavenProject proj) throws Exception {
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

        PomTui tui = new PomTui(rawPom, effectiveTree, originMap, pomFile.getName(), parentPomContents);
        tui.run();
    }

    private Map<String, String[]> readParentPomContents(MavenProject proj) {
        Map<String, String[]> contents = new LinkedHashMap<>();
        MavenProject current = proj;
        while (current.getParent() != null) {
            current = current.getParent();
            String modelId = current.getGroupId() + ":" + current.getArtifactId() + ":" + current.getVersion();
            File parentFile = current.getFile();

            // If getFile() is null (repo-resolved parent), resolve the POM artifact
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

    /**
     * Attach origin metadata to XML elements by traversing the XML tree in parallel with a Maven model
     * InputLocationTracker and recording InputLocation-derived OriginInfo into the provided identity map.
     *
     * Populates `map` with entries that associate each visited `Element` node to a `PomTui.OriginInfo`
     * derived from the tracker's `InputLocation` (or from item trackers for list entries).
     *
     * @param map               identity map to receive Element -> OriginInfo associations
     * @param xmlElement        root XML element whose subtree will be traversed
     * @param tracker           Maven model InputLocationTracker that provides source locations for fields
     * @param rawLines          the raw lines of the current POM file (used when an origin refers to this POM)
     * @param parentPomContents mapping from parent modelId ("groupId:artifactId:version") to that parent's raw lines
     */
    private void attachOrigins(
            IdentityHashMap<Node, PomTui.OriginInfo> map,
            Element xmlElement,
            InputLocationTracker tracker,
            String[] rawLines,
            Map<String, String[]> parentPomContents) {
        for (Node child : XmlTreeModel.treeChildren(xmlElement)) {
            if (!(child instanceof Element childElement)) continue;

            // Attach the InputLocation for this field
            InputLocation loc = tracker.getLocation(childElement.name());
            if (loc != null) {
                map.put(childElement, buildOriginInfo(loc, rawLines, parentPomContents));
            }

            // Recurse into sub-objects
            try {
                String name = childElement.name();
                String getterName = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
                Method getter = tracker.getClass().getMethod(getterName);
                Object value = getter.invoke(tracker);

                if (value instanceof InputLocationTracker subTracker) {
                    attachOrigins(map, childElement, subTracker, rawLines, parentPomContents);
                } else if (value instanceof List<?> list) {
                    // Match list items to XML children by position
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
                // No getter for this XML element name — skip
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
