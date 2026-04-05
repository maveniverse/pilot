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

/**
 * Classifies version updates as patch, minor, or major.
 */
public class VersionComparator {

    enum UpdateType {
        PATCH,
        MINOR,
        MAJOR
    }

    static UpdateType classify(String current, String newer) {
        int[] currentParts = parseSemver(current);
        int[] newerParts = parseSemver(newer);

        if (currentParts[0] != newerParts[0]) {
            return UpdateType.MAJOR;
        }
        if (currentParts[1] != newerParts[1]) {
            return UpdateType.MINOR;
        }
        return UpdateType.PATCH;
    }

    static boolean isPreview(String version) {
        String lower = version.toLowerCase();
        return lower.contains("alpha")
                || lower.contains("beta")
                || lower.contains("-rc")
                || lower.contains(".rc")
                || lower.contains("-m")
                || lower.contains("-cr")
                || lower.contains("snapshot")
                || lower.contains("-ea")
                || lower.contains("-preview")
                || lower.contains("-dev");
    }

    static boolean isNewer(String current, String candidate) {
        return compareVersions(current, candidate) < 0;
    }

    private static int compareVersions(String v1, String v2) {
        int[] p1 = parseSemver(v1);
        int[] p2 = parseSemver(v2);

        for (int i = 0; i < Math.min(p1.length, p2.length); i++) {
            int cmp = Integer.compare(p1[i], p2[i]);
            if (cmp != 0) return cmp;
        }
        return Integer.compare(p1.length, p2.length);
    }

    private static int[] parseSemver(String version) {
        // Strip qualifiers like -SNAPSHOT, -beta1, etc.
        String base = version.replaceAll("-.*", "").replaceAll("[^0-9.]", "");
        String[] parts = base.split("\\.");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                result[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                result[i] = 0;
            }
        }
        return result;
    }
}
