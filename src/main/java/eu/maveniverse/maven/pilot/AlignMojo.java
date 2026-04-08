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
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/**
 * Interactive TUI for detecting and aligning dependency conventions across the POM.
 *
 * <p>Detects the project's current version style (inline vs managed), version source
 * (literal vs property), and property naming convention, then lets the user choose
 * a target convention and preview/apply the changes.</p>
 *
 * <p>Usage:</p>
 * <pre>
 * mvn pilot:align
 * </pre>
 *
 * @since 0.2.0
 */
@Mojo(name = "align", requiresProject = true, threadSafe = true)
public class AlignMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            String pomPath = project.getFile().getAbsolutePath();
            String pomContent = Files.readString(Path.of(pomPath));
            PomEditor editor = new PomEditor(Document.of(pomContent));
            var detectedOptions = editor.dependencies().detectConventions();

            String gav = project.getGroupId() + ":" + project.getArtifactId() + ":" + project.getVersion();

            AlignTui tui = new AlignTui(pomPath, gav, detectedOptions);
            tui.run();
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to run alignment TUI: " + e.getMessage(), e);
        }
    }
}
