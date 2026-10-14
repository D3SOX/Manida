package io.github.d3sox.musicplayer.library

data class LocalFolderRules(val included: List<String> = emptyList(), val excluded: List<String> = emptyList()) {
    fun allows(folder: String, alternative: String? = null): Boolean {
        val folders = listOfNotNull(folder, alternative).map(::normalize)
        fun contains(rule: String): Boolean = folders.any { it == normalize(rule) || it.startsWith(normalize(rule) + "/") }
        return excluded.none(::contains) && (included.isEmpty() || included.any(::contains))
    }

    private fun normalize(path: String): String = path.trim().replace('\\', '/').trimEnd('/')
        .removePrefix("/storage/emulated/0/").removePrefix("/sdcard/").removePrefix("./")
}
