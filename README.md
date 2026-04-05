# Pilot

**Interactive TUI for Maven** — search, browse, and manage dependencies from the terminal.

Pilot is a Maven plugin **and** a standalone CLI with a REPL shell that replaces hard-to-read CLI output with interactive, keyboard-driven terminal interfaces. Navigate dependency trees, check for updates, resolve conflicts, and edit your POM — all without leaving the terminal.

## Quick Start

### Standalone CLI (recommended)

Build the fat jar, then run directly:

```bash
# Build
mvn install -DskipTests

# REPL mode — interactive shell with tab completion and history
java -jar pilot-cli/target/pilot-cli-*.jar

# One-shot commands
java -jar pilot-cli/target/pilot-cli-*.jar search guava
java -jar pilot-cli/target/pilot-cli-*.jar tree
java -jar pilot-cli/target/pilot-cli-*.jar updates
```

Inside the REPL you get a `pilot>` prompt (or `pilot [my-app]>` when a `pom.xml` is detected).
All commands are available with tab completion, history (`Up`/`Down`), and built-in `help`.

### Maven Plugin

> **Tip:** To use the short `mvn pilot:search` syntax instead of the full `mvn eu.maveniverse.maven.plugins:pilot:search`, add the plugin group to your `~/.m2/settings.xml`:
> ```xml
> <pluginGroups>
>   <pluginGroup>eu.maveniverse.maven.plugins</pluginGroup>
> </pluginGroups>
> ```

```bash
# Search Maven Central
mvn pilot:search

# Browse the dependency tree of your project
mvn pilot:tree

# Check for dependency and plugin updates
mvn pilot:updates

# View POM with syntax highlighting
mvn pilot:pom

# Analyze dependency hygiene
mvn pilot:analyze

# Detect and resolve version conflicts
mvn pilot:conflicts

# License and vulnerability audit
mvn pilot:audit
```

## Commands

| Command | Plugin Goal | Description |
|---------|-------------|-------------|
| `search` | `pilot:search` | Search Maven Central interactively with async results, version cycling, and POM info display |
| `tree` | `pilot:tree` | Browse the resolved dependency tree with expand/collapse, conflict highlighting, and reverse path lookup |
| `pom` | `pilot:pom` | View raw and effective POM with syntax highlighting, collapsible XML nodes, and origin tracking |
| `updates` | `pilot:updates` | Check for dependency and plugin updates with patch/minor/major classification and batch POM editing |
| `analyze` | `pilot:analyze` | Find unused declared and used-but-undeclared dependencies, fix with one keypress |
| `conflicts` | `pilot:conflicts` | Detect version conflicts across the dependency tree and pin versions via `dependencyManagement` |
| `audit` | `pilot:audit` | License overview and CVE lookup (via OSV.dev) for all transitive dependencies |

## Features

### Search (`search`)

Type to search Maven Central. Results load asynchronously with pagination. Use `Left`/`Right` arrows to cycle through available versions. Bottom bar shows POM metadata (name, license, organization, date).

**Keys:** `Enter` — focus results, `Left`/`Right` — cycle versions, `Esc` — back to search, `q` — quit

### Dependency Tree (`tree`)

Interactive collapsible tree view of all resolved dependencies. Conflicts are highlighted with `⚠` markers. Filter by name, jump between conflicts, and trace any dependency back to the root with reverse path mode.

![pilot:tree](docs/images/tree.svg)

**Keys:** `←→` — expand/collapse, `↑↓` — navigate, `/` — filter, `c` — next conflict, `r` — reverse path, `e/w` — expand/collapse all

### POM Viewer (`pom`)

Syntax-highlighted XML viewer with two switchable modes: **Raw POM** shows your `pom.xml` as-is with line numbers, **Effective POM** shows the fully resolved model with origin annotations. When a line has a known origin, a detail pane shows the relevant source lines.

In the CLI REPL, press `o` to open the POM in the built-in nano editor, then return to the viewer with updated content.

![pilot:pom](docs/images/pom.svg)

**Keys:** `Tab` — switch Raw/Effective, `←→` — expand/collapse, `/` — search, `n/N` — next/prev match, `e/w` — expand/collapse all, `o` — edit (CLI only)

### Dependency & Plugin Updates (`updates`)

Scans all dependencies **and plugins** for newer versions. Updates are color-coded: green (patch), yellow (minor), red (major). Each entry shows its source (`declared`, `managed`, `plugin`, `plugin-m`) and Maven property expression if applicable. Select individually or batch-select, then apply — Pilot edits your POM directly using lossless XML editing that preserves formatting and comments.

![pilot:updates](docs/images/updates.svg)

**Keys:** `Space` — toggle, `a` — select all, `n` — deselect all, `Enter` — apply, `1-4` — filter by type

### Dependency Analysis (`analyze`)

Two views: **Declared** dependencies (remove unused ones) and **Transitive** dependencies (promote undeclared ones). One-keypress fixes modify your POM directly.

![pilot:analyze](docs/images/analyze.svg)

**Keys:** `Tab` — switch view, `d` — remove declared, `a` — add transitive, `Enter` — fix selected

### Version Mediation (`conflicts`)

Groups dependencies by `groupId:artifactId` and shows where different versions are requested. Expand any conflict to see the full dependency paths. Pin a version to `dependencyManagement` with one keypress.

![pilot:conflicts](docs/images/conflicts.svg)

**Keys:** `Enter/Space` — toggle details, `p` — pin version, `↑↓` — navigate

### License & Security Audit (`audit`)

Two views: **Licenses** shows all transitive dependencies with their licenses (color-coded by permissiveness), **Vulnerabilities** queries OSV.dev for known CVEs. Data loads asynchronously.

**Keys:** `Tab` — switch view, `↑↓` — navigate

## How POM Editing Works

Pilot uses [DomTrip](https://github.com/maveniverse/domtrip) for all POM modifications. DomTrip is a lossless XML editor that preserves your formatting, comments, whitespace, and element ordering. When Pilot adds, removes, or updates a dependency, the rest of your POM stays exactly as you wrote it.

## Project Structure

```
pilot/               (reactor parent)
  pilot-core/        (TUI screens, models, clients — no Maven dependency)
  pilot-cli/         (standalone CLI + REPL shell with embedded Maven)
  pilot-plugin/      (thin Maven plugin wrappers)
```

- **pilot-core** contains all TUI classes and is Maven-agnostic
- **pilot-cli** embeds Maven via Plexus and provides a JLine 4 REPL shell
- **pilot-plugin** provides `mvn pilot:*` goals that delegate to pilot-core

## Requirements

- **Java** 17+
- **Maven** 3.6.3+ (for plugin usage)
- A terminal that supports ANSI escape codes (most modern terminals)

## Building

```bash
mvn install -DskipTests
```

The fat jar is produced at `pilot-cli/target/pilot-cli-*.jar`.

## Technology

- **[TamboUI](https://github.com/maveniverse/tamboui)** — Terminal UI framework (Rust Ratatui-inspired, Java-native)
- **[DomTrip](https://github.com/maveniverse/domtrip)** — Lossless XML/POM editor
- **[JLine 4](https://github.com/jline/jline3)** — REPL shell with tab completion, history, and built-in nano editor
- **Maven Embedder** — Embedded Maven for standalone CLI operation
- **OSV.dev** — Open Source Vulnerability database for security auditing

## License

[Apache License 2.0](LICENSE)
