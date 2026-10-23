# Changelog

All notable changes to this plugin. Format: [Keep a Changelog](https://keepachangelog.com/).

## [Unreleased]

## [0.1.0]

### Added

- Server settings (Jira, Confluence, Kibana, Grafana) with Basic, token, API-key or anonymous auth. Secrets are kept
  in the IDE password safe. Test Connection detects the server version.
- Per-project mapping in `.idea/taskOnDemand.xml`: Jira project keys, JQL, branch pattern, Confluence space and pinned
  pages, and applications with a Kibana index pattern/query and a Grafana dashboard/variables.
- Jira: task list tool window, current issue from the git branch in the status bar, create issue with required custom
  fields (create-meta for Jira before and after 8.4), status transitions, go to issue by key, clickable issue keys
  (Issue Navigation), commit message prefilled with the issue key.
- Confluence: pages linked from the current issue, pages mentioning it, pinned pages, Confluence links found in
  README/docs files, and search.
- Kibana: open Discover for an application (legacy and 7.10+ routes, Lucene before 6.3). "Search Logs in Kibana"
  for text selected in the editor or console.
- Grafana: open an application's dashboard with template variables (legacy `/dashboard/db/` before 5.0) and a
  dashboard picker.
- Supports IntelliJ IDEA 2024.3 and newer. Verified compatible with 2024.3 and 2026.2.
