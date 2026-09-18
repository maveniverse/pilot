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
package eu.maveniverse.maven.pilot.mvn3;

import eu.maveniverse.maven.pilot.*;
import javax.inject.Inject;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.collection.CollectResult;

/**
 * Print the project dependency tree as plain text.
 *
 * <p>Runs once per module in a multi-module reactor. For an interactive TUI with
 * expand/collapse, conflict highlighting, scope filtering, and reverse-path lookup,
 * use {@code pilot:pilot} instead.</p>
 *
 * <p>Usage:</p>
 * <pre>
 * mvn pilot:tree
 * mvn pilot:tree -Dscope=test
 * </pre>
 *
 * @since 0.1.0
 */
@Mojo(name = "tree", requiresProject = true, threadSafe = true)
public class TreeMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    private RepositorySystemSession repoSession;

    @Inject
    private RepositorySystem repoSystem;

    /**
     * The dependency scope to display. One of: compile, runtime, test.
     */
    @Parameter(property = "scope", defaultValue = "compile")
    private String scope;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            executeForProject(project);
        } catch (MojoExecutionException | MojoFailureException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to display dependency tree: " + e.getMessage(), e);
        }
    }

    private void executeForProject(MavenProject proj) throws Exception {
        CollectResult result =
                repoSystem.collectDependencies(repoSession, MojoHelper.buildCollectRequest(proj, repoSession));
        String gav = proj.getGroupId() + ":" + proj.getArtifactId() + ":" + proj.getVersion();
        DependencyTreeModel treeModel = MojoHelper.fromDependencyNode(result.getRoot());
        DependencyTreeModel filtered = treeModel.filterByScope(scope);
        StringBuilder sb = new StringBuilder();
        sb.append(gav).append("\n");
        renderTextTree(filtered.root, sb, "");
        getLog().info(sb.toString());
    }

    /**
     * Renders a dependency node and its children as a plain-text tree using box-drawing characters,
     * matching the style of {@code dependency:tree}.
     */
    private void renderTextTree(DependencyTreeModel.TreeNode node, StringBuilder sb, String prefix) {
        for (int i = 0; i < node.children.size(); i++) {
            DependencyTreeModel.TreeNode child = node.children.get(i);
            boolean last = (i == node.children.size() - 1);
            String connector = last ? "\\- " : "+- ";
            sb.append(prefix).append(connector).append(formatNode(child)).append("\n");
            String childPrefix = prefix + (last ? "   " : "|  ");
            renderTextTree(child, sb, childPrefix);
        }
    }

    String formatNode(DependencyTreeModel.TreeNode node) {
        StringBuilder sb = new StringBuilder();
        sb.append(node.groupId)
                .append(":")
                .append(node.artifactId)
                .append(":")
                .append(node.extension != null && !node.extension.isEmpty() ? node.extension : "jar");
        if (node.classifier != null && !node.classifier.isEmpty()) {
            sb.append(":").append(node.classifier);
        }
        sb.append(":").append(node.version);
        if (node.scope != null && !node.scope.isEmpty() && !"compile".equals(node.scope)) {
            sb.append(":").append(node.scope);
        }
        if (node.optional) {
            sb.append(" (optional)");
        }
        if (node.isConflict()) {
            sb.append(" (conflict: requested ").append(node.requestedVersion).append(")");
        }
        return sb.toString();
    }
}
