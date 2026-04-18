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
package eu.maveniverse.maven.pilot.mvn4;

import static org.junit.jupiter.api.Assertions.*;

import eu.maveniverse.maven.pilot.PilotProject;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Tests reactor loading and dependency collection without the TUI.
 */
class LoadReactorTest {

    @Test
    void loadSelfReactor() throws Exception {
        System.setProperty("maven.logger.defaultLogLevel", "ERROR");

        Path pomPath = Path.of("pom.xml").toAbsolutePath().normalize();
        AtomicReference<String> status = new AtomicReference<>("");

        PilotMain.LoadedReactor reactor = PilotMain.loadReactor(pomPath, status);

        assertNotNull(reactor);
        assertNotNull(reactor.engine());
        assertFalse(reactor.projectsByPomPath().isEmpty(), "Should have loaded projects");

        System.out.println("Loaded " + reactor.projectsByPomPath().size() + " projects");
        for (PilotProject p : reactor.projectsByPomPath().values()) {
            System.out.println("  " + p.gav());

            var panel = reactor.engine().createPanel("dependencies", p, List.of(p));
            assertNotNull(panel, "Dependencies panel should not be null for " + p.ga());
        }
    }
}
