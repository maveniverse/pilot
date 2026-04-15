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

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Abstraction over Maven resolution operations, allowing the TUI layer
 * to work with either Maven 3 (plugin) or Maven 4 (standalone) backends.
 */
public interface PilotResolver {

    /**
     * Collect the dependency tree for a project (no artifact file resolution).
     */
    DependencyTreeModel collectDependencies(PilotProject project);

    /**
     * Resolve dependencies with artifact files for bytecode analysis.
     */
    ResolvedDependencies resolveDependencies(PilotProject project);

    /**
     * Resolve available versions for an artifact, newest first.
     */
    List<String> resolveVersions(String groupId, String artifactId);

    /**
     * Resolve a single artifact to a local file path.
     */
    Path resolveArtifact(String groupId, String artifactId, String version, String type);

    /**
     * Get the serialized effective POM for a project.
     */
    String effectivePom(PilotProject project);

    /**
     * Get the serialized super POM XML content.
     */
    String superPom();

    /**
     * Result of resolving dependencies: tree model plus GA-to-JAR file mapping.
     */
    record ResolvedDependencies(DependencyTreeModel tree, Map<String, File> gaToJar) {}
}
