package io.github.elvismanchkin.taskondemand.core

/** Numeric server version (`8.13.2`, `7.10.0-SNAPSHOT`, `v6.5.4`). Comparison ignores qualifiers. */
data class ServerVersion(val parts: List<Int>) : Comparable<ServerVersion> {

    val major: Int get() = parts.getOrElse(0) { 0 }

    fun isAtLeast(vararg required: Int): Boolean = this >= ServerVersion(required.toList())

    override fun compareTo(other: ServerVersion): Int {
        for (i in 0 until maxOf(parts.size, other.parts.size)) {
            val diff = parts.getOrElse(i) { 0 } - other.parts.getOrElse(i) { 0 }
            if (diff != 0) return diff
        }
        return 0
    }

    override fun toString(): String = parts.joinToString(".")

    companion object {
        private val LEADING_NUMBERS = Regex("""^\D*(\d+(?:\.\d+)*)""")

        fun parse(text: String?): ServerVersion? {
            val match = LEADING_NUMBERS.find(text?.trim().orEmpty()) ?: return null
            return ServerVersion(match.groupValues[1].split('.').map { it.toInt() })
        }
    }
}
