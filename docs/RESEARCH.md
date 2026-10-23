# Research: IntelliJ IDEA plugin for on-prem Kibana / Grafana / Jira / Confluence

Status: research notes, kept as background. Roadmap steps 1–4 are implemented (see §11 and the [README](../README.md)).
Scope: **on-premise, self-hosted, mostly legacy versions**.
Atlassian Cloud, Elastic Cloud and Grafana Cloud are out of scope.

---

## 1. Goals (from the request)

| Integration | MVP | Later |
|---|---|---|
| **Kibana** | Show logs for the application being worked on. App ↔ Kibana association (index pattern + filter) stored in settings. | Query/tail logs inside the IDE; jump from a stack trace to logs. |
| **Grafana** | Open the right Grafana dashboard in the browser (with variables and a time range). | Fetch dashboard "schema" (panels, variables) and show it in the IDE. |
| **Jira** | List tasks for the current project, create tasks, detect the issue key from the current git branch, jump to the task quickly. | Transitions, comments, branch creation from an issue, commit-message prefixing. |
| **Confluence** | Show Confluence pages linked from the project or from the current Jira issue. | Search Confluence, render pages in the IDE. |

---

## 2. Plugin-development toolchain (current as of 2026)

- **Language:** Kotlin. JetBrains' own samples and the template use it.
- **Build:** Gradle + **IntelliJ Platform Gradle Plugin 2.x** (`org.jetbrains.intellij.platform`).
  The 1.x plugin (`org.jetbrains.intellij`) is obsolete. Minimum requirements for 2.x: Gradle 8.2+, Java 17+,
  target platform 2022.3+. The latest 2.x release found is 2.16.0, and the template uses Gradle 9.x.
- **Starting point:** the [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)
  (Gradle setup, GitHub Actions CI, `runIde`, plugin verifier, signing). JetBrains also has a newer web-based IDE plugin
  generator; either one is fine for bootstrapping.
- **Target IDE range:** choose `sinceBuild` based on the oldest IDEA your team actually uses. 2024.2 or later
  (`242`) is a sensible floor: it has coroutine-based services and Kotlin UI DSL v2, and the plugin
  verifier keeps things honest.
- **Distribution:** on-prem teams usually don't publish to the Marketplace. Use a **custom plugin repository**:
  an `updatePlugins.xml` served from any internal HTTP server or artifact repo (Nexus/Artifactory). Users add it under
  *Settings → Plugins → ⚙ → Manage Plugin Repositories*. This gives you auto-updates without the Marketplace.

Minimal `build.gradle.kts` sketch:

```kotlin
plugins {
    id("org.jetbrains.kotlin.jvm") version "<kotlin>"
    id("org.jetbrains.kotlin.plugin.serialization") version "<kotlin>"
    id("org.jetbrains.intellij.platform") version "2.16.0"   // check latest
}

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.2.5")    // or intellijIdea(...) for 2025.3+ unified distribution
        bundledPlugin("Git4Idea")            // current branch, branch-change events
        // bundledPlugin("com.intellij.tasks") // only if we choose to integrate with built-in Tasks (see §8)
        pluginVerifier()
        zipSigner()
    }
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:<ver>")
}
```

---

## 3. IntelliJ Platform building blocks we need

