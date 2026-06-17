package com.smgray.easypod.migration

data class LegacyManifestFile(
    val name: String,
    val size: Long,
)

data class LegacyBackupManifest(
    val version: String?,
    val timestamp: String?,
    val rootPath: String?,
    val device: String?,
    val totalSize: Long?,
    val files: List<LegacyManifestFile>,
)

object LegacyBackupManifestParser {
    fun parse(text: String): LegacyBackupManifest {
        val values = linkedMapOf<String, String>()
        val lines = text
            .lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()

        var index = 0
        while (index < lines.size) {
            val label = lines[index]
            if (label.startsWith("[") && label.endsWith("]")) {
                val value = lines.getOrNull(index + 1)
                    ?: throw IllegalArgumentException("Missing value for $label")
                values[label] = value
                index += 2
            } else {
                index++
            }
        }

        val fileCount = values.value("[FileNum]")?.toIntOrNull()
            ?: throw IllegalArgumentException("Backup manifest has no valid file count")
        require(fileCount in 1..MAX_FILE_COUNT) {
            "Backup manifest file count is outside the supported range"
        }

        val names = values.value("[FileNames]")
            ?.split(",")
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            .orEmpty()
        val sizes = (values.value("[FileSizes]") ?: values["[TotalSize]"])
            ?.split(Regex("[,\\s]+"))
            ?.mapNotNull(String::toLongOrNull)
            .orEmpty()

        require(names.size == fileCount && sizes.size == fileCount) {
            "Backup manifest file list is inconsistent"
        }

        return LegacyBackupManifest(
            version = values.value("[Version]"),
            timestamp = values.value("[timeStamp]", "[TimeStamp]"),
            rootPath = values.value("[RootPath]"),
            device = values.value("[device]", "[Device]"),
            totalSize = values["[totalSize]"]?.toLongOrNull(),
            files = names.zip(sizes) { name, size ->
                require(size in 0..MAX_ENTRY_SIZE) {
                    "Backup entry $name is too large"
                }
                LegacyManifestFile(name, size)
            },
        )
    }

    private const val MAX_FILE_COUNT = 64
    private const val MAX_ENTRY_SIZE = 256L * 1024L * 1024L

    private fun Map<String, String>.value(vararg labels: String): String? =
        labels.firstNotNullOfOrNull { label ->
            entries.firstOrNull { it.key.equals(label, ignoreCase = true) }?.value
        }
}
