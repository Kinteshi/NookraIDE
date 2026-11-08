package com.avfstudio.nativeide.assets

import android.content.Context
import android.util.Log
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.dsl.languages
import java.io.File
import java.io.IOException

object AssetPackGrammarLoader {
    private val loadedPacks = mutableSetOf<String>()
    /** Extension or exact file name → the pack and grammar scope that colours it. */
    private val extensionScopes = mutableMapOf<String, Pair<String, String>>()
    private val filenameScopes = mutableMapOf<String, Pair<String, String>>()
    /** Scope → the pack and grammar file, for registries other than sora's (the Aardink editor's). */
    private val grammarFiles = mutableMapOf<String, Pair<String, File>>()
    /** Scope → the pack and its language's `language-configuration.json`, where it has one. */
    private val configurationFiles = mutableMapOf<String, Pair<String, File>>()

    @Synchronized
    fun loadInstalled(context: Context) {
        val packsDirectory = File(context.applicationContext.filesDir, "asset-packs")
        val errors = packsDirectory.listFiles()?.filter(File::isDirectory)?.mapNotNull { pack ->
            try {
                loadPack(pack)
                null
            } catch (error: Exception) {
                val message = "${pack.name}: ${error.message ?: error.javaClass.simpleName}"
                Log.e(TAG, "Could not load asset-pack grammar: $message", error)
                message
            }
        }.orEmpty()
        context.applicationContext.getSharedPreferences("native-ide-assets", Context.MODE_PRIVATE)
            .edit()
            .putString("grammar-load-errors", errors.joinToString("; ").takeIf(String::isNotEmpty))
            .apply()
    }

    @Synchronized
    fun scopeNameForExtension(extension: String): String? = extensionScopes[extension.lowercase()]?.second

    /** The grammar for [fileName]: its exact name (Dockerfile), else its longest known extension. */
    @Synchronized
    fun scopeNameFor(fileName: String): String? {
        filenameScopes[fileName.lowercase()]?.let { return it.second }
        val parts = fileName.lowercase().split('.')
        for (start in 1 until parts.size) {
            extensionScopes[parts.drop(start).joinToString(".")]?.let { return it.second }
        }
        return null
    }

    /**
     * Forgets a removed pack's file types. Its grammars stay registered in sora until the app
     * restarts (the registry can't drop them), but no new editor uses them.
     */
    @Synchronized
    fun grammarFile(scopeName: String): File? = grammarFiles[scopeName]?.second

    @Synchronized
    fun configurationFile(scopeName: String): File? = configurationFiles[scopeName]?.second

    @Synchronized
    fun forgetPack(packId: String) {
        grammarFiles.values.removeAll { it.first == packId }
        configurationFiles.values.removeAll { it.first == packId }
        extensionScopes.values.removeAll { it.first == packId }
        filenameScopes.values.removeAll { it.first == packId }
        loadedPacks -= packId
    }

