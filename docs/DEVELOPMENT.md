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

## Gotchas and rules

Lessons from building and reviewing 0.1.0. Each one cost a build failure, a verifier finding or a review bug.

### Build and toolchain

- **IntelliJ Platform Gradle Plugin 2.19+ needs Gradle 9.** On Gradle 8 the build fails during configuration. Keep the
  wrapper and the plugin version in step, and the Kotlin compiler new enough for Gradle 9 (2.2.x). The language and
  API level stay at 2.0 because IDEA 2024.3 ships the Kotlin 2.0 stdlib.
- **`settings.gradle.kts` needs `import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform`** for
  `repositories { intellijPlatform { defaultRepositories() } }`. Without it the name is unresolved.
- **`kotlin.stdlib.default.dependency=false`.** The IDE provides the stdlib. Bundling another copy causes class
  conflicts at runtime that tests don't catch.
- **kotlinx-serialization is bundled with `exclude(group = "org.jetbrains.kotlin")`**, and only the tree API is used. The
  compiler plugin and `@Serializable` would generate code tied to a serialization version that can clash with the IDE's.
- **Cloud sandboxes:** Maven Central may return HTTP 429. Use the local-only mirror init script described in
  `CLAUDE.md`; never commit a mirror.

### Plugin Verifier

- Run `verifyPlugin` after any platform API change. Treat any deprecated, scheduled-for-removal, experimental or
  internal usage as a failure, not a warning: with no `until-build`, today's deprecation is a future crash.
- **Kotlin interface default methods** produce bridge methods that the verifier reports as internal API overrides.
  `jvmDefault = NO_COMPATIBILITY` in `build.gradle.kts` prevents that. Don't remove it.
- **`SimpleListCellRenderer.create(...)` is scheduled for removal.** Subclass `SimpleListCellRenderer<T>` and override
  `customize` instead.
- The verifier logs `ERROR … Unable to read documented IntelliJ API incompatible changes` when it can't download
  JetBrains' list of documented changes (no network, or a 403). It is harmless; only the final
  `Compatible` / problem list matters.
- The first run downloads both IDEs (~1 GB). Later runs take a few minutes, mostly for the newest IDE.

### HTTP and servers

- **`HttpRequests` follows GET redirects itself** (it turns off `HttpURLConnection` redirects and loops on `Location`),
  and it runs the `tuner` again for every hop, without checking the host. That is why `PlatformHttpTransport` decides
  per hop whether to set `Authorization` (`isSameOrigin`). Anything that adds headers must go through the same check.
  This was found by reading the platform bytecode (`javap -c` on `HttpRequests` in `lib/app-client.jar`); re-check it
  when raising the minimum IDE version.
- **The JSON tree parser accepts bare literals.** `parseJson("true")` or an HTML page starting with a word can "parse",
  so `RestClient` only parses bodies starting with `{` or `[`. SSO proxies answer with HTTP 200 and an HTML login page.
- **Atlassian CAPTCHA lock:** after failed Basic logins Jira/Confluence set `X-Seraph-LoginReason: AUTHENTICATION_DENIED`
  and refuse even correct passwords until the user logs in through the browser. Never retry automatically.
- **Read the response charset.** Old Jira/Confluence error pages can be ISO-8859-1; `charsetOf` reads `Content-Type`.
- **Encode path segments** (`RestClient.encode`) for anything user-supplied: issue keys from custom branch patterns,
  Grafana uids, project keys.
- **CQL `~` goes through the Lucene query parser.** Quote exact values as a phrase (`ConfluenceClient.phrase`), or
  `PROJ-123` matches any page containing "123", and `(`, `:` or `/` in pasted text cause a 400.
- **Jira issue types are global.** "Task" has the same id in every project, so checking only the issue type does not
  prove that loaded create-meta belongs to the selected project.
- **A 404 isn't always "old version".** Jira 8.4+ also answers 404 with an `errorMessages` body for an unknown project.
  Only fall back to the legacy API when the 404 is not Jira's own error (`JiraClient.withCreateMetaFallback`).
- **8.4+ create-meta is paged** (`startAt`, `maxResults`, `isLast`/`total`). Read every page.

### Threading and lifecycle

- **Never touch the password safe on the EDT.** KeePass or a locked OS keychain can block or prompt. This includes
  `AppSettings.connection()` and settings pages (`Credentials.saveAsync`, `getAfterPendingWrites`). Writes go through
  one serial executor so a read right after Apply sees the new secret.
- **Rethrow `ProcessCanceledException`.** `runCatching` swallows it. `runInBackground` rethrows it in titled tasks and
  drops it in silent ones; do the same in any new background code, or a cancelled task shows up as an error.
- **No `ModalityState.any()` for code that changes state.** `runInBackground` captures
  `ModalityState.defaultModalityState()` on the calling thread. Callbacks then run inside the dialog that started them,
  but not during an unrelated modal dialog. `CurrentIssueService` publishes with `ModalityState.nonModal()`.
- **Async results can arrive late or out of order.** Number each request (`generation`) and drop answers that aren't the
  newest one, or that arrive after `dispose()`. In dialogs, also check `isDisposed` and `component.isShowing` before
  showing a popup, otherwise `showUnderneathOf` throws on a closed dialog.
- **Git events arrive on several threads.** `CurrentIssueService.refresh` does its compare-and-set under `synchronized`.
- **Every message-bus connection needs a parent disposable** (`connect(this)` plus `Disposer.register(content, panel)`
  in the tool window factory).
- **Unsolicited refreshes are silent.** Branch changes and settings changes use `runInBackground` with a null title and
  show errors in the component. Progress and balloons are only for actions the user started.

### Settings

- **`isModified` must normalise exactly as `apply` does** (trailing `/` on URLs, the default JQL stored as empty).
  Otherwise Apply stays enabled forever and each Apply looks like a change.
- **Secrets are keyed by kind + URL + username.** When either changes, move the secret and delete the old entry, or the
  keychain fills with orphans.
- **After changing server settings, refresh open projects** (issue navigation rule, current issue). Project settings
  changes publish `ProjectSettings.CHANGED` so open tabs can reload.
- **Project settings are committed by teams.** No URLs, usernames or secrets in `ProjectSettings`.

### Review checklist

Before pushing a change, check it against the bugs found in review:

1. Does anything blocking (REST, password safe, file walking) run on the EDT?
2. Can an older or slower request overwrite a newer result, or update a disposed component?
3. Does each new server call have a `FakeTransport` test for every version branch and the error path?
4. Is user input encoded for its context: URL path, query, CQL, KQL/Lucene, Rison?
5. Does an unknown server version pick the legacy-compatible behaviour (or is the exception documented in KDoc)?
6. Is the change recorded in `CHANGELOG.md`, and did `verifyPlugin` stay clean?

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
