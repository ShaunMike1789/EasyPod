package com.smgray.easypod.migration

data class LegacyCategory(
    val name: String,
    val color: Int,
)

object LegacyCategoryCodec {
    fun parseCatalog(raw: String?): List<LegacyCategory> =
        raw.orEmpty()
            .split("|")
            .mapNotNull(::parseCategory)
            .distinctBy { it.name.lowercase() }

    fun parseAssignments(raw: String?): List<String> =
        raw.orEmpty()
            .split("|")
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.equals("Unassigned", ignoreCase = true) }
            .take(2)

    private fun parseCategory(value: String): LegacyCategory? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null

        val separator = trimmed.lastIndexOf("^")
        if (separator < 0) return LegacyCategory(trimmed, 0)

        val name = trimmed.substring(0, separator).trim()
        if (name.isEmpty()) return null
        return LegacyCategory(
            name = name,
            color = trimmed.substring(separator + 1).toIntOrNull() ?: 0,
        )
    }
}