| Need | Platform API |
|---|---|
| Per-project settings (app ↔ Kibana/Grafana/Jira project mapping) | `PersistentStateComponent` project service, `@State(storages = [Storage("taskOnDemand.xml")])` stored in `.idea/`. **Can be committed** so the whole team shares the mapping. |
| Per-user / per-IDE settings (server URLs, username) | Application-level `PersistentStateComponent` (`Storage("taskOnDemand.xml", roamingType = …)`) |
| Secrets (tokens, passwords) | `PasswordSafe` + `CredentialAttributes(generateServiceName("TaskOnDemand", serverUrl))`. Never store them in XML. |
| Settings UI | `Configurable` / `BoundConfigurable` with **Kotlin UI DSL v2** (`panel { row { textField().bindText(...) } }`), registered via `projectConfigurable` / `applicationConfigurable` under *Tools*. Add a "Test connection" button for each server. |
| Main UI | `ToolWindowFactory` with tabs (Jira / Logs / Confluence). |
| Current-task indicator | `StatusBarWidgetFactory`: shows `PROJ-123 · In Progress`, click opens a popup (open in browser, transition, logs, dashboard). |
| Actions / shortcuts | `AnAction` in `plugin.xml` with keymap shortcuts, e.g. *Open current issue*, *Open logs*, *Open Grafana*, *Create issue*. Search Everywhere contributor (`SearchEverywhereContributor`) for "jump to issue". |
| Current git branch + change events | `GitRepositoryManager.getInstance(project).repositories.first().currentBranchName`. Subscribe to `GitRepository.GIT_REPO_CHANGE` or `BranchChangeListener.VCS_BRANCH_CHANGED` on the project message bus. |
| Background HTTP | Project/app services that take a `CoroutineScope` in the constructor. Use `withBackgroundProgress` for user-visible work. Never call the network on the EDT. |
| HTTP client | `com.intellij.util.io.HttpRequests`. It **respects the IDE proxy settings and the IDE's trusted certificates** (*Settings → Tools → Server Certificates*), which is essential for on-prem servers with internal CAs or self-signed certs. Alternative: `java.net.http.HttpClient` with `CertificateManager.getInstance().sslContext`. |
| Opening web pages | `BrowserUtil.browse(url)`. To embed pages, use **JCEF** (`JBCefBrowser`, check `JBCefApp.isSupported()`). |
| Clickable issue keys in VCS log / commit messages / console | Built-in **Issue Navigation** (`IssueNavigationConfiguration`). Our plugin can register the `[A-Z][A-Z0-9]+-\d+` → `{jira}/browse/$0` link automatically. |
| Commit message prefix | `CommitMessageProvider` (prefill `PROJ-123: `) |
| Notifications | `NotificationGroup` declared in `plugin.xml` + `Notification(...).notify(project)`. |

### Suggested module layout

```
src/main/kotlin/…/taskondemand/
  settings/      AppSettings, ProjectSettings, Configurables, CredentialStore
  core/          HttpSupport (auth, TLS, version detection), BranchIssueResolver
  jira/          JiraClient, JiraModels, JiraToolWindowTab, CreateIssueDialog, actions
  confluence/    ConfluenceClient, LinkedPagesCollector, ConfluenceTab
  kibana/        KibanaClient, DiscoverUrlBuilder, LogsTab
  grafana/       GrafanaClient, DashboardUrlBuilder
  ui/            StatusBarWidget, ToolWindowFactory, SearchEverywhere contributor
src/main/resources/META-INF/plugin.xml
```

Each server client should implement one small interface (`ping()`, `serverInfo()`) so the settings page
can run "Test connection" and choose an API flavour based on the detected version.

---

## 4. Legacy on-prem considerations (applies everywhere)

1. **Version detection first.** Call the server-info endpoint once, cache the version, and branch on it:
   - Jira: `GET /rest/api/2/serverInfo` → `versionNumbers`
   - Confluence: `GET /rest/applinks/1.0/manifest` (has `version`), or read the `ajs-version-number` meta tag on any HTML page
   - Kibana: `GET /api/status` → `version.number`
   - Grafana: `GET /api/health` → `version` (Grafana 6+), or `GET /api/frontend/settings` → `buildInfo.version`
2. **Auth will vary by version.** Plan for these modes per server:
   - **Basic auth** (username + password). Works on all legacy Jira/Confluence/Kibana versions. Often the only option.
   - **Bearer Personal Access Token**: Jira ≥ 8.14, Confluence ≥ 7.9 only.
   - **Kibana/Elasticsearch API key** (`Authorization: ApiKey base64(id:key)`): ES/Kibana ≥ 6.7 with security enabled.
   - **Grafana API key** (Bearer): all versions. Service-account tokens on Grafana ≥ 8.5/9.
   - **No auth**: open-source Kibana 6.x without X-Pack security, or anonymous Grafana.
   - **SSO / reverse proxy (SAML, Kerberos, header auth)**: REST clients cannot easily do this. The fallback is a JCEF
     browser login, then reuse its cookies (`JBCefCookieManager`), or a browser-only mode.
3. **TLS.** Old servers may use self-signed or internal-CA certificates. Use the IDE certificate manager (see §3).
   Very old servers that only speak TLS 1.0/1.1 will **fail on JDK 17+** by default. Document this, because the server
   side needs fixing.
4. **CAPTCHA lockout.** After several failed Basic-auth logins, Jira/Confluence require CAPTCHA, and REST calls then fail with
   `X-Seraph-LoginReason: AUTHENTICATION_DENIED`. Detect this header and tell the user to log in through the browser once.
   Don't keep retrying.
5. **Lifecycle context.** Atlassian has announced end of life for Jira/Confluence Data Center (licences go read-only in
   March 2029, and Server is already unsupported). The servers won't get new APIs, so the legacy REST surface is stable to target.
