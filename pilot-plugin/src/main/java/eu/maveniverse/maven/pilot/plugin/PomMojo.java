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
package eu.maveniverse.maven.pilot.plugin;

import eu.maveniverse.maven.pilot.PomOriginHelper;
import eu.maveniverse.maven.pilot.PomTui;
import eu.maveniverse.maven.pilot.XmlTreeModel;
import java.io.File;
import java.io.StringWriter;
import java.nio.file.Files;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.inject.Inject;
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
@Mojo(name = "pom", requiresProject = true, threadSafe = true)
public class PomMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    private RepositorySystemSession repoSession;

    @Inject
    private RepositorySystem repoSystem;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            File pomFile = project.getFile();
            String rawPom = Files.readString(pomFile.toPath());
            String[] rawLines = rawPom.split("\n");

            StringWriter sw = new StringWriter();
            new MavenXpp3Writer().write(sw, project.getModel());
            String effectivePom = sw.toString();

            Map<String, String[]> parentPomContents = readParentPomContents();

            XmlTreeModel effectiveTree = XmlTreeModel.parse(effectivePom);
            var originMap = new IdentityHashMap<XmlTreeModel.XmlNode, PomTui.OriginInfo>();
            PomOriginHelper.attachOrigins(
                    originMap, effectiveTree.root, project.getModel(), rawLines, parentPomContents);

            PomTui tui = new PomTui(rawPom, effectiveTree, originMap, pomFile.getName(), parentPomContents);
            tui.run();
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to display POM: " + e.getMessage(), e);
        }
    }

    private Map<String, String[]> readParentPomContents() {
        Map<String, String[]> contents = new LinkedHashMap<>();
        MavenProject current = project;
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
}
