# Pilot — Enhancement Roadmap

## Status

All core goals are implemented and working:

| Goal | Description | Status |
|------|-------------|--------|
| `pilot:search` | Search Maven Central interactively | Done |
| `pilot:tree` | Browse dependency tree with expand/collapse, conflicts, reverse path | Done |
| `pilot:pom` | POM viewer with Raw/Effective switching and origin detail | Done |
| `pilot:updates` | Show available dependency updates with patch/minor/major classification | Done |
| `pilot:dependencies` | Unused/undeclared dependency analysis | Done |
| `pilot:conflicts` | Conflict resolution with version pinning | Done |
| `pilot:audit` | License and security (CVE) dashboard | Done |

Supporting infrastructure:
- DomTrip exclusion support (PR: maveniverse/domtrip#162)
- TUI test infrastructure using TamboUI 0.2.0-SNAPSHOT test fixtures
- Unit tests for models and utilities, demo tests for all TUI screens

---

## Possible Enhancements

### Search

- **Search to POM flow** — after finding an artifact, press a key to add it as a dependency to the current project's POM via DomTrip
- **Saved searches / favorites** — bookmark frequently searched artifacts
- **Class name search** — search by class name (using Central's `fc` field) in addition to coordinates

### Tree

- **Scope filtering** — toggle visibility of test/provided/system scope dependencies
- **Reactor / multi-module view** — show inter-module dependencies using `projectDependencyGraph()`
- **Tree diff** — compare dependency trees between two versions of the project
- **Dependency size** — show download size / total transitive size per node
- **Duplicate detection** — highlight same artifact pulled in via different paths

### POM Viewer

- **Linked scrolling** — sync scroll position between Raw and Effective views when switching with Tab
- **Better origin tracking** — use Maven's `InputLocation` data to precisely map effective POM elements back to their source (parent POM, profile, BOM) instead of simple text search
- **Property resolution highlighting** — in Raw view, highlight `${property}` references and show resolved values inline
- **Inline editing** — allow editing values directly in the TUI and writing back via DomTrip
- **Profile support** — show which profiles are active and their contributions to the effective POM

### Updates

- **Property-aware updates** — when a version is defined via `${property}`, update the property instead of the dependency version element
- **BOM-aware updates** — detect versions managed by BOMs and suggest BOM upgrades instead
- **Diff preview** — before applying updates, show the exact POM diff that will be written
- **Batch apply** — `Space` to toggle individual updates, `a` to select all, `Enter` to apply selected
- **Version history** — show release timeline / changelog link for selected dependency
- **Update policies** — configurable rules (e.g., never go to next major, skip pre-releases)

### Analyze

- **Quick fix with DomTrip** — `Enter` on unused dependency to remove from POM, or on undeclared to add
- **Ignore list** — `i` to add to ignored dependencies (persist in plugin configuration)
- **Scope suggestions** — suggest moving compile-scope deps to test if only used in test sources

### Conflicts

- **Add exclusion** — use DomTrip exclusion support to add `<exclusion>` on the transitive path
- **Bump dependency** — offer to update the conflicting direct dependency to resolve the conflict
- **Convergence report** — show whether the dependency graph converges (all versions of same GA agree)
- **Integration with tree** — make conflicts a filter/mode within the tree TUI rather than a separate screen

### Audit

- **Export** — generate reports in CSV, JSON, or NOTICE file format
- **License compatibility matrix** — flag potentially incompatible licenses (e.g., GPL in an Apache-licensed project)
- **CVE severity filtering** — filter by CVSS score or severity level
- **Remediation suggestions** — for CVEs, suggest the minimum version that fixes the vulnerability
- **SBOM generation** — produce CycloneDX or SPDX output

### Cross-cutting

- **Screen abstraction** — common interface for TUI screens to reduce boilerplate across the 7+ screens
- **Toolbox integration** — replace mock/simple implementations with real Maveniverse Toolbox APIs for dependency resolution, tree operations, and conflict detection
- **Session recording** — use TamboUI's `RecordingBackend` to produce `.cast` files for documentation
- **Terminal size handling** — graceful layout adaptation for very small terminals
- **Mouse support** — click to select tree nodes, scroll with mouse wheel
- **Configuration** — user preferences for colors, key bindings, default filters (via `.mvn/pilot.properties` or plugin config)
- **Help screen** — `?` key to show context-sensitive help overlay

---

## Testing Plan

Current test coverage:
- `VersionComparatorTest` — 14 unit tests for version classification
- `XmlTreeModelTest` — 10 unit tests for XML parsing and tree operations
- `PomDemoTest` — TUI integration test for POM viewer
- `DependenciesDemoTest` — TUI integration test for dependency analysis
- `ConflictsDemoTest` — TUI integration test for conflict resolution
- `UpdatesDemoTest` — TUI integration test for updates screen

Future testing:
- Add `TreeDemoTest` and `SearchDemoTest` TUI integration tests
- Add `AuditDemoTest` for the audit screen
- Integration tests that run against real Maven projects (requires project context)
- Scripted demo recordings for documentation using TamboUI recording infrastructure
