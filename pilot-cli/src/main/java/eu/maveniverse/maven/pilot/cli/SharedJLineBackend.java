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

import dev.tamboui.layout.Position;
import dev.tamboui.layout.Size;
import dev.tamboui.terminal.AbstractBackend;
import java.io.IOException;
import java.io.PrintWriter;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.Terminal.Signal;
import org.jline.utils.InfoCmp;
import org.jline.utils.NonBlockingReader;

/**
 * TamboUI backend that wraps an existing JLine terminal instead of creating a new one.
 * Used when running TUI commands inside the REPL to share the terminal.
 * Unlike JLineBackend, close() does NOT close the underlying terminal.
 */
class SharedJLineBackend extends AbstractBackend {

    private static final String ESC = "\033";
    private static final String CSI = ESC + "[";

    private final Terminal terminal;
    private final PrintWriter writer;
    private final NonBlockingReader reader;
    private Attributes savedAttributes;
    private boolean inAlternateScreen;
    private boolean mouseEnabled;

    SharedJLineBackend(Terminal terminal) {
        this.terminal = terminal;
        this.writer = terminal.writer();
        this.reader = terminal.reader();
    }

    @Override
    public void flush() throws IOException {
        writer.flush();
    }

    @Override
    public void clear() throws IOException {
        writer.print(CSI + "2J");
        writer.print(CSI + "H");
        writer.flush();
    }

    @Override
    public Size size() throws IOException {
        return new Size(terminal.getWidth(), terminal.getHeight());
    }

    @Override
    public void showCursor() throws IOException {
        writer.print(CSI + "?25h");
        writer.flush();
    }

    @Override
    public void hideCursor() throws IOException {
        writer.print(CSI + "?25l");
        writer.flush();
    }

    @Override
    public Position getCursorPosition() throws IOException {
        return Position.ORIGIN;
    }

    @Override
    public void enterAlternateScreen() throws IOException {
        terminal.puts(InfoCmp.Capability.enter_ca_mode);
        writer.flush();
        inAlternateScreen = true;
    }

    @Override
    public void leaveAlternateScreen() throws IOException {
        terminal.puts(InfoCmp.Capability.exit_ca_mode);
        writer.flush();
        inAlternateScreen = false;
    }

    @Override
    public void enableRawMode() throws IOException {
        savedAttributes = terminal.getAttributes();
        terminal.enterRawMode();
        Attributes attrs = terminal.getAttributes();
        attrs.setLocalFlag(Attributes.LocalFlag.ISIG, false);
        terminal.setAttributes(attrs);
    }

    @Override
    public void disableRawMode() throws IOException {
        if (savedAttributes != null) {
            terminal.setAttributes(savedAttributes);
        }
    }

    @Override
    public void enableMouseCapture() throws IOException {
        writer.print(CSI + "?1000h");
        writer.print(CSI + "?1002h");
        writer.print(CSI + "?1015h");
        writer.print(CSI + "?1006h");
        writer.flush();
        mouseEnabled = true;
    }

    @Override
    public void disableMouseCapture() throws IOException {
        writer.print(CSI + "?1006l");
        writer.print(CSI + "?1015l");
        writer.print(CSI + "?1002l");
        writer.print(CSI + "?1000l");
        writer.flush();
        mouseEnabled = false;
    }

    @Override
    public void scrollUp(int lines) throws IOException {
        writer.print(CSI + lines + "S");
        writer.flush();
    }

    @Override
    public void scrollDown(int lines) throws IOException {
        writer.print(CSI + lines + "T");
        writer.flush();
    }

    @Override
    public void insertLines(int n) throws IOException {
        if (n > 0) writer.print(CSI + n + "L");
    }

    @Override
    public void deleteLines(int n) throws IOException {
        if (n > 0) writer.print(CSI + n + "M");
    }

    @Override
    public void moveCursorUp(int n) throws IOException {
        if (n > 0) writer.print(CSI + n + "A");
    }

    @Override
    public void moveCursorDown(int n) throws IOException {
        if (n > 0) writer.print(CSI + n + "B");
    }

    @Override
    public void moveCursorRight(int n) throws IOException {
        if (n > 0) writer.print(CSI + n + "C");
    }

    @Override
    public void moveCursorLeft(int n) throws IOException {
        if (n > 0) writer.print(CSI + n + "D");
    }

    @Override
    public void eraseToEndOfLine() throws IOException {
        writer.print(CSI + "K");
    }

    @Override
    public void carriageReturn() throws IOException {
        writer.print("\r");
    }

    @Override
    public void writeRaw(byte[] data) throws IOException {
        terminal.output().write(data);
    }

    @Override
    public void writeRaw(String data) {
        writer.print(data);
    }

    @Override
    public void onResize(Runnable handler) {
        terminal.handle(Signal.WINCH, signal -> handler.run());
    }

    @Override
    public int read(int timeoutMs) throws IOException {
        return reader.read(timeoutMs);
    }

    @Override
    public int peek(int timeoutMs) throws IOException {
        return reader.peek(timeoutMs);
    }

    @Override
    public void close() throws IOException {
        writer.print(CSI + "0m");
        if (mouseEnabled) {
            disableMouseCapture();
        }
        if (inAlternateScreen) {
            leaveAlternateScreen();
        }
        showCursor();
        disableRawMode();
        writer.flush();
        // Do NOT close the terminal — it's shared with the REPL
    }
}
