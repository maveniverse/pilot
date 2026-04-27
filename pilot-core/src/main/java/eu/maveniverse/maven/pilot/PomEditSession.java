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
import eu.maveniverse.domtrip.maven.Coordinates;
import eu.maveniverse.domtrip.maven.PomEditor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * Shared POM editing session for a single module.
 *
 * <p>All editing tools (DependenciesTui, UpdatesTui, ConflictsTui, AuditTui) that operate
 * on the same POM file share a single {@code PomEditSession}. This provides:</p>
 * <ul>
 *   <li>A single shared {@link PomEditor} — changes in one tool are visible in others</li>
 *   <li>An ordered change log for visual markers and status display</li>
 *   <li>Undo via XML snapshot stack</li>
 *   <li>Revert-all to restore the original POM content</li>
 *   <li>Save with external-modification safety check</li>
 * </ul>
 */
public class PomEditSession {

    enum ChangeType {
        ADD,
        REMOVE,
        MODIFY
    }

    record Change(ChangeType type, String target, String ga, String description, String tool) {}

    private final Path pomPath;
    private String originalContent;
    private PomEditor editor;
    private final List<Change> changes = new ArrayList<>();
    private final Deque<String> undoStack = new ArrayDeque<>();
    private int mutationCount;

    PomEditSession(Path pomPath) {
        this.pomPath = pomPath;
        try {
            this.originalContent = Files.readString(pomPath);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read POM: " + pomPath, e);
        }
        this.editor = new PomEditor(Document.of(originalContent));
    }

    // -- Accessors --

    PomEditor editor() {
        return editor;
    }

    Path pomPath() {
        return pomPath;
    }

    String originalContent() {
        return originalContent;
    }

    Document document() {
        return editor.document();
    }

    // -- Change tracking --

    /**
     * Snapshot the current editor state before a mutation.
     * Must be called before each POM-modifying operation to enable undo.
     */
    void beforeMutation() {
        undoStack.push(editor.toXml());
    }

    /**
     * Record a change after a successful mutation.
     *
     * @param type        ADD, REMOVE, or MODIFY
     * @param target      what was changed (e.g. "dependency", "managed", "bom", "property")
     * @param ga          the groupId:artifactId affected
     * @param description human-readable description (e.g. "removed", "scope: compile to test")
     * @param tool        which tool originated the change (e.g. "dependencies", "updates")
     */
    void recordChange(ChangeType type, String target, String ga, String description, String tool) {
        changes.add(new Change(type, target, ga, description, tool));
        mutationCount++;
    }

    int mutationCount() {
        return mutationCount;
    }

    List<Change> changes() {
        return Collections.unmodifiableList(changes);
    }

    int changeCount() {
        return changes.size();
    }

    long addCount() {
        return changes.stream().filter(c -> c.type == ChangeType.ADD).count();
    }

    long modifyCount() {
        return changes.stream().filter(c -> c.type == ChangeType.MODIFY).count();
    }

    long removeCount() {
        return changes.stream().filter(c -> c.type == ChangeType.REMOVE).count();
    }

    boolean isDirty() {
        return !changes.isEmpty();
    }

    /**
     * Check if a given GA has any recorded change.
     */
    boolean isChanged(String ga) {
        return changes.stream().anyMatch(c -> ga.equals(c.ga));
    }

    /**
     * Return the most recent change type for a given GA, or {@code null} if unchanged.
     */
    ChangeType changeType(String ga) {
        for (int i = changes.size() - 1; i >= 0; i--) {
            if (ga.equals(changes.get(i).ga)) {
                return changes.get(i).type;
            }
        }
        return null;
    }

    // -- Convention-aware managed dependency --

    /**
     * Add or update a managed dependency following the project's detected conventions,
     * placing the managed dependency (and any version property) in this session's POM.
     *
     * @param coords the artifact coordinates (groupId, artifactId, version required)
     */
    void addManagedDependencyAligned(Coordinates coords) {
        addManagedDependencyAligned(coords, this);
    }

    /**
     * Add or update a managed dependency following conventions, placing the managed
     * dependency and version property in the specified management session's POM.
     *
     * <p>Conventions are detected from the management POM. If the management session is
     * different from this session, changes are recorded on the management session and
     * its {@link #beforeMutation()} is called automatically.</p>
     *
     * @param coords            the artifact coordinates (groupId, artifactId, version required)
     * @param managementSession the session whose POM should receive the managed dependency
     */
    void addManagedDependencyAligned(Coordinates coords, PomEditSession managementSession) {
        String version = coords.version();
        if (managementSession != this) {
            managementSession.beforeMutation();
        }
        managementSession.editor().dependencies().updateManagedDependencyAligned(true, coords);
        if (managementSession != this) {
            managementSession.recordChange(
                    ChangeType.ADD,
                    "managed",
                    coords.groupId() + ":" + coords.artifactId(),
                    "added " + version + " to dependencyManagement",
                    "cross-module");
        }
    }

    // -- Diff --

    /**
     * Return the current editor XML content for diff comparison.
     */
    String currentXml() {
        return editor.toXml();
    }

    // -- Save --

    /**
     * Save the current editor state to disk.
     *
     * <p>Checks that the file has not been modified externally since the session was created.
     * On success, returns {@code true}. On failure (external modification or I/O error),
     * returns {@code false} and the returned message describes the problem.</p>
     *
     * @return save result with success flag and status message
     */
    SaveResult save() {
        try {
            String currentOnDisk = Files.readString(pomPath);
            if (!currentOnDisk.equals(originalContent)) {
                return new SaveResult(false, "POM modified externally \u2014 save aborted");
            }
            String xml = editor.toXml();
            Files.writeString(pomPath, xml);
            int saved = changes.size();
            originalContent = xml;
            changes.clear();
            undoStack.clear();
            return new SaveResult(true, "Saved " + saved + " change(s)");
        } catch (IOException e) {
            return new SaveResult(false, "Failed to save: " + e.getMessage());
        }
    }

    record SaveResult(boolean success, String message) {}

    // -- Undo --

    /**
     * Undo the last change by restoring the previous XML snapshot.
     *
     * @return {@code true} if a change was undone, {@code false} if the undo stack is empty
     */
    boolean undoLast() {
        if (undoStack.isEmpty()) {
            return false;
        }
        String previous = undoStack.pop();
        editor = new PomEditor(Document.of(previous));
        if (!changes.isEmpty()) {
            changes.remove(changes.size() - 1);
        }
        mutationCount++;
        return true;
    }

    /**
     * Revert all changes, restoring the original POM content.
     */
    void revertAll() {
        editor = new PomEditor(Document.of(originalContent));
        changes.clear();
        undoStack.clear();
        mutationCount++;
    }
}
