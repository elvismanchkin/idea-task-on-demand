# Task on Demand: IntelliJ IDEA plugin for on-prem Jira, Confluence, Kibana and Grafana

Connects the IDE to **self-hosted, legacy-friendly** Atlassian and observability servers. Cloud editions are out of scope.
See [docs/RESEARCH.md](docs/RESEARCH.md) for the background and the API choices per server version.

| | What it does | Versions handled |
|---|---|---|
| **Jira** | Task list (JQL) in the tool window. The current issue comes from the git branch (`feature/PROJ-123-…`) and shows in the status bar. You can create issues (including the project's required custom fields), change status, open an issue by key, get clickable issue keys in the VCS log and console, and have commit messages prefilled with the key. | Server/DC 7.x – 9.x (create-meta API picked by version) |
| **Confluence** | Lists pages linked from the current issue (remote links), pages mentioning the issue key, pages pinned in project settings, and Confluence URLs found in `README*` / `docs/**`. Also has search. | Server/DC 5.5+ (`/rest/api/content`) |
| **Kibana** | Opens Discover with the application's logs (index pattern + query + time range). *Search Logs in Kibana* on selected text in the editor or console. | 6.x – 8.x (legacy `/app/kibana#/discover` vs `/app/discover#/`, Lucene before 6.3) |
| **Grafana** | Opens the application's dashboard with template variables and time range. Includes a dashboard picker in settings. | 4.x – 10.x (`/dashboard/db/slug` before 5.0) |

## Setup

1. **Settings | Tools | Task on Demand** (per developer): URL, authentication and *Test Connection* for each server.
   - Auth options: username + password (Basic, works on every legacy version), Bearer token (Jira ≥ 8.14 / Confluence ≥ 7.9
     personal access tokens, Grafana API keys), Elasticsearch API key (Kibana), or anonymous.
   - Passwords and tokens are stored in the IDE password safe.
   - Internal-CA or self-signed certificates: accept them under **Settings | Tools | Server Certificates**.
     All requests go through the IDE's HTTP stack, so its proxy settings apply as well.
2. **Settings | Tools | Task on Demand | Project Mapping** (per project, stored in `.idea/taskOnDemand.xml`): Jira project keys,
   optional JQL / branch regex, Confluence space and pinned pages, and one or more *applications*, each with:
   - Kibana index pattern (loaded from the server) and a query such as `application:"orders" and env:"dev"`
   - Grafana dashboard (picked from the server) and variables, one `name=value` per line
   This file contains no URLs or secrets. Commit it so the whole team gets the mapping.

## Using it

- **Tool window "Task on Demand"** has three tabs: *Jira*, *Logs & Metrics* and *Confluence*.
- **Status bar** shows `PROJ-123 · In Progress` for the current branch. Click it for all actions.
- **Tools | Task on Demand** has: open current issue, go to issue, create issue, change status, open logs,
  open dashboard, linked Confluence pages.
  No default shortcuts are assigned. To add some, go to *Settings | Keymap* and search for "Task on Demand".
- **Editor / console context menu → Search Logs in Kibana** opens the selected text (exception message, trace ID) in the
  application's logs.

If Jira/Confluence answer with *CAPTCHA required* after failed logins, log in once in the browser and retry.

## Building

Requirements: JDK 21. The Gradle wrapper (Gradle 9) downloads everything else.

```bash
./gradlew test           # unit tests
./gradlew buildPlugin    # build/distributions/idea-task-on-demand-<version>.zip
./gradlew runIde         # sandbox IDE with the plugin installed
./gradlew verifyPlugin   # JetBrains Plugin Verifier against the IDEs listed in build.gradle.kts
```

Install the ZIP via *Settings | Plugins | ⚙ | Install Plugin from Disk…*. To distribute it inside the company with
auto-updates, use an internal plugin repository (see [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md#internal-plugin-repository)).

The build targets IntelliJ Platform **2024.3 (since-build 243)** and has no upper bound.

## Documentation

- [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md): architecture, version-specific behaviour, how to extend, releasing and the
  internal plugin repository
- [docs/RESEARCH.md](docs/RESEARCH.md): research on the APIs and the roadmap
- [CHANGELOG.md](CHANGELOG.md)
- [CLAUDE.md](CLAUDE.md): conventions for AI-assisted work in this repository

## Status and next steps

Version 0.1.0 has not yet been tested against real servers. Please report what breaks with your server versions.

Not done yet:

- In-IDE log viewer: query Elasticsearch directly or through Kibana's console proxy, with clickable stack traces.
- Grafana dashboard "schema": list panels and variables from `/api/dashboards/uid/{uid}`.
- Create a branch from an issue. Comments.
- SSO / reverse-proxy login (JCEF browser login with cookie reuse).
