# Development guide

How the plugin is put together, how to extend it, and how to release it. User-facing documentation is in the
[README](../README.md). The research behind the API choices is in [RESEARCH.md](RESEARCH.md).

## Setup

- JDK 21 and any OS. The Gradle 9 wrapper downloads Gradle, the IntelliJ Platform (IDEA 2024.3.6) and dependencies.
- Open the folder in IntelliJ IDEA as a Gradle project. *Run Plugin* is `./gradlew runIde`.

| Task | What it does |
|---|---|
| `./gradlew test` | Unit tests, plus `PluginSmokeTest`, which loads the plugin in a headless IDE |
| `./gradlew buildPlugin` | Installable ZIP in `build/distributions/` |
| `./gradlew runIde` | Sandbox IDE with the plugin |
| `./gradlew verifyPlugin` | Plugin Verifier against the IDEs in `build.gradle.kts` (`pluginVerification.ides`) |

CI (`.github/workflows/build.yml`) runs `test buildPlugin` on pushes to `main` and on pull requests, and uploads the ZIP.

## Architecture

```
            ┌──────────── IDE layer (platform APIs, EDT) ─────────────┐
 actions/ ─▶│ ui/Navigator   ui/*Panel   ui/CurrentIssueWidget        │
            │ settings/*Configurable   jira/CreateIssueDialog          │
            │ jira/CurrentIssueService (git branch → issue, TOPIC)     │
            └───────────────┬─────────────────────────────────────────┘
                            │ runInBackground { Clients.xxx().call() }
            ┌───────────────▼──────── plain Kotlin, unit-tested ──────┐
            │ JiraClient  ConfluenceClient  KibanaClient  GrafanaClient│
            │ KibanaUrls/Rison  GrafanaUrls  ConfluenceLinks  IssueKeys│
            │ CreateIssueFields  LinkedPagesCollector                  │
            │            RestClient (auth, errors, JSON)               │
            └───────────────┬─────────────────────────────────────────┘
                            │ HttpTransport (interface)
                 PlatformHttpTransport (IDE HttpRequests: proxy + Server Certificates)
                 FakeTransport (tests)
```

Key points:

- **Settings are split in two.** `AppSettings` is per developer (URLs, auth type, username, detected version) and
  `Credentials` holds secrets in PasswordSafe. `ProjectSettings` (`.idea/taskOnDemand.xml`) is shareable, so it has
  no URLs or secrets.
- **Version detection.** Test Connection stores the server version. `Clients.cachedVersion()` detects it lazily
  otherwise. Clients and URL builders take a `ServerVersion?` and choose the legacy or modern API/URL.
- **Current issue.** `CurrentIssueService` listens to `BranchChangeListener` and `GitRepository.GIT_REPO_CHANGE`,
  extracts the key with `IssueKeys.fromBranch`, fetches the issue silently in the background and publishes
  `CurrentIssueService.TOPIC` on the EDT. The status-bar widget, the Jira and Confluence tabs and the commit-message
  provider read its `snapshot`.
- **Async UI results.** Panels and dialogs number their requests and ignore answers that are not the newest one or
  that arrive after the panel/dialog is disposed (`JiraPanel`, `ConfluencePanel`, `CurrentIssueService`,
  `CreateIssueDialog`). Copy that pattern for new async UI.
- **Credentials on redirects.** `HttpRequests` follows GET redirects itself; `PlatformHttpTransport` only sets the
  `Authorization` header while the request stays on the configured scheme, host and port (`isSameOrigin`).
- **Errors.** `RestClient` maps 401/403, the Jira/Confluence CAPTCHA lock (`X-Seraph-LoginReason`) and non-JSON
  answers (SSO login pages) to `RemoteException` subclasses with user-readable messages (`Throwable.userMessage()`).

## Version-specific behaviour

| Server | Cut-off | Where |
|---|---|---|
| Jira | `createmeta/{project}/issuetypes` from 8.4; old `createmeta?expand=` removed in 9.0 | `JiraClient.issueTypes/fields` |
| Jira / Confluence | Personal access tokens (Bearer) from Jira 8.14 / Confluence 7.9 | `AuthType.BEARER` |
| Kibana | Discover route `/app/discover#/` from 7.10, before that `/app/kibana#/discover` | `KibanaUrls.discoverRoute` |
| Kibana | KQL from 6.3, Lucene before (unknown version → KQL, deliberately) | `KibanaUrls.queryLanguage` |
| Confluence | CQL search (`/rest/api/content/search`) from 5.7; earlier versions get a clear error | `ConfluenceClient.search` |
| Grafana | `/d/{uid}/{slug}` from 5.0, before that `/dashboard/db/{slug}` | `GrafanaUrls.dashboard` |
| Grafana | `/api/health` from 6.0, fallback `/api/frontend/settings` | `GrafanaClient.serverVersion` |

## Extending

**New REST call.** Add it to the relevant client using `rest.get/post` and the JSON helpers (`str("a.b")`, `arr()`),
and return a data class. Add a test with `FakeTransport` that covers each version branch.

**New action.** Subclass `ProjectAction` in `actions/Actions.kt`, register it in `plugin.xml` inside
`TaskOnDemand.Group` (Tools menu + status-bar popup) or another group, and add its ID to `PluginSmokeTest`.

**New project setting.** Add a `var` with a default to `ProjectSettings.State` (or `AppMapping`), a field in the
configurable (`isModified`/`apply`/`reset`), and check that `PluginSmokeTest.testProjectSettingsSerializeApplications`
still round-trips.

**New integration.** Add a `ServerKind` entry (with supported auth types), an `AppSettings.State` field, a client in
its own package, a branch in `Clients.test`, and UI where it's needed.

## Compatibility rules

- `sinceBuild = 243`, no `untilBuild`. Compile against 2024.3. Run `verifyPlugin`, which checks the oldest and newest IDE.
- Keep the verifier report clean: no deprecated, scheduled-for-removal or internal API usages. Typical traps:
  `SimpleListCellRenderer.create(...)` (subclass instead), and Kotlin interface-default bridges, which is why the build
  uses `jvmDefault = NO_COMPATIBILITY`.
- Kotlin `apiVersion` 2.0. The IDE provides the stdlib (`kotlin.stdlib.default.dependency = false`).
- kotlinx-serialization is bundled, so only the JSON tree API is used and no compiler plugin.

## Releasing

1. Bump `version` in `gradle.properties` and move the `[Unreleased]` entries in `CHANGELOG.md` to the new version.
2. `./gradlew test buildPlugin verifyPlugin`.
3. Publish `build/distributions/idea-task-on-demand-<version>.zip` to the internal plugin repository (below), or hand
   it out for *Install Plugin from Disk*.

### Internal plugin repository

Host the ZIP and an `updatePlugins.xml` on any internal HTTP server or artifact repository:

```xml
<plugins>
  <plugin id="io.github.elvismanchkin.taskondemand"
          url="https://tools.company.local/idea-plugins/idea-task-on-demand-0.1.0.zip"
          version="0.1.0">
    <idea-version since-build="243"/>
    <name>Task on Demand</name>
    <description>On-prem Jira, Confluence, Kibana and Grafana integration</description>
  </plugin>
</plugins>
```

Developers add `https://tools.company.local/idea-plugins/updatePlugins.xml` under *Settings | Plugins | ⚙ |
Manage Plugin Repositories*. After that the plugin installs from the Marketplace tab and updates automatically.
