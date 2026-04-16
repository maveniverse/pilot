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

import dev.tamboui.layout.Alignment;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.paragraph.Paragraph;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Unified shell container for the Pilot TUI.
 *
 * <p>Provides an IDE-like layout with a persistent module tree on the left,
 * tool tabs in the header, and a contextual content pane on the right.
 * Manages focus, tool switching, animated panel resizing, and event dispatch.</p>
 */
public class PilotShell {

    enum Focus {
        TREE,
        CONTENT
    }

    enum TreeWidth {
        FULL,
        NARROW,
        HIDDEN
    }

    record ToolDef(String name, String id, char mnemonic, boolean aggregatable) {
        boolean isModuleIndependent() {
            return "search".equals(id);
        }
    }

    @FunctionalInterface
    public interface ToolPanelFactory {
        ToolPanel create(
                String toolId,
                PilotProject project,
                List<PilotProject> scope,
                PomEditSession session,
                java.util.function.Function<java.nio.file.Path, PomEditSession> sessionProvider,
                java.util.function.Consumer<String> progress)
                throws Exception;
    }

    @FunctionalInterface
    public interface LoadingStatusSupplier {
        String get();
    }

    static final List<ToolDef> TOOLS = List.of(
            new ToolDef("Tree", "tree", 't', false),
            new ToolDef("Deps", "dependencies", 'd', false),
            new ToolDef("Pom", "pom", 'p', false),
            new ToolDef("Align", "align", 'a', true),
            new ToolDef("Updates", "updates", 'u', true),
            new ToolDef("Conflicts", "conflicts", 'c', true),
            new ToolDef("audIt", "audit", 'i', true),
            new ToolDef("Search", "search", 's', false));

    private final Theme theme = Theme.DEFAULT;

    // Focus and layout
    private Focus focus;
    private TreeWidth targetTreeWidth;
    private int currentTreeCols = -1;

    // Tool state
    private int activeToolIndex = 0;
    private ToolPanel activePanel;
    private final Map<String, ToolPanel> panelCache = new HashMap<>();
    private final Map<String, PomEditSession> sessionCache = new ConcurrentHashMap<>();
    private boolean pendingQuit;
    private String pendingQuitStatus;

    // Components
    private final ReactorModel reactorModel;
    private final ModuleTreePane treePane;
    private final ToolPanelFactory panelFactory;
    private final List<PilotProject> projects;
    private final HelpOverlay helpOverlay = new HelpOverlay();

    // Module context
    private PilotProject selectedProject;
    private List<PilotProject> selectedScope;
    private TuiRunner runner;

    // Captured log output (redirected from System.out/err during TUI)
    private java.io.ByteArrayOutputStream capturedLog;