    @Synchronized
    fun loadPack(packDirectory: File) {
        val packId = packDirectory.name
        if (packId in loadedPacks) return
        val extensionRoot = File(packDirectory, "extension").canonicalFile
        val manifestFile = File(extensionRoot, "package.json")
        if (!manifestFile.isFile) throw IOException("Asset pack has no extension/package.json: $packId")
        val manifest = try {
            JsonParser.parseString(manifestFile.readText(Charsets.UTF_8)).asJsonObject
        } catch (error: RuntimeException) {
            throw IOException("Asset pack manifest is invalid: $packId", error)
        }
        val contributions = manifest.getAsJsonObject("contributes") ?: JsonObject()
        val languages = parseLanguages(contributions)
        val grammarEntries = contributions.getAsJsonArray("grammars") ?: run {
            loadedPacks += packId
            return
        }
        val definitions = grammarEntries.mapNotNull { entry ->
            if (!entry.isJsonObject) throw IOException("Asset-pack grammar contribution must be an object")
            val grammar = entry.asJsonObject
            val grammarFile = grammar.get("path")?.takeUnless { it.isJsonNull }?.asString
                ?: throw IOException("Asset-pack grammar contribution has no path")
            val scopeName = grammar.get("scopeName")?.takeUnless { it.isJsonNull }?.asString
                ?: throw IOException("Asset-pack grammar contribution has no scopeName")
            val resolvedGrammar = validateContributionFile(extensionRoot, grammarFile)
            grammarFiles[scopeName] = packId to resolvedGrammar
            val languageId = grammar.get("language")?.takeUnless { it.isJsonNull }?.asString
            val language = languageId?.let(languages::get)
            language?.extensions?.forEach { extension ->
                extensionScopes[extension.removePrefix(".").lowercase()] = packId to scopeName
            }
            language?.filenames?.forEach { name -> filenameScopes[name.lowercase()] = packId to scopeName }
            val configuration = language?.configurationPath?.let { path ->
                validateContributionFile(extensionRoot, path)
            }
            configuration?.let { configurationFiles[scopeName] = packId to it }
            GrammarDefinition(
                languageId = languageId ?: scopeName,
                grammarPath = "asset-packs/$packId/extension/${resolvedGrammar.relativeTo(extensionRoot).invariantSeparatorsPath}",
                scopeName = scopeName,
                configurationPath = configuration?.let {
                    "asset-packs/$packId/extension/${it.relativeTo(extensionRoot).invariantSeparatorsPath}"
                },
            )
        }
        if (definitions.isNotEmpty()) {
            val registryDefinitions = languages {
                definitions.forEach { definition ->
                    language(definition.languageId) {
                        grammar = definition.grammarPath
                        scopeName = definition.scopeName
                        definition.configurationPath?.let { languageConfiguration = it }
                    }
                }
            }.build()
            GrammarRegistry.getInstance().loadGrammars(registryDefinitions)
        }
        loadedPacks += packId
    }

    private fun parseLanguages(contributions: JsonObject): Map<String, LanguageConfig> {
        val declarations = contributions.getAsJsonArray("languages") ?: return emptyMap()
        return declarations.mapNotNull { entry ->
            if (!entry.isJsonObject) throw IOException("Asset-pack language contribution must be an object")
            val declaration = entry.asJsonObject
            val id = declaration.get("id")?.takeUnless { it.isJsonNull }?.asString
                ?: throw IOException("Asset-pack language contribution has no id")
            val extensions = declaration.getAsJsonArray("extensions")
                ?.mapNotNull { it.takeIf(JsonElement::isJsonPrimitive)?.asString }
                .orEmpty()
            val filenames = declaration.getAsJsonArray("filenames")
                ?.mapNotNull { it.takeIf(JsonElement::isJsonPrimitive)?.asString }
                .orEmpty()
            val configuration = declaration.get("configuration")?.takeUnless { it.isJsonNull }?.asString
            id to LanguageConfig(extensions, filenames, configuration)
        }.toMap()
    }

    private fun validateContributionFile(extensionRoot: File, relativePath: String): File {
        if (relativePath.isBlank() || relativePath.startsWith('/') || '\\' in relativePath) {
            throw IOException("Asset contribution has an invalid relative path")
        }
        val file = File(extensionRoot, relativePath.removePrefix("./")).canonicalFile
        if (!file.path.startsWith(extensionRoot.path + File.separator) || !file.isFile) {
            throw IOException("Asset contribution is missing or outside the pack: $relativePath")
        }
        return file
    }

    private data class LanguageConfig(val extensions: List<String>, val filenames: List<String>, val configurationPath: String?)
    private data class GrammarDefinition(
        val languageId: String,
        val grammarPath: String,
        val scopeName: String,
        val configurationPath: String?,
    )

    private const val TAG = "AssetPackGrammarLoader"
}
