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

import java.util.List;

/**
 * Test helper for creating CollectionResult instances with update data.
 * Needed because ReactorCollector inner classes are package-private.
 */
public final class UpdatesMojoTestHelper {

    private UpdatesMojoTestHelper() {}

    public static ReactorCollector.CollectionResult resultWithNoUpdates() {
        var dep = new ReactorCollector.AggregatedDependency("org.example", "lib");
        dep.primaryVersion = "1.0.0";
        return new ReactorCollector.CollectionResult(List.of(dep), List.of(), List.of(dep));
    }

    public static ReactorCollector.CollectionResult resultWithUpdate(
            String groupId, String artifactId, String currentVersion, String newestVersion, float libYears) {
        var dep = new ReactorCollector.AggregatedDependency(groupId, artifactId);
        dep.primaryVersion = currentVersion;
        dep.newestVersion = newestVersion;
        dep.updateType = VersionComparator.classify(currentVersion, newestVersion);
        dep.libYears = libYears;
        return new ReactorCollector.CollectionResult(List.of(dep), List.of(), List.of(dep));
    }
}
