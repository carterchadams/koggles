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
    is Scope.Folder -> "folder:$packId|$folder"
}

/** Inverse of [encode]; anything unrecognised (including null) is [Scope.AllEnabled]. */
fun decodeScope(s: String?): Scope = when {
    s == null -> Scope.AllEnabled
    s == "all" -> Scope.AllEnabled
    s == "fav" -> Scope.Favorites
    s.startsWith("pack:") -> s.removePrefix("pack:").takeIf { it.isNotEmpty() }?.let { Scope.PackOnly(it) }
        ?: Scope.AllEnabled
    s.startsWith("folder:") -> {
        // Pack ids never contain '|', so split on the first one; the folder may contain anything.
        val rest = s.removePrefix("folder:")
        val bar = rest.indexOf('|')
        if (bar <= 0 || bar == rest.length - 1) Scope.AllEnabled
        else Scope.Folder(rest.substring(0, bar), rest.substring(bar + 1))
    }
    else -> Scope.AllEnabled
}
