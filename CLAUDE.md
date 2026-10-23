# CLAUDE.md

IntelliJ IDEA plugin ("Task on Demand") that connects the IDE to **on-premise, often legacy** Jira, Confluence,
Kibana and Grafana. Cloud editions are explicitly out of scope, so don't add Cloud-only APIs (Jira REST v3, Confluence v2,
accountId users, Grafana Cloud).

- User docs: `README.md`. Developer notes: `docs/DEVELOPMENT.md`. Background and API research: `docs/RESEARCH.md`.
- Changes: record user-visible changes in `CHANGELOG.md` under `[Unreleased]`.

## Commands

```bash
./gradlew test            # unit tests + headless IDE smoke test (~20 s once cached)
./gradlew buildPlugin     # build/distributions/idea-task-on-demand-<version>.zip
./gradlew verifyPlugin    # Plugin Verifier vs oldest (2024.3) and newest (2026.2) IDE. Slow, downloads ~1 GB on first run.
./gradlew runIde          # sandbox IDE (needs a display)
```

Before pushing, run `./gradlew test buildPlugin`. Run `verifyPlugin` after touching platform APIs (UI, actions,
services, plugin.xml). It must report `Compatible` with no deprecated/internal usages.

Toolchain: JDK 21, Gradle 9 wrapper, IntelliJ Platform Gradle Plugin 2.x, Kotlin 2.2 compiler with
`apiVersion`/`languageVersion` 2.0, because IDEA 2024.3 bundles the Kotlin 2.0 stdlib. Don't use newer stdlib APIs.

### Sandbox note (cloud sessions)

Maven Central may answer HTTP 429 here. If dependency resolution fails with 429, add a **local-only** init script
`~/.gradle/init.d/central-mirror.gradle` that puts `https://maven-central.storage-download.googleapis.com/maven2/`
first in `pluginManagement` and `dependencyResolutionManagement` repositories. Never commit a mirror into the build.

## Layout

```
src/main/kotlin/io/github/elvismanchkin/taskondemand/
  core/        HttpTransport (seam) + PlatformHttpTransport (IDE HttpRequests: proxy + trusted certs), RestClient,
               ServerVersion, AuthType/ServerKind, Clients (factories + connection test), runInBackground, Notifier
  settings/    AppSettings (per developer, app-level) + Credentials (PasswordSafe), ProjectSettings (.idea/taskOnDemand.xml,
               shareable, no URLs or secrets), settings pages, AppMappingDialog
  jira/        JiraClient, IssueKeys (branch → key), CreateIssueFields (create-meta → editors/JSON), CurrentIssueService,
               CreateIssueDialog, IssueNavigationSync, IssueCommitMessageProvider
  confluence/  ConfluenceClient, ConfluenceLinks (URL parsing, tiny links), LinkedPagesCollector
  kibana/      KibanaClient, Rison, KibanaUrls (Discover links)
  grafana/     GrafanaClient, GrafanaUrls
  ui/          tool window tabs (JiraPanel, ObservabilityPanel, ConfluencePanel), CurrentIssueWidget, Navigator
  actions/     AnActions registered in plugin.xml
src/main/resources/META-INF/plugin.xml   all extensions and actions
src/test/kotlin/...                       JUnit 4. FakeTransport for clients, PluginSmokeTest (BasePlatformTestCase)
```

## Conventions and rules

- **Legacy-version awareness is the core feature.** Anything whose API or URL differs by server version goes through
  `ServerVersion` (detected via Test Connection or lazily, cached in `AppSettings.ServerState.detectedVersion`). If
  the version is unknown, pick the legacy-compatible form or try the modern one and fall back on 404 (see
  `JiraClient.withCreateMetaFallback`). Document new version cut-offs in a KDoc comment next to the branch.
- **Keep REST clients and URL builders free of platform classes.** They take a `ServerConnection` + `HttpTransport` and
  return plain data classes, so they're unit-testable with `FakeTransport`. Each new endpoint or version branch gets a
  test.
