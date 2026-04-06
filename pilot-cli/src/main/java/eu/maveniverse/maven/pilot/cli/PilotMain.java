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
package eu.maveniverse.maven.pilot.cli;

import java.nio.file.Path;
import java.util.stream.Collectors;
import org.jline.shell.CommandSession;
import org.jline.shell.Shell;
import org.jline.shell.impl.SimpleCommandGroup;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

/**
 * Pilot CLI entry point.
 *
 * <ul>
 *   <li>{@code pilot} — enters REPL shell</li>
 *   <li>{@code pilot search guava} — one-shot command</li>
 * </ul>
 *
 * <p>To suppress JVM Unsafe deprecation warnings on JDK 23+, launch with:
 * {@code java --sun-misc-unsafe-memory-access=allow -jar pilot-cli.jar}
 */
public class PilotMain {

    public static void main(String[] args) throws Exception {
        MavenContext mavenContext = new MavenContext();

        try (Terminal terminal = TerminalBuilder.builder().build()) {
            PilotCommands commands = new PilotCommands(terminal, mavenContext);

            if (args.length > 0) {
                // One-shot mode: dispatch to the named command
                String cmdName = args[0];
                String[] cmdArgs = new String[args.length - 1];
                System.arraycopy(args, 1, cmdArgs, 0, cmdArgs.length);

                var cmd = commands.commands().stream()
                        .filter(c -> c.name().equals(cmdName))
                        .findFirst()
                        .orElse(null);
                if (cmd == null) {
                    System.err.println("Unknown command: " + cmdName);
                    String available =
                            commands.commands().stream().map(c -> c.name()).collect(Collectors.joining(", "));
                    System.err.println("Available commands: " + available);
                    System.exit(1);
                }
                cmd.execute(new CommandSession(terminal), cmdArgs);
            } else {
                // REPL mode
                Path historyFile = Path.of(System.getProperty("user.home"), ".pilot_history");
                boolean hasProject = mavenContext.findPom() != null;
                String projectName = hasProject ? mavenContext.projectName() : null;
                String prompt = projectName != null ? "pilot [" + projectName + "]> " : "pilot> ";

                Shell shell = Shell.builder()
                        .terminal(terminal)
                        .prompt(prompt)
                        .historyFile(historyFile)
                        .groups(new SimpleCommandGroup("pilot", commands.commands()))
                        .helpCommands(true)
                        .historyCommands(true)
                        .build();

                shell.run();
            }
        }
    }
}
