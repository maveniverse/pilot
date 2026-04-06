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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.apache.maven.model.InputLocation;
import org.apache.maven.model.InputLocationTracker;
import org.apache.maven.model.InputSource;

/**
 * Shared logic for walking the Maven Model and attaching InputLocation-based
 * origin info to XmlTree nodes. Used by both the CLI and plugin modules.
 */
public final class PomOriginHelper {

    private PomOriginHelper() {}

    /**
     * Walks the Maven Model and XmlTree in parallel, attaching InputLocation-based
     * OriginInfo directly to each XmlNode via the identity map.
     */
    public static void attachOrigins(
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

    /**
     * Builds a snippet of source lines around the given target line.
     */
    public static List<String> buildSnippet(String[] lines, int targetLine) {
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

    private static PomTui.OriginInfo buildOriginInfo(
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
}
