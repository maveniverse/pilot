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

import java.util.ArrayList;
import java.util.List;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.project.MavenProject;

/**
 * Shared utility for collecting dependencies for the updates tool.
 * Uses the original model for dependency management to exclude BOM-imported dependencies.
 */
final class UpdatesHelper {

    private UpdatesHelper() {}

    /**
     * Collect dependencies from a single project for the updates TUI.
     *
     * <p>Direct dependencies come from the effective model. Managed dependencies come from
     * the <em>original</em> model's {@code <dependencyManagement>} section so that
     * BOM-imported entries (which only appear in the effective model) are excluded.
     * BOM import entries (type=pom, scope=import) are also filtered out.
     * Managed deps already present in the direct list are skipped (deduplication).</p>
     *
     * @param proj the Maven project
     * @return collected dependency info list
     */
    static List<UpdatesTui.DependencyInfo> collectDependencies(MavenProject proj) {
        List<UpdatesTui.DependencyInfo> dependencies = new ArrayList<>();

        for (Dependency dep : proj.getDependencies()) {
            dependencies.add(new UpdatesTui.DependencyInfo(
                    dep.getGroupId(), dep.getArtifactId(), dep.getVersion(), dep.getScope(), dep.getType()));
        }

        DependencyManagement mgmt = proj.getOriginalModel().getDependencyManagement();
        if (mgmt != null && mgmt.getDependencies() != null) {
            for (Dependency dep : mgmt.getDependencies()) {
                if ("pom".equals(dep.getType()) && "import".equals(dep.getScope())) {
                    continue;
                }
                boolean alreadyListed = dependencies.stream()
                        .anyMatch(d -> d.groupId.equals(dep.getGroupId()) && d.artifactId.equals(dep.getArtifactId()));
                if (!alreadyListed) {
                    var info = new UpdatesTui.DependencyInfo(
                            dep.getGroupId(), dep.getArtifactId(), dep.getVersion(), dep.getScope(), dep.getType());
                    info.managed = true;
                    dependencies.add(info);
                }
            }
        }

        return dependencies;
    }
}