- **Async UI:** ignore stale answers. Number requests (`generation`) and drop results that aren't the newest or that
  arrive after dispose. Use `runInBackground` with a null title and an `onError` that shows the error in the
  component for refreshes the user didn't trigger (branch changes); balloons only for explicit actions.
- **Threading:** clients block. Never call them on the EDT. Use `runInBackground(project, title|null, work, onError,
  onSuccess)`: callbacks run on the EDT, and a null title means silent. `AppSettings.connection()` reads the password safe,
  so it's off-EDT too. `CurrentIssueService` publishes `TOPIC` on the EDT.
- **Secrets** only via `Credentials` (PasswordSafe), and never on the EDT: the settings page loads them with
  `runInBackground` and saves with `Credentials.saveAsync`. Never put them in settings XML or logs. Never send the
  `Authorization` header to another origin (see `isSameOrigin` in `PlatformHttpTransport`). Project settings must stay
  free of URLs/credentials because teams commit them.
- **HTTP** goes through `PlatformHttpTransport` (IDE proxy + Server Certificates). Don't add OkHttp/Ktor.
- **JSON** uses the kotlinx-serialization tree API only (`parseJson`, `str("a.b")`, `arr()`). No `@Serializable`/compiler
  plugin, because the bundled copy must not clash with the IDE's. `RestClient` rejects bodies that don't start with `{`/`[`,
  because the tree parser accepts bare literals and an SSO HTML page would otherwise "parse".
- **Platform API compatibility:** since-build is 243 with no until-build. Avoid APIs that are deprecated or scheduled for
  removal in the newest IDE (e.g. use `object : SimpleListCellRenderer<T>() { customize(...) }`, not
  `SimpleListCellRenderer.create`). `jvmDefault = NO_COMPATIBILITY` is required, because otherwise Kotlin bridge methods
  show up as internal-API usages in the verifier.
- **UI** uses Kotlin UI DSL v2 for settings and plain Swing + `toolbar()/panelAction()` helpers (`ui/UiUtil.kt`) for
  panels. New user-facing actions go into the `TaskOnDemand.Group` group in plugin.xml so they appear in Tools menu and
  status-bar popup. No default keymap shortcuts (the obvious ones clash with IDEA defaults).
- **Style:** Kotlin, 4-space indent, ~120-char lines, KDoc on non-obvious decisions (especially version cut-offs).
  Match the surrounding code.

## Known gaps / unverified

- Not yet tested against real servers. The most fragile parts are Confluence tiny-link decoding (`ConfluenceLinks`),
  Kibana Discover URLs per version, and Jira create-meta for unusual custom field types.
- Not implemented: in-IDE log viewer, Grafana panel listing, create branch from issue, comments, SSO/JCEF login.
- Deliberate trade-offs from the code review (revisit if real servers disagree):
  - Kibana with an unknown version uses KQL, not Lucene (KDoc on `KibanaUrls.queryLanguage`).
  - Confluence 5.5–5.6 has no search fallback: CQL needs 5.7, older servers get a clear error. A fallback would use
    `/rest/prototype/1/search`. The Confluence version is never detected (`Clients.cachedVersion` returns null).
  - Jira datetime fields are entered as `yyyy-MM-dd [HH:mm]` and sent as UTC (`CreateIssueFields.toJiraDateTime`).
  - Branch keys without configured project keys skip a small stoplist (`UTF`, `JDK`, `RFC`, …) in `IssueKeys`. With
    no keys set, an unverified key still prefixes the commit message even if Jira returns 404.
  - `IssueNavigationSync` recognises its own rules by shape (`…/browse/$0` + our key pattern), so a hand-made rule
    with the same shape is replaced too.
  - Create Issue is blocked until create-meta loads; if Jira can't return it, no issue can be created from the IDE.
- Not covered by tests: the UI layer (panels, settings page, dialogs, async ordering), `PlatformHttpTransport` against
  real redirects, and `Credentials`. Only `PluginSmokeTest` loads the plugin in a headless IDE.
- `.claude/settings.json` pre-approves the Gradle tasks above for Claude Code; keep it to build/test commands.