6. **Timeouts and offline mode.** These servers are often reachable only over VPN. Use short connect timeouts,
   cache the last results, and never block IDE startup.

---

## 5. Jira (Server / Data Center, legacy 7.x–9.x)

**Base API:** `/rest/api/2/...` (v2 is the stable API on Server/DC. Don't use Cloud's v3).

| Feature | Endpoint / approach |
|---|---|
| Current user | `GET /rest/api/2/myself` (also a good "test connection" call) |
| Tasks for project | `GET /rest/api/2/search?jql=…&fields=summary,status,assignee,issuetype,priority,updated&maxResults=50` <br>Default JQL: `project = KEY AND assignee = currentUser() AND resolution = Unresolved ORDER BY updated DESC`. Make the JQL editable and save it per project. Use `POST /rest/api/2/search` for long JQL. |
| Single issue | `GET /rest/api/2/issue/{key}?fields=…&expand=renderedFields` |
| Create issue | `POST /rest/api/2/issue` with `{fields:{project:{key},issuetype:{id},summary,description,…}}` |
| Create metadata (required fields per issue type) | **Version-dependent:** <br>• Jira **< 8.4**: `GET /rest/api/2/issue/createmeta?projectKeys=KEY&expand=projects.issuetypes.fields` <br>• Jira **≥ 8.4**: `GET /rest/api/2/issue/createmeta/{projectKey}/issuetypes` then `…/issuetypes/{id}` <br>• The old form was **removed in Jira 9.0**, so detect the version and pick one. |
| Transitions | `GET/POST /rest/api/2/issue/{key}/transitions` |
| Comments | `GET/POST /rest/api/2/issue/{key}/comment` |
| Linked Confluence pages | `GET /rest/api/2/issue/{key}/remotelink`. Entries with `application.type == "com.atlassian.confluence"` (or `object.url` under the Confluence base URL) are Confluence pages. **This is the main way to find Confluence pages for a task.** |
| Agile boards / sprint | `GET /rest/agile/1.0/board?projectKeyOrId=KEY`, `…/board/{id}/sprint?state=active`, `…/sprint/{id}/issue` (Jira Software only) |

**Branch → issue:**
- Regex `(?<![A-Z0-9])([A-Z][A-Z0-9_]+-\d+)` on the current branch name. Matches `feature/PROJ-123-login`, `PROJ-123`, `bugfix/proj-123` (with a case-insensitive option).
  Optionally restrict to the configured project key(s) to avoid false positives like `UTF-8`.
- On branch change: resolve the key, fetch the issue, update the status-bar widget, and select the issue in the tool window.
- "Fast switch": a *Go to Issue* action (`Ctrl+Alt+J`-style) opens the current branch's issue in the tool window or browser.
  The reverse direction, *Create branch from issue*, is a later feature that uses `GitBrancher`.

---

## 6. Confluence (Server / Data Center, legacy 6.x–7.x)

