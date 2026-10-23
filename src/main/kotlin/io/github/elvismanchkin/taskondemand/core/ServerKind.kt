package io.github.elvismanchkin.taskondemand.core

enum class ServerKind(val displayName: String, val supportedAuth: List<AuthType>) {
    JIRA("Jira", listOf(AuthType.BASIC, AuthType.BEARER, AuthType.NONE)),
    CONFLUENCE("Confluence", listOf(AuthType.BASIC, AuthType.BEARER, AuthType.NONE)),
    KIBANA("Kibana", listOf(AuthType.NONE, AuthType.BASIC, AuthType.API_KEY, AuthType.BEARER)),
    GRAFANA("Grafana", listOf(AuthType.NONE, AuthType.BEARER, AuthType.BASIC)),
}

enum class AuthType(val displayName: String, val needsUsername: Boolean, val secretLabel: String?) {
    NONE("Anonymous", false, null),
    BASIC("Username and password", true, "Password:"),
    BEARER("Token (PAT / Grafana API key)", false, "Token:"),
    API_KEY("Elasticsearch API key", false, "API key (base64 id:key):"),
}