    // Async panel creation
    private final ExecutorService panelExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "pilot-panel-creator");
        t.setDaemon(true);
        return t;
    });
    private String loadingCacheKey;
    private final Map<String, Future<?>> runningTasks = new HashMap<>();
    private String panelError;
    private volatile String panelLoadingStatus;
    private final LoadingStatusSupplier loadingStatusSupplier;

    public PilotShell(ReactorModel reactorModel, List<PilotProject> projects, ToolPanelFactory panelFactory) {
        this(reactorModel, projects, panelFactory, null);
    }

    public PilotShell(
            ReactorModel reactorModel,
            List<PilotProject> projects,
            ToolPanelFactory panelFactory,
            LoadingStatusSupplier loadingStatusSupplier) {
        this.reactorModel = reactorModel;
        this.projects = projects;
        this.panelFactory = panelFactory;
        this.loadingStatusSupplier = loadingStatusSupplier;

        boolean singleModule = reactorModel == null || projects.size() <= 1;

        if (!singleModule) {
            this.treePane = new ModuleTreePane(reactorModel, this::onModuleSelected);
            this.selectedProject = treePane.selectedProject();
            this.selectedScope = computeScope();
            this.focus = Focus.TREE;
            this.targetTreeWidth = TreeWidth.NARROW;
            this.treePane.setFocused(true);
        } else {
            this.treePane = null;
            this.selectedProject = projects.get(0);
            this.selectedScope = projects;
            this.focus = Focus.CONTENT;
            this.targetTreeWidth = TreeWidth.HIDDEN;
            this.currentTreeCols = 0;
        }
    }

    public void run() throws Exception {
        ToolPanel.configureBackend();
        var configured = TuiRunner.builder()
                .eventHandler(this::handleEvent)
                .renderer(this::render)
                .tickRate(Duration.ofMillis(20))
                .mouseCapture(true)
                .build();
        // Redirect Maven log output (INFO/WARNING from model builder, repo system)
        // to a buffer to prevent corruption of the TUI on the alternate screen
        var origOut = System.out;
        var origErr = System.err;
        capturedLog = new java.io.ByteArrayOutputStream();
        var logStream = new java.io.PrintStream(capturedLog, true);
        System.setOut(logStream);
        System.setErr(logStream);
        try {
            runner = configured.runner();
            // Create initial panel synchronously (before event loop starts,
            // runOnRenderThread callbacks won't be processed)
            loadInitialPanel();
            configured.run();
        } finally {
            System.setOut(origOut);
            System.setErr(origErr);
            panelExecutor.shutdownNow();
            configured.close();
            // Print any captured log output after TUI exits
            String log = capturedLog.toString();
            if (!log.isBlank()) {
                origErr.print(log);
            }
        }
    }

    // ── Event handling ──────────────────────────────────────────────────────

    boolean handleEvent(Event event, TuiRunner runner) {
        if (helpOverlay.isActive()) {
            if (event instanceof KeyEvent key) {
                if (helpOverlay.handleKey(key)) return true;
                if (key.isCtrlC()) {
                    runner.quit();
                    return true;
                }
            }
            if (event instanceof MouseEvent mouse) {
                if (helpOverlay.handleMouse(mouse)) return true;
            }
            return true;
        }

        if (event instanceof KeyEvent key) {
            return handleKeyEvent(key);
        }

        if (event instanceof MouseEvent mouse) {
            return handleMouseEvent(mouse);
        }

        return true;
    }

    private boolean handleKeyEvent(KeyEvent key) {
        // Ctrl+C always quits
        if (key.isCtrlC()) {
            runner.quit();
            return true;
        }

        // Pending quit prompt: y/n/Esc
        if (pendingQuit) {
            if (key.isCharIgnoreCase('y')) {
                saveAllAndQuit();
                return true;
            }
            if (key.isCharIgnoreCase('n')) {
                runner.quit();
                return true;
            }
            if (key.isKey(KeyCode.ESCAPE)) {
                pendingQuit = false;
                pendingQuitStatus = null;
                return true;
            }
            return true; // consume all keys during quit prompt
        }

        // Alt+letter: switch tool (checked before delegation because
        // isCharIgnoreCase does not check modifiers)
        if (key.modifiers().alt() && key.code() == KeyCode.CHAR) {
            char c = Character.toLowerCase(key.character());
            for (int i = 0; i < TOOLS.size(); i++) {
                if (c == TOOLS.get(i).mnemonic) {
                    switchTool(i);
                    return true;
                }
            }
        }

        // Edit lifecycle keys (before panel delegation so they're global)
        PomEditSession session = currentSession();
        if (key.isCharIgnoreCase('w') && session != null && session.isDirty()) {
            PomEditSession.SaveResult result = session.save();
            pendingQuitStatus = result.message();
            return true;
        }
        if (key.isChar('z') && session != null && session.isDirty()) {
            if (session.undoLast()) {
                pendingQuitStatus = "Undid last change";
            }
            return true;
        }
        if (key.isChar('R') && session != null && session.isDirty()) {
            session.revertAll();
            // Invalidate cached panels for this POM so they refresh
            invalidatePanelCacheForPom(session.pomPath());
            refreshActivePanel();
            pendingQuitStatus = "Reverted all changes";
            return true;
        }

        // Delegate to focused pane (search inputs consume chars like q)
        if (focus == Focus.TREE && treePane != null) {
            if (treePane.handleKeyEvent(key)) return true;
        } else if (focus == Focus.CONTENT && activePanel != null) {
            if (activePanel.handleKeyEvent(key)) return true;
        }

        // Shell-level keys (unhandled by focused pane)
        if (key.isKey(KeyCode.TAB)) {
            // Tab toggles focus between tree and content
            if (treePane != null) {
                toggleFocus();
            }
            return true;
        }
        // Number keys: 0 = focus module tree, 1-9 = select sub-view tab
        if (key.code() == KeyCode.CHAR
                && !key.hasCtrl()
                && !key.hasAlt()
                && key.character() >= '0'
                && key.character() <= '9') {
            if (key.character() == '0') {
                if (treePane != null) {
                    setFocus(Focus.TREE);
                }
                return true;
            }
            int index = key.character() - '1';
            if (activePanel != null && index < activePanel.subViewCount()) {
                activePanel.setActiveSubView(index);
                if (focus != Focus.CONTENT) setFocus(Focus.CONTENT);
                return true;
            }
            // Even if panel has only one view, '1' focuses content
            if (activePanel != null && index == 0) {
                if (focus != Focus.CONTENT) setFocus(Focus.CONTENT);
                return true;
            }
        }
        if (key.isKey(KeyCode.ENTER) && focus == Focus.TREE) {
            setFocus(Focus.CONTENT);
            return true;
        }
        if (key.isCharIgnoreCase('q')) {
            requestQuit();
            return true;
        }
        if (key.isChar('?') || key.isCharIgnoreCase('h')) {
            helpOverlay.open(buildHelp());
            return true;
        }
        if (key.isChar('\\') && treePane != null) {
            cycleTreeWidth();
            return true;
        }

        return false;
    }

    private PomEditSession currentSession() {
        if (selectedProject == null) return null;
        return sessionCache.get(selectedProject.pomPath.toString());
    }

    private void requestQuit() {
        List<PomEditSession> dirtySessions =
                sessionCache.values().stream().filter(PomEditSession::isDirty).toList();
        if (dirtySessions.isEmpty()) {
            runner.quit();
        } else {
            pendingQuit = true;
            int total =
                    dirtySessions.stream().mapToInt(PomEditSession::changeCount).sum();
            pendingQuitStatus = total + " unsaved change(s). Save? (y)es (n)o (Esc)cancel";
        }
    }

    private void saveAllAndQuit() {
        for (PomEditSession session : sessionCache.values()) {
            if (session.isDirty()) {
                PomEditSession.SaveResult result = session.save();
                if (!result.success()) {
                    pendingQuit = false;
                    pendingQuitStatus = result.message();
                    return;
                }
            }
        }
        runner.quit();
    }

    @SuppressWarnings("unused")
    private void invalidatePanelCacheForPom(java.nio.file.Path pomPath) {
        panelCache.entrySet().removeIf(entry -> {
            // Cache keys are "toolId:moduleGA" — we need to match by POM path
            // which requires checking the project. For simplicity, clear all module-dependent panels.
            String cacheKey = entry.getKey();
            return cacheKey.contains(":");
        });
    }

    private boolean handleMouseEvent(MouseEvent mouse) {
        // Header: click on tool tabs to switch tools
        if (mouse.isClick() && headerArea != null && mouse.y() == headerArea.y()) {
            if (toolTabStarts != null) {
                int mx = mouse.x() - headerArea.x();
                for (int i = 0; i < TOOLS.size(); i++) {
                    if (mx >= toolTabStarts[i] && mx < toolTabEnds[i]) {
                        switchTool(i);
                        return true;
                    }
                }
            }
            return true;
        }

        // Tree pane: clicks
        if (treePane != null && treeArea != null && isInRect(mouse, treeArea)) {
            if (mouse.isClick()) {
                if (focus != Focus.TREE) setFocus(Focus.TREE);
                treePane.handleMouseEvent(mouse, treeArea);
                return true;
            }
        }

        // Content pane: clicks
        if (contentArea != null && isInRect(mouse, contentArea)) {
            if (mouse.isClick()) {
                if (focus != Focus.CONTENT) setFocus(Focus.CONTENT);
                if (activePanel != null) {
                    activePanel.handleMouseEvent(mouse, contentArea);
                }
                return true;
            }
        }

        // Scroll follows mouse position and updates focus
        if (mouse.isScroll()) {
            if (treePane != null && treeArea != null && isInRect(mouse, treeArea)) {
                if (focus != Focus.TREE) setFocus(Focus.TREE);
                treePane.handleMouseEvent(mouse, treeArea);
            } else if (activePanel != null && contentArea != null && isInRect(mouse, contentArea)) {
                if (focus != Focus.CONTENT) setFocus(Focus.CONTENT);
                activePanel.handleMouseEvent(mouse, contentArea);
            }
            return true;
        }

        return true;
    }

    private static boolean isInRect(MouseEvent mouse, Rect rect) {
        return mouse.x() >= rect.x()
                && mouse.x() < rect.x() + rect.width()
                && mouse.y() >= rect.y()
                && mouse.y() < rect.y() + rect.height();
    }

    // Store rendered rects for mouse hit testing
    private Rect headerArea;
    private Rect treeArea;
    private Rect contentArea;
    private int[] toolTabStarts; // x-offset of each tool label in the header
    private int[] toolTabEnds;

    // ── Focus management ────────────────────────────────────────────────────

    private void toggleFocus() {
        setFocus(focus == Focus.TREE ? Focus.CONTENT : Focus.TREE);
    }

    private void setFocus(Focus newFocus) {
        if (treePane == null && newFocus == Focus.TREE) return;
        focus = newFocus;
        if (treePane != null) {
            treePane.setFocused(focus == Focus.TREE);
            if (targetTreeWidth == TreeWidth.HIDDEN && focus == Focus.TREE) {
                targetTreeWidth = TreeWidth.FULL;
            }
        }
        if (activePanel != null) {
            activePanel.setFocused(focus == Focus.CONTENT);
        }
    }

    private void cycleTreeWidth() {
        targetTreeWidth = switch (targetTreeWidth) {
            case FULL -> TreeWidth.NARROW;
            case NARROW -> TreeWidth.HIDDEN;
            case HIDDEN -> TreeWidth.FULL;
        };
        if (targetTreeWidth == TreeWidth.HIDDEN && focus == Focus.TREE) {
            setFocus(Focus.CONTENT);
        }
    }

    // ── Tool management ─────────────────────────────────────────────────────

    private void loadInitialPanel() {
        activeToolIndex = 0;
        if (!isToolAvailable(0)) return;
        refreshActivePanel();
    }

    private void switchTool(int toolIndex) {
        switchTool(toolIndex, true);
    }

    private void switchTool(int toolIndex, boolean focusContent) {
        if (toolIndex < 0 || toolIndex >= TOOLS.size()) return;
        if (!isToolAvailable(toolIndex)) return;
        activeToolIndex = toolIndex;
        refreshActivePanel();
        if (focusContent) {
            setFocus(Focus.CONTENT);
        }
    }

    private boolean isToolAvailable(int toolIndex) {
        ToolDef tool = TOOLS.get(toolIndex);
        if ("dependencies".equals(tool.id) && treePane != null && treePane.isSelectedParent()) {
            return false;
        }
        return true;
    }

    private void refreshActivePanel() {
        ToolDef tool = TOOLS.get(activeToolIndex);
        if (!isToolAvailable(activeToolIndex)) {
            activePanel = null;
            loadingCacheKey = null;
            return;
        }
        // Remember current sub-view to carry over to new panel
        int currentSubView = activePanel != null ? activePanel.activeSubView() : 0;

        String cacheKey = tool.isModuleIndependent()
                ? tool.id
                : tool.id + ":" + (selectedProject != null ? selectedProject.ga() : "null");

        ToolPanel cached = panelCache.get(cacheKey);
        if (cached != null) {
            activePanel = cached;
            // Restore sub-view to keep navigation homogeneous across modules
            if (currentSubView < activePanel.subViewCount()) {
                activePanel.setActiveSubView(currentSubView);
            }
            loadingCacheKey = null;
            return;
        }

        // Already loading this exact panel — just re-attach
        if (cacheKey.equals(loadingCacheKey)) {
            return;
        }

        panelError = null;
        panelLoadingStatus = null;
        loadingCacheKey = cacheKey;

        // Reuse existing running task for this panel if available
        Future<?> existing = runningTasks.get(cacheKey);
        if (existing != null && !existing.isDone()) {
            return;
        }

        final PilotProject proj = selectedProject;
        final List<PilotProject> scope = selectedScope;
        final int subView = currentSubView;
        // Get or create session for this module's POM
        final PomEditSession session;
        if (proj != null && !tool.isModuleIndependent()) {
            String pomKey = proj.pomPath.toString();
            session = sessionCache.computeIfAbsent(pomKey, k -> new PomEditSession(proj.pomPath));
        } else {
            session = null;
        }
        final java.util.function.Function<java.nio.file.Path, PomEditSession> sessionProvider =
                path -> sessionCache.computeIfAbsent(path.toString(), k -> new PomEditSession(path));
        Future<?> task = panelExecutor.submit(() -> {
            try {
                ToolPanel panel = panelFactory.create(tool.id, proj, scope, session, sessionProvider, s -> {
                    if (cacheKey.equals(loadingCacheKey)) {
                        panelLoadingStatus = s;
                    }
                });
                if (panel != null && runner != null) {
                    runner.runOnRenderThread(() -> {
                        panel.setRunner(runner);
                        panelCache.put(cacheKey, panel);
                        runningTasks.remove(cacheKey);
                        // Only set active if still waiting for this panel
                        if (cacheKey.equals(loadingCacheKey)) {
                            activePanel = panel;
                            activePanel.setFocused(focus == Focus.CONTENT);
                            // Restore sub-view
                            if (subView < activePanel.subViewCount()) {
                                activePanel.setActiveSubView(subView);
                            }
                            loadingCacheKey = null;
                        }
                    });
                }
            } catch (Exception e) {
                String msg = e.getMessage();
                if (msg == null) msg = e.getClass().getSimpleName();
                final String errorMsg = msg;
                runningTasks.remove(cacheKey);
                if (runner != null) {
                    runner.runOnRenderThread(() -> {
                        if (cacheKey.equals(loadingCacheKey)) {
                            loadingCacheKey = null;
                            panelError = errorMsg;
                        }
                    });
                }
            }
        });
        runningTasks.put(cacheKey, task);
    }

    private void onModuleSelected(PilotProject project) {
        if (project == selectedProject) return;
        selectedProject = project;
        selectedScope = computeScope();

        ToolDef activeTool = TOOLS.get(activeToolIndex);
        if (!activeTool.isModuleIndependent()) {
            refreshActivePanel();
        }
    }

    private List<PilotProject> computeScope() {
        if (treePane != null && treePane.isSelectedParent()) {
            return treePane.selectedSubtree();
        }
        return selectedProject != null ? List.of(selectedProject) : projects;
    }

    // ── Rendering ───────────────────────────────────────────────────────────

    void render(Frame frame) {
        Rect area = frame.area();

        animateTreeWidth(area.width());

        var zones = Layout.vertical()
                .constraints(Constraint.length(1), Constraint.length(1), Constraint.fill())
                .split(area);

        renderHeader(frame, zones.get(0));

        Rect bodyArea = zones.get(2);

        if (helpOverlay.isActive()) {
            // Split body into content + info bar; info bar stays visible
            var bodyRows = Layout.vertical()
                    .constraints(Constraint.fill(), Constraint.length(3))
                    .split(bodyArea);
            Rect contentZone = bodyRows.get(0);
            Rect infoZone = bodyRows.get(1);

            // Help grows from 0 up to contentZone + 1 (to also cover header divider)
            int maxH = contentZone.height() + 1;
            int helpH = helpOverlay.animate(maxH);

            if (helpH >= maxH) {
                // Fully open: help replaces header divider + entire content zone
                Rect helpArea = new Rect(
                        zones.get(1).x(), zones.get(1).y(), zones.get(1).width(), contentZone.height() + 1);
                helpOverlay.render(frame, helpArea);
            } else if (helpH > 0) {
                // Animating: header divider visible, content zone split
                renderDivider(frame, zones.get(1));
                var split = Layout.vertical()
                        .constraints(Constraint.fill(), Constraint.length(helpH))
                        .split(contentZone);
                renderContentWithTree(frame, split.get(0));
                helpOverlay.render(frame, split.get(1));
            } else {
                // Close animation done
                renderDivider(frame, zones.get(1));
                renderContentWithTree(frame, contentZone);
            }
            // Info bar always visible
            renderInfoBar(frame, infoZone);
        } else {
            renderDivider(frame, zones.get(1));
            renderBody(frame, bodyArea);
        }
    }

    private void renderHeader(Frame frame, Rect area) {
        headerArea = area;
        toolTabStarts = new int[TOOLS.size()];
        toolTabEnds = new int[TOOLS.size()];

        List<Span> spans = new ArrayList<>();
        String prefix = " Pilot ── ";
        spans.add(theme.brandLabel());
        spans.add(theme.brandSeparator());
        int xPos = prefix.length();

        for (int i = 0; i < TOOLS.size(); i++) {
            ToolDef tool = TOOLS.get(i);
            toolTabStarts[i] = xPos;
            String label;
            if (i == activeToolIndex) {
                label = "[▸" + tool.name + "]";
                spans.add(theme.activeToolTab(tool.name));
            } else if (isToolAvailable(i)) {
                label = tool.name;
                spans.add(theme.inactiveToolTab(label));
            } else {
                label = tool.name;
                spans.add(theme.unavailableToolTab(label));
            }
            xPos += label.length();
            toolTabEnds[i] = xPos;
            if (i < TOOLS.size() - 1) {
                spans.add(Span.raw(" "));
                xPos++;
            }
        }

        frame.renderWidget(Paragraph.from(Line.from(spans)), area);
    }

    private void renderDivider(Frame frame, Rect area) {
        String div = "─".repeat(area.width());
        frame.renderWidget(Paragraph.from(Line.from(Span.raw(div).fg(theme.dividerColor()))), area);
    }

    private void renderBody(Frame frame, Rect area) {
        // Split: content area (tree + gap + panel) on top, divider + info bar at bottom
        var bodyRows = Layout.vertical()
                .constraints(Constraint.fill(), Constraint.length(3))
                .split(area);

        renderContentWithTree(frame, bodyRows.get(0));
        renderInfoBar(frame, bodyRows.get(1));
    }

    private void renderContentWithTree(Frame frame, Rect contentZone) {
        if (currentTreeCols > 0 && treePane != null) {
            var cols = Layout.horizontal()
                    .constraints(Constraint.length(currentTreeCols), Constraint.length(1), Constraint.fill())
                    .split(contentZone);

            treeArea = cols.get(0);
            Rect separatorArea = cols.get(1);
            contentArea = cols.get(2);

            if (targetTreeWidth == TreeWidth.FULL) {
                treePane.renderFull(frame, treeArea);
            } else {
                treePane.renderNarrow(frame, treeArea);
            }

            // Vertical separator between tree and content
            List<Line> sepLines = new ArrayList<>();
            for (int i = 0; i < separatorArea.height(); i++) {
                sepLines.add(Line.from(Span.raw("│").fg(theme.separatorColor())));
            }
            frame.renderWidget(
                    Paragraph.builder().text(new Text(sepLines, Alignment.LEFT)).build(), separatorArea);

            renderContentPanel(frame, contentArea);
        } else {
            treeArea = null;
            contentArea = contentZone;
            renderContentPanel(frame, contentZone);
        }
    }

    private void renderContentPanel(Frame frame, Rect area) {
        if (activePanel != null) {
            activePanel.render(frame, area);
        } else {
            renderPlaceholder(frame, area);
        }
    }

    private void renderPlaceholder(Frame frame, Rect area) {
        ToolDef tool = TOOLS.get(activeToolIndex);
        String title;
        String message;
        if (loadingCacheKey != null) {
            title = " " + tool.name + " ";
            String status = panelLoadingStatus;
            if (status == null && loadingStatusSupplier != null) {
                status = loadingStatusSupplier.get();
            }
            message = status != null ? status : "Loading…";
        } else if (panelError != null) {
            title = " " + tool.name + " ";
            message = "Error: " + panelError;
        } else if (!isToolAvailable(activeToolIndex)) {
            title = " " + tool.name + " ";
            message = tool.name + " is not available for parent modules";
        } else {
            title = " No Tool Selected ";
            message = "Select a tool with Alt+letter";
        }
        Block block = Block.builder()
                .title(title)
                .borderType(BorderType.ROUNDED)
                .borderStyle(theme.placeholderBorder())
                .build();
        Paragraph p = Paragraph.builder().text(message).block(block).centered().build();
        frame.renderWidget(p, area);
    }

    private void renderInfoBar(Frame frame, Rect area) {
        var rows = Layout.vertical()
                .constraints(Constraint.length(1), Constraint.length(1), Constraint.length(1))
                .split(area);

        // Divider line
        String divider = "─".repeat(area.width());
        frame.renderWidget(Paragraph.from(Line.from(Span.raw(divider).fg(theme.dividerColor()))), rows.get(0));

        // Status line
        List<Span> statusSpans = new ArrayList<>();
        if (pendingQuitStatus != null) {
            statusSpans.add(Span.raw(" " + pendingQuitStatus).fg(Color.YELLOW));
        } else if (focus == Focus.TREE && treePane != null) {
            statusSpans.add(Span.raw(" Module Tree").fg(theme.statusColor()));
            if (selectedProject != null) {
                statusSpans.add(Span.raw(" │ " + selectedProject.artifactId).fg(theme.secondaryTextColor()));
            }
        } else if (activePanel != null) {
            String status = activePanel.status();
            if (status != null && !status.isEmpty()) {
                statusSpans.add(Span.raw(" " + status).fg(theme.statusColor()));
            }
        }
        frame.renderWidget(Paragraph.from(Line.from(statusSpans)), rows.get(1));

        // Key hints line — context-sensitive based on focus
        List<Span> keySpans = new ArrayList<>();
        keySpans.add(Span.raw(" "));
        if (focus == Focus.TREE && treePane != null) {
            keySpans.addAll(treePane.keyHints());
            keySpans.add(Span.raw("  "));
        } else if (activePanel != null) {
            List<Span> toolHints = activePanel.keyHints();
            if (toolHints != null && !toolHints.isEmpty()) {
                keySpans.addAll(toolHints);
                keySpans.add(Span.raw("  "));
            }
        }
        if (treePane != null) {
            keySpans.add(Span.raw("0").bold());
            keySpans.add(Span.raw(":Tree  "));
        }
        if (activePanel != null) {
            if (activePanel.subViewCount() > 1) {
                keySpans.add(Span.raw("1-" + activePanel.subViewCount()).bold());
            } else {
                keySpans.add(Span.raw("1").bold());
            }
            keySpans.add(Span.raw(":View  "));
        }
        if (treePane != null) {
            keySpans.add(Span.raw("Tab").bold());
            keySpans.add(Span.raw(":Focus  "));
            keySpans.add(Span.raw("\\").bold());
            keySpans.add(Span.raw(":Resize  "));
        }
        keySpans.add(Span.raw("h").bold());
        keySpans.add(Span.raw(":Help  "));
        keySpans.add(Span.raw("q").bold());
        keySpans.add(Span.raw(":Quit"));

        frame.renderWidget(Paragraph.from(Line.from(keySpans)), rows.get(2));
    }

    // ── Animation ───────────────────────────────────────────────────────────

    private void animateTreeWidth(int terminalWidth) {
        if (treePane == null) return;

        int fullWidth = Math.min(120, terminalWidth / 2);
        int narrowWidth = Math.min(30, terminalWidth / 3);

        int target =
                switch (targetTreeWidth) {
                    case FULL -> fullWidth;
                    case NARROW -> narrowWidth;
                    case HIDDEN -> 0;
                };

        // First render: jump to target immediately
        if (currentTreeCols < 0) {
            currentTreeCols = target;
            return;
        }

        // Animate toward target
        if (currentTreeCols != target) {
            int diff = target - currentTreeCols;
            int step = Math.max(2, Math.abs(diff) / 2);
            if (diff > 0) {
                currentTreeCols = Math.min(currentTreeCols + step, target);
            } else {
                currentTreeCols = Math.max(currentTreeCols - step, target);
            }
        }
    }

    // ── Help ────────────────────────────────────────────────────────────────

    private List<HelpOverlay.Section> buildHelp() {
        List<HelpOverlay.Section> sections = new ArrayList<>();

        // Tool-specific help first (most relevant to current context)
        if (activePanel != null) {
            sections.addAll(activePanel.helpSections());
        }

        // Module tree help
        if (treePane != null) {
            sections.addAll(treePane.helpSections());
        }

        // Shell-level help last
        sections.add(new HelpOverlay.Section(
                "Pilot Shell",
                List.of(
                        new HelpOverlay.Entry("Tab", "Toggle focus: module tree ↔ content pane"),
                        new HelpOverlay.Entry("0", "Focus module tree"),
                        new HelpOverlay.Entry("1-9", "Focus sub-view tab by number"),
                        new HelpOverlay.Entry("Enter", "Switch focus to content pane (from tree)"),
                        new HelpOverlay.Entry("\\", "Cycle left panel: full → narrow → hidden"),
                        new HelpOverlay.Entry("Alt+t/d/p/a/u/c/i/s", "Switch tool"),
                        new HelpOverlay.Entry("? / h", "Toggle this help screen"),
                        new HelpOverlay.Entry("q / Ctrl+C", "Quit"))));

        return sections;
    }
}
