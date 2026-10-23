# Changelog

All notable changes to this plugin. Format: [Keep a Changelog](https://keepachangelog.com/).

## [Unreleased]

### Security

- Passwords and tokens are no longer sent when a server redirects to another host, port or to plain http
  (for example to an SSO login page).

### Fixed

- Create Issue: fields of the previously selected project could be submitted for a different project; issues can only
  be created once the fields for the selected project and type are loaded. Required datetime fields now use Jira's
  full date-time format, and multi-selects of plain strings send the values instead of ids. Paged create-meta answers
  (Jira 8.4+) are read completely.
- Branch names: keys after an underscore (`bugfix_PROJ-12`) are found; `UTF-8`, `JDK-17` and similar are no longer
  taken for issues when no project keys are set; a custom branch pattern must yield a valid key (a bare number gets
  the single configured project key).
- The current issue, Jira task list and Confluence tab no longer show results of an older request after a quicker
  newer one. Background refreshes on branch change no longer show progress or error balloons.
- Confluence: "Mentions issue" searches the key as an exact phrase instead of matching every page containing its
  number; search text with special characters no longer fails; pinned pages that can't be loaded still show as
  links; an unreadable folder in `docs/` no longer breaks the tab; search on Confluence before 5.7 reports that it
  needs CQL. Links with parentheses and tiny links are detected more precisely.
- Grafana: variables pasted from a URL are no longer encoded twice; dashboard uid/slug are URL-encoded.
- Settings: the password safe is read and written in the background, so the settings page no longer freezes on a
  locked keychain; the old secret is removed when the URL or username changes; a trailing `/` in a URL no longer
  keeps Apply enabled; changing the Jira URL updates issue links right away and no longer leaves the old link rule.
  The Logs & Metrics tab refreshes after the mapping is edited in Settings.
- Cancelling a task no longer shows a `ProcessCanceledException` error. Jira error messages are shown without JSON
  escapes, and responses in a non-UTF-8 charset are decoded correctly.

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
