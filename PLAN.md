# Pilot -- Enhancement Roadmap

## Status

All core goals are implemented and working:

| Goal | Description | Status |
|------|-------------|--------|
| `pilot:pilot` | Interactive launcher with module/tool picker | Done |
| `pilot:search` | Search Maven Central interactively | Done |
| `pilot:tree` | Browse dependency tree with expand/collapse, conflicts, reverse path, scope toggle | Done |
| `pilot:pom` | POM viewer with Raw/Effective switching and origin detail | Done |
| `pilot:updates` | Show available dependency updates with patch/minor/major classification | Done |
| `pilot:dependencies` | Bytecode-level dependency analysis with ASM, SPI detection, member references | Done |
| `pilot:conflicts` | Conflict resolution with version pinning, show-all toggle | Done |
| `pilot:audit` | License and security (CVE) dashboard | Done |
| `pilot:align` | Convention alignment with reactor-aware parent POM editing | Done |

Supporting infrastructure:
- ASM-based bytecode scanner with member-level reference tracking
- SPI/ServiceLoader detection for runtime-discovered dependencies
- DomTrip for lossless POM editing (exclusions, dependency management, properties)
- Reactor-aware module picker with batch/subtree execution
- TUI test infrastructure using TamboUI test fixtures
- Unit tests for models, utilities, and TUI screens

---

## Possible Enhancements

### Search

- **Search to POM flow** -- after finding an artifact, press a key to add it as a dependency to the current project's POM via DomTrip
- **Saved searches / favorites** -- bookmark frequently searched artifacts
- **Class name search** -- search by class name (using Central's `fc` field) in addition to coordinates

### Tree

- ~~**Scope filtering** -- toggle visibility of test/provided/system scope dependencies~~ **Done** (`s` key)
- **Reactor / multi-module view** -- show inter-module dependencies using `projectDependencyGraph()`
- **Tree diff** -- compare dependency trees between two versions of the project
- **Dependency size** -- show download size / total transitive size per node
- **Duplicate detection** -- highlight same artifact pulled in via different paths

### POM Viewer

- **Linked scrolling** -- sync scroll position between Raw and Effective views when switching with Tab
- **Property resolution highlighting** -- in Raw view, highlight `${property}` references and show resolved values inline
- **Inline editing** -- allow editing values directly in the TUI and writing back via DomTrip
- **Profile support** -- show which profiles are active and their contributions to the effective POM

### Updates

- **Property-aware updates** -- when a version is defined via `${property}`, update the property instead of the dependency version element
- **BOM-aware updates** -- detect versions managed by BOMs and suggest BOM upgrades instead
- **Diff preview** -- before applying updates, show the exact POM diff that will be written
- **Version history** -- show release timeline / changelog link for selected dependency
- **Update policies** -- configurable rules (e.g., never go to next major, skip pre-releases)

### Dependencies

- ~~**Bytecode analysis** -- ASM-based scanning with member-level references~~ **Done**
- ~~**SPI detection** -- recognize ServiceLoader, Sisu, Spring service providers~~ **Done**
- ~~**Compilation warning** -- show banner when target/classes is missing~~ **Done**
- **Ignore list** -- `i` to add to ignored dependencies (persist in plugin configuration)
- **Scope suggestions** -- suggest moving compile-scope deps to test if only used in test sources

### Conflicts

- ~~**Show-all toggle** -- switch between actual conflicts and all dependency groups~~ **Done** (`a` key)
- **Add exclusion** -- use DomTrip exclusion support to add `<exclusion>` on the transitive path
- **Bump dependency** -- offer to update the conflicting direct dependency to resolve the conflict
- **Convergence report** -- show whether the dependency graph converges (all versions of same GA agree)

### Align

- ~~**Reactor-aware** -- detect parent POM dependency management, cross-POM editing~~ **Done**
- ~~**Batch mode** -- run alignment across all modules via module picker~~ **Done**
- **BOM import alignment** -- detect and align BOM import conventions
- **Profile-aware alignment** -- handle dependencies declared inside profiles

### Audit

- **Export** -- generate reports in CSV, JSON, or NOTICE file format
- **License compatibility matrix** -- flag potentially incompatible licenses (e.g., GPL in an Apache-licensed project)
- **CVE severity filtering** -- filter by CVSS score or severity level
- **Remediation suggestions** -- for CVEs, suggest the minimum version that fixes the vulnerability
- **SBOM generation** -- produce CycloneDX or SPDX output

### Cross-cutting

- ~~**Help screen** -- `h` key to show context-sensitive help overlay~~ **Done** (all screens)
- ~~**Module picker** -- reactor-aware module selection with tree view~~ **Done**
- ~~**Batch execution** -- run tools on all modules or subtrees~~ **Done** (`r`/`t` keys)
- **Screen abstraction** -- common interface for TUI screens to reduce boilerplate
- **Toolbox integration** -- replace implementations with Maveniverse Toolbox APIs
- **Session recording** -- use TamboUI's `RecordingBackend` to produce `.cast` files for documentation
- **Terminal size handling** -- graceful layout adaptation for very small terminals
- **Mouse support** -- click to select tree nodes, scroll with mouse wheel
- **Configuration** -- user preferences for colors, key bindings, default filters (via `.mvn/pilot.properties` or plugin config)