**Base API:** `/rest/api/content…` (present since 5.x. Don't use the Cloud v2 API).

Ways to find pages "linked to the project or the task":

1. **From Jira remote links** (see §5). The `object.url` usually contains `pageId=NNN`. Fetch the page with
   `GET /rest/api/content/{id}?expand=space,version,body.view`.
2. **From the repository:** scan `README*`, `docs/**/*.md`, `CONTRIBUTING*` (and optionally source comments) for URLs under
   the configured Confluence base URL. Recognise these legacy URL shapes:
   - `/pages/viewpage.action?pageId=123`
   - `/display/SPACEKEY/Page+Title` → resolve with `GET /rest/api/content?spaceKey=SPACEKEY&title=Page Title`
   - `/x/AbCd` (tiny links) → follow the redirect
   Run this as a background task when the project opens and when those files change.
3. **Explicitly configured** in project settings: a space key and/or root page ID for the project.
4. **CQL search:** `GET /rest/api/content/search?cql=…`, for example `text ~ "PROJ-123"`,
   `space = DEV AND label = "my-service"`, or `type = page AND title ~ "my-service"`. CQL is available on all 6.x/7.x versions.

Display: show a list (title, space, last modified) in the tool window. Open in the browser by default. JCEF preview is optional,
because Confluence `body.view` HTML relies on Confluence CSS/JS, so rendering it standalone looks poor. Embedding the real
page in JCEF works but needs the user's session cookies.

---

## 7. Kibana (legacy 6.x–7.x, possibly some 8.x)

There are two levels of integration:

### 7.1 MVP: deep link into Discover (works everywhere, no API auth needed)

Store per project (or per run configuration / module): `kibanaUrl`, `indexPattern` (ID **and** title), a base query
(for example `kubernetes.labels.app:"my-service"` or `application:"my-service"`), an optional `environment` field/value, and
a default time range. Build a URL from those values in **rison** format. The URL shape **differs by version**:

- **Kibana 6.x / 7.0–7.9:**
  `{kibana}/app/kibana#/discover?_g=(time:(from:now-1h,to:now))&_a=(index:'<indexPatternId>',query:(language:kuery,query:'application:"my-service"'))`
- **Kibana 7.10+ / 8.x:**
  `{kibana}/app/discover#/?_g=(time:(from:now-1h,to:now))&_a=(index:'<indexPatternId>',query:(language:kuery,query:'…'))`
  (In 8.x index patterns are called *data views*. The URL still uses `index:`.)
- Kibana 6.x before 6.3 doesn't support KQL, so use `language:lucene`.
- Rison-encode values properly. The `'` and `!` characters must be escaped as `!'` and `!!`. A tiny Kotlin rison encoder is ~50 lines,
  so there's no need for a dependency.

To fill dropdowns in settings, fetch the list of index patterns:
- 6.x/7.x: `GET /api/saved_objects/_find?type=index-pattern&fields=title&per_page=1000`
- 8.x: `GET /api/data_views` (saved_objects still works)

Nice extras: *Open logs for selected text* (exception message or trace ID → added to the query). A Kibana link from
the Jira status-bar popup for the current issue.

### 7.2 Later: show logs inside the IDE

Options, best first:
1. **Query Elasticsearch directly**: `POST /{index}/_search` with `query_string`/`bool` + `range @timestamp`, sorted
   desc, `size: 200`. Simple and stable across ES 6/7/8. It needs network access and credentials for ES, which are often
   **not** exposed on-prem.
2. **Through Kibana's console proxy**: `POST {kibana}/api/console/proxy?path=/{index}/_search&method=GET` with
   `kbn-xsrf: true` and `Content-Type: application/json`. It works with whatever Kibana auth the user has, and the endpoint has existed since
   Kibana 5.x. It's undocumented/internal, but it's what Dev Tools uses. Treat it as best-effort and keep the deep link as the fallback.
3. **Embed Discover in a JCEF tool window**. This gets the full Kibana UI and SSO for free, but no IDE-level interaction.

Render results in a table or console view (timestamp, level, logger, message). Make stack traces clickable with
`ExceptionFilters` / `AnalyzeStacktraceUtil`, so a log line jumps to the source. This is the main reason to do 7.2.

---

## 8. Grafana (legacy 6.x–9.x)

### 8.1 MVP: open dashboard

Settings per project: `grafanaUrl`, dashboard `uid` (or chosen from search), `orgId`, and a map of template variables
(e.g. `service=my-service`, `env=prod`).

URL: `{grafana}/d/{uid}/{slug}?orgId=1&var-service=my-service&var-env=prod&from=now-6h&to=now`
- `/d/{uid}` exists since Grafana 5.0. Grafana < 5 uses `/dashboard/db/{slug}`.
- Optional: `&viewPanel={panelId}` (7+) to focus one panel. Use `&kiosk` to embed in JCEF.

Dashboard picker in settings: `GET /api/search?type=dash-db&query=…` (or `&tag=my-service`) → `uid`, `title`, `url`.
The response's `url` field already gives the correct path for the server's version.

### 8.2 Later: "schema fetching"

- `GET /api/dashboards/uid/{uid}` → full dashboard JSON: panels (title, type, datasource, targets/queries),
  `templating.list` (variables and their options).
- Use it to list panels (with "open this panel" links), and to auto-populate variable choices in settings.
- Actually querying data: `POST /api/ds/query` (Grafana 7.5+). On older versions use the datasource proxy `/api/datasources/proxy/{id}/…`.
  This is data-source-specific, so leave it out of the MVP.

---

## 9. Settings model (proposal)

**Application level** (per developer, not committed):
```
servers:
  jira:       { url, authType: BASIC|PAT|COOKIE, username }     # secret in PasswordSafe
  confluence: { url, authType, username }
  kibana:     { url, authType: NONE|BASIC|API_KEY|COOKIE, username }
  grafana:    { url, authType: NONE|API_KEY|BASIC, username }
```

**Project level** (`.idea/taskOnDemand.xml`, committable so the team shares it):
```
jira:       { projectKeys: [PROJ], defaultJql, branchPattern, defaultIssueType }
confluence: { spaceKey, rootPageId, extraPageIds: [] }
kibana:     { indexPatternId, indexPatternTitle, baseQuery, timeRange, environments: {dev: "...", prod: "..."} }
grafana:    { dashboards: [{ name, uid, vars: {service: "my-service"} }], defaultTimeRange }
```

To support multi-service repos, allow a list of "applications" per project (for example keyed by module or run configuration),
each with its own Kibana/Grafana mapping. Pick the active one automatically from the selected run configuration or module.

---

## 10. Built-in "Tasks & Contexts" plugin: reuse or build our own?

IntelliJ ships a bundled **Tasks** plugin (`com.intellij.tasks`) that already supports Jira servers, switching tasks,
creating branches from tasks and saving context. Options:

- **A. Build our own Jira integration (recommended).** We control the UI (tool window, create-issue dialog,
  status widget), the legacy-version handling (createmeta, auth fallbacks) and the cross-linking with Confluence/Kibana.
  The Tasks plugin's Jira connector is generic and hard to extend.
- **B. Integrate with Tasks.** Make an optional dependency on `com.intellij.tasks` and sync our "current issue" into
  `TaskManager.activateTask(...)` so that its changelist/context features still work for people who use them. This can be
  added later on top of A.

---

## 11. Roadmap

| # | Step | Status |
|---|---|---|
| 1 | **Skeleton.** Template project, settings pages (servers + project mapping), PasswordSafe, "Test connection" with version detection. | ✅ Done in 0.1.0. A custom plugin repository is documented in [DEVELOPMENT.md](DEVELOPMENT.md) but not set up. |
| 2 | **Jira MVP.** Branch → issue detection, status-bar widget, tool window list (JQL), open in browser, create issue (version-aware createmeta), Issue Navigation link registration. | ✅ Done in 0.1.0, plus transitions and the commit-message prefix from step 5. |
| 3 | **Kibana + Grafana deep links.** Version-aware Discover URL builder, Grafana dashboard URL with variables, actions in the tool window, status-bar popup and editor/console context menu. | ✅ Done in 0.1.0 |
| 4 | **Confluence.** Pages from Jira remote links, repo URL scan, configured space and pinned pages. List in the tool window. | ✅ Done in 0.1.0, plus pages that mention the issue key and search. |
| 5 | **Later.** In-IDE log viewer (ES / console proxy) with clickable stack traces, Grafana panel listing, comments, create branch from issue, JCEF embedding for SSO setups. | ⏳ Open |

Nothing has been tested against real servers yet. Verifying the version-specific branches (Kibana URL routes,
Confluence tiny links, Jira create-meta) against the team's actual servers is the most valuable next step.

## 12. Open questions for the team

- Exact versions of Jira, Confluence, Kibana/Elasticsearch and Grafana. These decide the auth options and API branches above.
- Auth: Basic, PAT, or SSO (SAML/Kerberos/reverse proxy)? SSO pushes us toward JCEF-cookie auth.
- Is Elasticsearch reachable from developer machines, or only Kibana? This decides the in-IDE log viewer approach.
- How are logs tagged per application (field name: `application`, `service.name`, k8s labels, …)? Is it the same
  across environments?
- Branch naming convention (e.g. `feature/PROJ-123-short-desc`)?
- Minimum IntelliJ IDEA version used by the team (sets `sinceBuild`)?
- Should project mappings be committed to the repo (team-shared) or kept per developer?

---

## Sources

- IntelliJ Platform Gradle Plugin 2.x: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
- Configuring the Gradle plugin: https://plugins.jetbrains.com/docs/intellij/configuring-gradle.html
- Plugin template: https://github.com/JetBrains/intellij-platform-plugin-template
- JetBrains Platform blog (newsletter Q2 2026, plugin generator): https://blog.jetbrains.com/platform/2026/07/busy-plugin-developers-newsletter-q2-2026/ , https://blog.jetbrains.com/platform/2026/07/ide-plugin-generator-the-new-beginning/
- Atlassian Personal Access Tokens (Jira ≥ 8.14, Confluence ≥ 7.9): https://confluence.atlassian.com/display/ENTERPRISE/Using+Personal+Access+Tokens
- Atlassian Data Center end of life: https://www.atlassian.com/licensing/data-center-end-of-life
- Kibana API conventions (kbn-xsrf, auth): https://www.elastic.co/guide/en/kibana/7.10/using-api.html
