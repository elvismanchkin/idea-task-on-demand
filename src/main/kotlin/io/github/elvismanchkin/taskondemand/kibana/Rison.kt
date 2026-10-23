package io.github.elvismanchkin.taskondemand.kibana

/** Minimal Rison encoder (https://github.com/Nanonid/rison), the format Kibana uses for `_g` / `_a` URL state. */
object Rison {

    private const val NOT_ID_CHARS = " '!:(),*@$"
    private const val NOT_ID_START = "-0123456789"

    fun encode(value: Any?): String = when (value) {
        null -> "!n"
        is Boolean -> if (value) "!t" else "!f"
        is Int, is Long -> value.toString()
        is Number -> value.toString()
        is String -> encodeString(value)
        is Map<*, *> -> value.entries.joinToString(",", "(", ")") { (k, v) -> encodeString(k.toString()) + ":" + encode(v) }
        is Iterable<*> -> value.joinToString(",", "!(", ")") { encode(it) }
        else -> encodeString(value.toString())
    }

    fun encodeString(value: String): String {
        if (value.isNotEmpty() && value[0] !in NOT_ID_START && value.none { it in NOT_ID_CHARS || it.code < 0x21 }) {
            return value
        }
        return "'" + value.replace("!", "!!").replace("'", "!'") + "'"
    }
}
