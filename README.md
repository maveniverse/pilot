# Pilot

**Interactive TUI for Maven** -- search, browse, and manage dependencies from the terminal.

Pilot is a Maven plugin that replaces hard-to-read CLI output with interactive, keyboard-driven terminal interfaces. Navigate dependency trees, check for updates, resolve conflicts, and edit your POM -- all without leaving the terminal.

## Goals

| Goal | Description |
|------|-------------|
| `pilot:pilot` | Interactive launcher -- pick a module and tool from the TUI (recommended entry point) |
| `pilot:search` | Search Maven Central interactively with async results and version cycling |
| `pilot:tree` | Browse the resolved dependency tree with expand/collapse, conflict highlighting, scope filtering, and reverse path lookup |
| `pilot:pom` | View raw and effective POM with syntax highlighting, collapsible XML nodes, and origin tracking |
| `pilot:dependencies` | Bytecode-level analysis of declared vs used dependencies with ASM, SPI detection, and member-level references |
| `pilot:updates` | Check for dependency updates with patch/minor/major classification and batch POM editing |
| `pilot:conflicts` | Detect version conflicts across the dependency tree and pin versions via `dependencyManagement` |
| `pilot:audit` | License overview and CVE lookup (via OSV.dev) for all transitive dependencies |
| `pilot:align` | Detect and align dependency conventions (version style, property naming) across POMs |

## Quick Start

> **Tip:** To use the short `mvn pilot:tree` syntax instead of the full `mvn eu.maveniverse.maven.plugins:pilot:tree`, add the plugin group to your `~/.m2/settings.xml`:
> ```xml
> <pluginGroups>
>   <pluginGroup>eu.maveniverse.maven.plugins</pluginGroup>
> </pluginGroups>
> ```

```bash
# Launch the interactive pilot (recommended)
mvn pilot:pilot

# Search Maven Central
mvn pilot:search

# Browse the dependency tree
mvn pilot:tree

# Check for dependency updates
mvn pilot:updates

# View POM with syntax highlighting
mvn pilot:pom

# Analyze dependency usage (run 'mvn compile' first for bytecode analysis)
mvn compile pilot:dependencies

# Detect and resolve version conflicts
mvn pilot:conflicts

# License and vulnerability audit
mvn pilot:audit

# Align dependency conventions
mvn pilot:align
```

## Multi-Module Reactor Support

In multi-module builds, Pilot automatically detects the reactor and shows a **module picker** -- an interactive tree view mirroring the Maven reactor hierarchy. Select a module, then choose a tool. Your selection is preserved when returning from a tool.

Some tools operate reactor-wide (updates, conflicts, audit), analyzing all modules at once. Others are per-module (tree, dependencies, pom). When selecting a parent/aggregator module, the tool picker filters out tools that don't apply (e.g., bytecode analysis). For **align**, selecting a parent module automatically aligns all child modules in one go.

## Features

### Pilot Launcher (`pilot:pilot`)

The main entry point. In a reactor, shows the module picker first, then the tool picker. In a single-module project, goes directly to the tool picker. All tools below are accessible from here, including search.

### Search (`pilot:search`)

Type to search Maven Central. Results load asynchronously with pagination. Use `Left`/`Right` arrows to cycle through available versions. Bottom bar shows POM metadata (name, license, organization, date).

<video src="https://github.com/maveniverse/pilot/releases/download/demo-assets/search.mp4" controls width="100%"></video>

**Keys:** `Enter` -- focus results, `Left`/`Right` -- cycle versions, `Esc` -- back to search, `q` -- quit

### Dependency Tree (`pilot:tree`)

Interactive collapsible tree view of all resolved dependencies. Conflicts are highlighted with markers. Filter by name, jump between conflicts, and trace any dependency back to the root with reverse path mode. Toggle scope (`s`) to cycle between compile, runtime, and test views.

<video src="https://github.com/maveniverse/pilot/releases/download/demo-assets/dependency-tree.mp4" controls width="100%"></video>

**Keys:** `<>` -- expand/collapse, `jk` -- navigate, `/` -- filter, `c` -- next conflict, `r` -- reverse path, `s` -- cycle scope, `e/w` -- expand/collapse all

### POM Viewer (`pilot:pom`)

Syntax-highlighted XML viewer with two switchable modes: **Raw POM** shows your `pom.xml` as-is, **Effective POM** shows the fully resolved model with origin annotations. When a line has a known origin, a detail pane shows the relevant source lines from the parent POM.

