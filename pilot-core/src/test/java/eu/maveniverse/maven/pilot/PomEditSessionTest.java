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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PomEditSessionTest {

    @TempDir
    Path tempDir;

    private static final String MINIMAL_POM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>test</groupId>
                <artifactId>test-project</artifactId>
                <version>1.0.0</version>
            </project>
            """;

    private Path writePom() throws IOException {
        Path pom = tempDir.resolve("pom.xml");
        Files.writeString(pom, MINIMAL_POM);
        return pom;
    }

    @Test
    void constructorReadsFile() throws IOException {
        Path pom = writePom();
        PomEditSession session = new PomEditSession(pom);
        assertThat(session.originalContent()).isEqualTo(MINIMAL_POM);
    }

    @Test
    void initialStateIsClean() throws IOException {
        Path pom = writePom();
        PomEditSession session = new PomEditSession(pom);
        assertThat(session.isDirty()).isFalse();
        assertThat(session.changeCount()).isZero();
        assertThat(session.changes()).isEmpty();
    }

    @Test
    void recordChangeMarksDirty() throws IOException {
        Path pom = writePom();
        PomEditSession session = new PomEditSession(pom);
        session.beforeMutation();
        session.recordChange(PomEditSession.ChangeType.ADD, "dependency", "g:a", "added", "test");
        assertThat(session.isDirty()).isTrue();
    }

    @Test
    void changeCountReflectsRecordedChanges() throws IOException {
        Path pom = writePom();
        PomEditSession session = new PomEditSession(pom);
        session.beforeMutation();
        session.recordChange(PomEditSession.ChangeType.ADD, "dependency", "g:a", "added", "test");
        session.beforeMutation();
        session.recordChange(PomEditSession.ChangeType.MODIFY, "dependency", "g:b", "modified", "test");
        session.beforeMutation();
        session.recordChange(PomEditSession.ChangeType.REMOVE, "dependency", "g:c", "removed", "test");
        assertThat(session.addCount()).isEqualTo(1);
        assertThat(session.modifyCount()).isEqualTo(1);
        assertThat(session.removeCount()).isEqualTo(1);
        assertThat(session.changeCount()).isEqualTo(3);
    }

    @Test
    void isChangedMatchesGa() throws IOException {
        Path pom = writePom();
        PomEditSession session = new PomEditSession(pom);
        session.beforeMutation();
        session.recordChange(PomEditSession.ChangeType.ADD, "dependency", "g:a", "added", "test");
        assertThat(session.isChanged("g:a")).isTrue();
        assertThat(session.isChanged("x:y")).isFalse();
    }

    @Test
    void changeTypeReturnsLastType() throws IOException {
        Path pom = writePom();
        PomEditSession session = new PomEditSession(pom);
        session.beforeMutation();
        session.recordChange(PomEditSession.ChangeType.ADD, "dependency", "g:a", "added", "test");
        session.beforeMutation();
        session.recordChange(PomEditSession.ChangeType.MODIFY, "dependency", "g:a", "modified", "test");
        assertThat(session.changeType("g:a")).isEqualTo(PomEditSession.ChangeType.MODIFY);
    }

    @Test
    void changeTypeReturnsNullForUnchanged() throws IOException {
        Path pom = writePom();
        PomEditSession session = new PomEditSession(pom);
        assertThat(session.changeType("x:y")).isNull();
    }

    @Test
    void undoLastRemovesChange() throws IOException {
        Path pom = writePom();
        PomEditSession session = new PomEditSession(pom);
        session.beforeMutation();
        session.recordChange(PomEditSession.ChangeType.ADD, "dependency", "g:a", "added", "test");
        session.beforeMutation();
        session.recordChange(PomEditSession.ChangeType.MODIFY, "dependency", "g:b", "modified", "test");
        session.undoLast();
        assertThat(session.changeCount()).isEqualTo(1);
    }

    @Test
    void undoLastRestoresXml() throws IOException {
        Path pom = writePom();
        PomEditSession session = new PomEditSession(pom);
        String xmlBefore = session.currentXml();
        session.beforeMutation();
        session.editor().properties().updateProperty(true, "foo", "bar");
        session.recordChange(PomEditSession.ChangeType.ADD, "property", "foo", "added", "test");
        session.undoLast();
        assertThat(session.currentXml()).isEqualTo(xmlBefore);
    }

    @Test
    void undoLastOnEmptyReturnsFalse() throws IOException {
        Path pom = writePom();
        PomEditSession session = new PomEditSession(pom);
        assertThat(session.undoLast()).isFalse();
    }

    @Test
    void revertAllRestoresOriginal() throws IOException {
        Path pom = writePom();
        PomEditSession session = new PomEditSession(pom);
        session.beforeMutation();
        session.editor().properties().updateProperty(true, "foo", "bar");
        session.recordChange(PomEditSession.ChangeType.ADD, "property", "foo", "added", "test");
        session.revertAll();
        assertThat(session.isDirty()).isFalse();
        assertThat(session.currentXml()).isEqualTo(session.originalContent());
    }

    @Test
    void saveWritesToDisk() throws IOException {
        Path pom = writePom();
        PomEditSession session = new PomEditSession(pom);
        session.beforeMutation();
        session.editor().properties().updateProperty(true, "foo", "bar");
        session.recordChange(PomEditSession.ChangeType.ADD, "property", "foo", "added", "test");
        String expectedXml = session.currentXml();
        PomEditSession.SaveResult result = session.save();
        assertThat(result.success()).isTrue();
        assertThat(Files.readString(pom)).isEqualTo(expectedXml);
    }

    @Test
    void saveDetectsExternalModification() throws IOException {
        Path pom = writePom();
        PomEditSession session = new PomEditSession(pom);
        session.beforeMutation();
        session.editor().properties().updateProperty(true, "foo", "bar");
        session.recordChange(PomEditSession.ChangeType.ADD, "property", "foo", "added", "test");
        Files.writeString(pom, "<!-- externally modified -->\n" + MINIMAL_POM);
        PomEditSession.SaveResult result = session.save();
        assertThat(result.success()).isFalse();
    }

    @Test
    void saveSuccessResetsState() throws IOException {
        Path pom = writePom();
        PomEditSession session = new PomEditSession(pom);
        session.beforeMutation();
        session.editor().properties().updateProperty(true, "foo", "bar");
        session.recordChange(PomEditSession.ChangeType.ADD, "property", "foo", "added", "test");
        session.save();
        assertThat(session.isDirty()).isFalse();
        assertThat(session.changes()).isEmpty();
    }
}
