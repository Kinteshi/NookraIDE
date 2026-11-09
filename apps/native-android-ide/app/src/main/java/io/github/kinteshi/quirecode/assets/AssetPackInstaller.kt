package io.github.kinteshi.quirecode.assets

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream

data class AssetContribution(
    val label: String,
    val path: String,
    val languageId: String? = null,
    val scopeName: String? = null,
    val extensions: List<String> = emptyList(),
    val configurationPath: String? = null,
)

data class InstalledAssetPack(
    val id: String,
    val displayName: String,
    val version: String,
    val directory: File,
    val themes: List<AssetContribution>,
    val grammars: List<AssetContribution>,
    val languages: List<AssetContribution>,
    val snippets: List<AssetContribution>,
)

class AssetPackInstaller(private val packsDirectory: File) {
    fun install(vsix: InputStream): InstalledAssetPack {
        if (!packsDirectory.exists() && !packsDirectory.mkdirs()) {
            throw IOException("Could not create asset-pack directory")
        }
        val temporary = File(packsDirectory, ".install-${UUID.randomUUID()}")
        if (!temporary.mkdir()) throw IOException("Could not create temporary asset-pack directory")
        try {
            extractExtensionFiles(vsix, temporary)
            val packageFile = File(temporary, "extension/package.json")
            if (!packageFile.isFile) throw IOException("VSIX does not contain extension/package.json")
            val manifest = try {
                JsonParser.parseString(packageFile.readText(Charsets.UTF_8)).asJsonObject
            } catch (error: RuntimeException) {
                throw IOException("VSIX extension manifest is invalid JSON", error)
            }
            val publisher = manifest.string("publisher") ?: "unknown"
            val name = manifest.string("name") ?: throw IOException("VSIX manifest has no extension name")
            val id = safePackId("$publisher.$name")
            val displayName = manifest.string("displayName") ?: name
            val version = manifest.string("version") ?: "0.0.0"
            val contributions = manifest.getAsJsonObject("contributes") ?: JsonObject()
            val themes = contributions.assetContributions("themes")
            val grammars = contributions.assetContributions("grammars")
            val languages = contributions.assetContributions("languages", pathRequired = false)
            val snippets = contributions.assetContributions("snippets")
            validateContributionFiles(temporary, themes + grammars + languages + snippets)

            val destination = File(packsDirectory, id)
            if (destination.exists()) throw IOException("Asset pack is already installed: $id")
            if (!temporary.renameTo(destination)) throw IOException("Could not finalize asset pack: $id")
            return InstalledAssetPack(
                id,
                displayName,
                version,
                destination,
                themes,
                grammars,
                languages,
                snippets,
            )
        } catch (error: Exception) {
            temporary.deleteRecursively()
            throw error
        }
    }

    private fun extractExtensionFiles(vsix: InputStream, destination: File) {
        var totalBytes = 0L
        var entryCount = 0
        val filePaths = mutableSetOf<String>()
        ZipInputStream(vsix).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++
                if (entryCount > MAX_ENTRIES) throw IOException("VSIX contains too many archive entries")
                val relativePath = safeArchivePath(entry.name)
                if (!entry.isDirectory && !filePaths.add(relativePath)) {
                    throw IOException("VSIX contains a duplicate archive path")
                }
                val target = if (!entry.isDirectory && relativePath.startsWith("extension/")) {
                    val target = File(destination, relativePath)
                    val canonicalDestination = destination.canonicalFile
                    if (!target.canonicalPath.startsWith(canonicalDestination.path + File.separator)) {
                        throw IOException("VSIX entry escapes the asset-pack directory")
                    }
                    target.parentFile?.let { parent ->
                        if (!parent.exists() && !parent.mkdirs()) throw IOException("Could not create asset directory")
                    }
                    target
                } else {
                    null
                }
                val output = target?.let(::FileOutputStream)
                try {
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var fileBytes = 0L
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        fileBytes += count
                        totalBytes += count
                        if (fileBytes > MAX_FILE_BYTES || totalBytes > MAX_TOTAL_BYTES) {
                            throw IOException("VSIX exceeds the asset-pack size limit")
                        }
                        output?.write(buffer, 0, count)
                    }
                } finally {
                    output?.close()
                }
                zip.closeEntry()
            }
        }
    }

    private fun validateContributionFiles(root: File, contributions: List<AssetContribution>) {
        contributions.forEach { contribution ->
            listOfNotNull(contribution.path.takeIf(String::isNotBlank), contribution.configurationPath)
                .forEach { contributionPath ->
                    val path = safeArchivePath("extension/${contributionPath.removePrefix("./")}")
                    val file = File(root, path)
                    if (!file.isFile || !file.canonicalPath.startsWith(root.canonicalPath + File.separator)) {
                        throw IOException("Asset contribution is missing or outside the pack: $contributionPath")
                    }
            }
        }
    }

    private fun safePackId(value: String): String {
        val normalized = value.lowercase()
        if (normalized.isBlank() || normalized == "." || normalized == ".." ||
            normalized.any { !(it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') }
        ) {
            throw IOException("VSIX publisher/name contains unsupported characters")
        }
        return normalized
    }

    private fun safeArchivePath(value: String): String {
        if (value.isBlank() || value.startsWith('/') || value.startsWith('\\') || '\\' in value) {
            throw IOException("VSIX contains an invalid archive path")
        }
        val normalized = value.removeSuffix("/")
        val parts = normalized.split('/')
        if (parts.any { it.isBlank() || it == ".." || it == "." }) {
            throw IOException("VSIX contains a traversal or empty path segment")
        }
        return normalized
    }

    private fun JsonObject.string(name: String): String? =
        get(name)?.takeUnless { it.isJsonNull }?.asString

    private fun JsonObject.assetContributions(name: String, pathRequired: Boolean = true): List<AssetContribution> {
        val entries = getAsJsonArray(name) ?: return emptyList()
        return entries.map { entry ->
            if (!entry.isJsonObject) throw IOException("VSIX $name contribution must be an object")
            val contribution = entry.asJsonObject
            val path = contribution.string("path")
            if (pathRequired && path == null) throw IOException("VSIX $name contribution has no path")
            val languageId = contribution.string("language") ?: contribution.string("id")
            val configuration = contribution.string("configuration")
            val extensions = contribution.getAsJsonArray("extensions")
                ?.mapNotNull { value -> value.takeIf { it.isJsonPrimitive }?.asString }
                .orEmpty()
            val label = contribution.string("label")
                ?: languageId
                ?: path?.let { File(it).nameWithoutExtension }
                ?: throw IOException("VSIX $name contribution has no label or identifier")
            AssetContribution(
                label = label,
                path = path.orEmpty(),
                languageId = languageId,
                scopeName = contribution.string("scopeName"),
                extensions = extensions,
                configurationPath = configuration,
            )
        }
    }

    private companion object {
        const val MAX_ENTRIES = 5_000
        const val MAX_FILE_BYTES = 8L * 1024L * 1024L
        const val MAX_TOTAL_BYTES = 64L * 1024L * 1024L
    }
}