<video src="https://github.com/maveniverse/pilot/releases/download/demo-assets/pom-viewer.mp4" controls width="100%"></video>

**Keys:** `Tab` -- switch Raw/Effective, `<>` -- expand/collapse, `/` -- search, `n/N` -- next/prev match, `e/w` -- expand/collapse all

### Dependency Analysis (`pilot:dependencies`)

Two views: **Declared** dependencies and **Transitive** dependencies. Uses ASM bytecode analysis to determine which dependencies are actually referenced in code, with member-level detail (method calls, field accesses). Detects SPI/ServiceLoader usage -- dependencies providing `META-INF/services` are recognized even without direct class references.

A details pane shows per-class member references and SPI service interfaces. Promote transitive dependencies to declared, remove unused ones, or change scope -- all with single keypresses that edit your POM via DomTrip.

Run `mvn compile` before this goal for full bytecode analysis. A warning banner appears when classes are not compiled.

<video src="https://github.com/maveniverse/pilot/releases/download/demo-assets/dependencies.mp4" controls width="100%"></video>

**Keys:** `Tab` -- switch Declared/Transitive, `d` -- remove declared, `a` -- add transitive, `s` -- change scope, `h` -- help

### Dependency Updates (`pilot:updates`)

Scans all dependencies for newer versions. Updates are color-coded: green (patch), yellow (minor), red (major). Select individually or batch-select, then apply -- Pilot edits your POM directly using lossless XML editing that preserves formatting and comments. In reactor builds, shows a reactor-wide view with per-module breakdown.

<video src="https://github.com/maveniverse/pilot/releases/download/demo-assets/updates.mp4" controls width="100%"></video>

**Keys:** `Space` -- toggle, `a` -- select all, `n` -- deselect all, `Enter` -- apply, `1-4` -- filter by type

### Conflict Resolution (`pilot:conflicts`)

Groups dependencies by `groupId:artifactId` and shows where different versions are requested. Toggle between actual conflicts only or all dependency groups (`a`). Expand any conflict to see the full dependency paths. Pin a version to `dependencyManagement` with one keypress.

<video src="https://github.com/maveniverse/pilot/releases/download/demo-assets/conflicts.mp4" controls width="100%"></video>

**Keys:** `Enter/Space` -- toggle details, `p` -- pin version, `a` -- toggle show all, `jk` -- navigate

### License & Security Audit (`pilot:audit`)

Two views: **Licenses** shows all transitive dependencies with their licenses (color-coded by permissiveness), **Vulnerabilities** queries OSV.dev for known CVEs. Data loads asynchronously.

<video src="https://github.com/maveniverse/pilot/releases/download/demo-assets/audit.mp4" controls width="100%"></video>

**Keys:** `Tab` -- switch view, `jk` -- navigate

### Convention Alignment (`pilot:align`)

Detects the project's current dependency conventions (inline vs managed versions, literal vs property references, property naming patterns) and lets you choose a target convention. Preview the diff before applying. In reactor builds, understands the parent POM hierarchy -- managed dependencies are written to the correct parent POM while child modules get version-less references. Selecting a parent module automatically applies alignment across all child modules in one go.

**Keys:** `jk` -- navigate options, `<>/Enter` -- cycle values, `p` -- preview diff, `w` -- apply, `h` -- help

## How POM Editing Works

Pilot uses [DomTrip](https://github.com/maveniverse/domtrip) for all POM modifications. DomTrip is a lossless XML editor that preserves your formatting, comments, whitespace, and element ordering. When Pilot adds, removes, or updates a dependency, the rest of your POM stays exactly as you wrote it.

## Requirements

- **Maven** 3.6.3+
- **Java** 17+
- A terminal that supports ANSI escape codes (most modern terminals)

## Building

```bash
mvn install
```

## Technology

- **[TamboUI](https://github.com/maveniverse/tamboui)** -- Terminal UI framework (Rust Ratatui-inspired, Java-native)
- **[DomTrip](https://github.com/maveniverse/domtrip)** -- Lossless XML/POM editor
- **[ASM](https://asm.ow2.io/)** -- Bytecode analysis for dependency usage detection
- **Maven Plugin API** -- Standard Maven plugin infrastructure
- **OSV.dev** -- Open Source Vulnerability database for security auditing

## License

[Apache License 2.0](LICENSE)
