package dev.koggles.settings

/** Which presets rotation draws from (spec §5.3). Persisted as a short string via [encode]. */
sealed interface Scope {
    data object AllEnabled : Scope
    data object Favorites : Scope
    data class PackOnly(val packId: String) : Scope
    data class Folder(val packId: String, val folder: String) : Scope
}

fun Scope.encode(): String = when (this) {
    Scope.AllEnabled -> "all"
    Scope.Favorites -> "fav"
    is Scope.PackOnly -> "pack:$packId"
    is Scope.Folder -> "folder:${escape(packId)}|${escape(folder)}"
}

// Folder names may contain anything, so '\' and '|' are escaped in the folder encoding.
private fun escape(s: String) = s.replace("\\", "\\\\").replace("|", "\\|")

/** Splits on the first unescaped '|' and unescapes both halves; null if there is none. */
private fun splitEscaped(s: String): Pair<String, String>? {
    val first = StringBuilder()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            c == '\\' && i + 1 < s.length -> { first.append(s[i + 1]); i += 2 }
            c == '|' -> return first.toString() to unescape(s.substring(i + 1))
            else -> { first.append(c); i++ }
        }
    }
    return null
}

private fun unescape(s: String): String {
    val out = StringBuilder()
    var i = 0
    while (i < s.length) {
        if (s[i] == '\\' && i + 1 < s.length) { out.append(s[i + 1]); i += 2 } else { out.append(s[i]); i++ }
    }
    return out.toString()
}

/** Inverse of [encode]; anything unrecognised (including null) is [Scope.AllEnabled]. */
fun decodeScope(s: String?): Scope = when {
    s == null -> Scope.AllEnabled
    s == "all" -> Scope.AllEnabled
    s == "fav" -> Scope.Favorites
    s.startsWith("pack:") -> s.removePrefix("pack:").takeIf { it.isNotEmpty() }?.let { Scope.PackOnly(it) }
        ?: Scope.AllEnabled
    s.startsWith("folder:") -> {
        val parts = splitEscaped(s.removePrefix("folder:"))
        if (parts == null || parts.first.isEmpty() || parts.second.isEmpty()) Scope.AllEnabled
        else Scope.Folder(parts.first, parts.second)
    }
    else -> Scope.AllEnabled
}
