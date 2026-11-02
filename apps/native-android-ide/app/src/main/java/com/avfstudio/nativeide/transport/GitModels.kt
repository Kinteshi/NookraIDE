package com.avfstudio.nativeide.transport

import org.msgpack.core.MessagePacker
import org.msgpack.value.Value

/** A Git operation, mirroring the daemon's `GitOp` (externally tagged MessagePack). */
sealed interface GitOperation {
    data object Status : GitOperation
    data class DiffFile(val path: String, val staged: Boolean) : GitOperation
    data class Stage(val paths: List<String>) : GitOperation
    data class Unstage(val paths: List<String>) : GitOperation
    data class Discard(val paths: List<String>) : GitOperation
    data class Commit(val message: String, val amend: Boolean = false, val signoff: Boolean = false) : GitOperation
    data object Branches : GitOperation
    data class Checkout(val name: String, val create: Boolean) : GitOperation
    data class Log(val limit: Int = 50, val rev: String? = null, val path: String? = null) : GitOperation
    data object Fetch : GitOperation
    data class Pull(val rebase: Boolean = false) : GitOperation
    data class Push(val setUpstream: Boolean = false, val forceWithLease: Boolean = false) : GitOperation
    data object Init : GitOperation
    data class Clone(val url: String, val dest: String) : GitOperation
    /** [path] as it was in commit [rev], with that commit's change to it (`git show`). */
    data class Show(val rev: String, val path: String) : GitOperation
    /** A whole commit: message, files changed and patch. */
    data class ShowCommit(val rev: String) : GitOperation
    data class Blame(val path: String) : GitOperation
    data class Stash(val op: StashOperation) : GitOperation
    /** Part of a file's diff applied to the index ([cached]) or the working tree, maybe [reverse]d. */
    data class ApplyPatch(val patch: String, val cached: Boolean, val reverse: Boolean) : GitOperation

    fun pack(packer: MessagePacker) {
        fun struct(name: String, fields: List<Pair<String, Any?>>) {
            packer.packMapHeader(1)
            packer.packString(name)
            packer.packMapHeader(fields.size)
            fields.forEach { (key, value) ->
                packer.packString(key)
                when (value) {
                    null -> packer.packNil()
                    is String -> packer.packString(value)
                    is Boolean -> packer.packBoolean(value)
                    is Int -> packer.packInt(value)
                    else -> error("Unsupported Git field ${value::class.java.name}")
                }
            }
        }
        fun paths(name: String, paths: List<String>) {
            packer.packMapHeader(1)
            packer.packString(name)
            packer.packArrayHeader(paths.size)
            paths.forEach(packer::packString)
        }
        when (this) {
            Status -> packer.packString("Status")
            Branches -> packer.packString("Branches")
            Fetch -> packer.packString("Fetch")
            Init -> packer.packString("Init")
            is DiffFile -> struct("DiffFile", listOf("path" to path, "staged" to staged))
            is Stage -> paths("Stage", paths)
            is Unstage -> paths("Unstage", paths)
            is Discard -> paths("Discard", paths)
            is Commit -> struct("Commit", listOf("message" to message, "amend" to amend, "signoff" to signoff))
            is Checkout -> struct("Checkout", listOf("name" to name, "create" to create))
            is Log -> struct("Log", listOf("rev" to rev, "limit" to limit, "path" to path))
            is Pull -> struct("Pull", listOf("rebase" to rebase))
            is Push -> struct("Push", listOf("set_upstream" to setUpstream, "force_with_lease" to forceWithLease))
            is Clone -> struct("Clone", listOf("url" to url, "dest" to dest))
            is Show -> struct("Show", listOf("rev" to rev, "path" to path))
            is ShowCommit -> struct("ShowCommit", listOf("rev" to rev))
            is Blame -> struct("Blame", listOf("path" to path))
            is ApplyPatch -> struct("ApplyPatch", listOf("patch" to patch, "cached" to cached, "reverse" to reverse))
            is Stash -> {
                packer.packMapHeader(1)
                packer.packString("Stash")
                packer.packMapHeader(1)
                packer.packString("op")
                when (op) {
                    StashOperation.List -> packer.packString("List")
                    is StashOperation.Push -> {
                        packer.packMapHeader(1)
                        packer.packString("Push")
                        packer.packMapHeader(1)
                        packer.packString("include_untracked")
                        packer.packBoolean(op.includeUntracked)
                    }
                    is StashOperation.Pop, is StashOperation.Apply, is StashOperation.Drop -> {
                        val (name, index) = when (op) {
                            is StashOperation.Pop -> "Pop" to op.index
                            is StashOperation.Apply -> "Apply" to op.index
                            is StashOperation.Drop -> "Drop" to op.index
                            else -> error("unreachable")
                        }
                        packer.packMapHeader(1)
                        packer.packString(name)
                        packer.packMapHeader(1)
                        packer.packString("index")
                        packer.packInt(index)
                    }
                }
            }
        }
    }
}

/** The daemon's `StashOp`. */
sealed interface StashOperation {
    data object List : StashOperation
    data class Push(val includeUntracked: Boolean) : StashOperation
    data class Pop(val index: Int) : StashOperation
    data class Apply(val index: Int) : StashOperation
    data class Drop(val index: Int) : StashOperation
}

enum class GitFileStatus { Untracked, Added, Modified, Deleted, Renamed, Copied, Conflicted }

/** One `git status` entry; [indexStatus]/[worktreeStatus] are porcelain letters ("." = unchanged). */
data class GitStatusEntry(
    val path: String,
    val originalPath: String?,
    val indexStatus: String,
    val worktreeStatus: String,
    val status: GitFileStatus,
) {
    val staged: Boolean get() = indexStatus != "." && indexStatus != "?" && indexStatus != "!" && status != GitFileStatus.Conflicted
    val unstaged: Boolean get() = worktreeStatus != "." || status == GitFileStatus.Untracked || status == GitFileStatus.Conflicted
}

data class GitBranch(val name: String, val isCurrent: Boolean, val upstream: String?)
data class GitCommit(val hash: String, val author: String, val timestamp: Long, val subject: String)

sealed interface GitResult {
    data object Success : GitResult
    data class Status(
        val branch: String?,
        val upstream: String?,
        val ahead: Int,
        val behind: Int,
        val entries: List<GitStatusEntry>,
    ) : GitResult
    data class Diff(val path: String, val staged: Boolean, val patch: String) : GitResult
    data class Branches(val branches: List<GitBranch>) : GitResult
    data class Commits(val commits: List<GitCommit>) : GitResult
    data class Text(val text: String) : GitResult
    data class Other(val kind: String) : GitResult
}

/** Streamed messages for one Git request. */
sealed interface GitEvent {
    val requestId: Long

    data class Result(override val requestId: Long, val result: GitResult) : GitEvent
    data class Progress(override val requestId: Long, val phase: String, val percent: Int?, val detail: String) : GitEvent
    data class Prompt(override val requestId: Long, val promptId: Long, val text: String, val secret: Boolean) : GitEvent
    data class Error(override val requestId: Long, val message: String) : GitEvent
}

internal fun decodeGitResult(value: Value): GitResult {
    if (value.isStringValue) return if (value.asStringValue().asString() == "Success") GitResult.Success else GitResult.Other(value.asStringValue().asString())
    val entry = value.asMapValue().map().entries.single()
    val kind = entry.key.asStringValue().asString()
    val body = entry.value
    fun Value.fields(): Map<String, Value> = asMapValue().map().entries.associate { (key, item) -> key.asStringValue().asString() to item }
    fun Value?.optString(): String? = this?.takeUnless { it.isNilValue }?.asStringValue()?.asString()
    return when (kind) {
        "Status" -> {
            val fields = body.fields()
            GitResult.Status(
                branch = fields["branch"].optString(),
                upstream = fields["upstream"].optString(),
                ahead = fields.getValue("ahead").asIntegerValue().toInt(),
                behind = fields.getValue("behind").asIntegerValue().toInt(),
                entries = fields.getValue("entries").asArrayValue().list().map { item ->
                    val values = item.fields()
                    GitStatusEntry(
                        path = values.getValue("path").asStringValue().asString(),
                        originalPath = values["original_path"].optString(),
                        indexStatus = values.getValue("index_status").asStringValue().asString(),
                        worktreeStatus = values.getValue("worktree_status").asStringValue().asString(),
                        status = GitFileStatus.valueOf(values.getValue("status").asStringValue().asString()),
                    )
                },
            )
        }
        "Diff" -> {
            val fields = body.fields()
            GitResult.Diff(
                fields.getValue("path").asStringValue().asString(),
                fields.getValue("staged").asBooleanValue().boolean,
                fields.getValue("patch").asStringValue().asString(),
            )
        }
        "Branches" -> GitResult.Branches(
            body.asArrayValue().list().map { item ->
                val values = item.fields()
                GitBranch(
                    values.getValue("name").asStringValue().asString(),
                    values.getValue("is_current").asBooleanValue().boolean,
                    values["upstream"].optString(),
                )
            },
        )
        "Commits" -> GitResult.Commits(
            body.asArrayValue().list().map { item ->
                val values = item.fields()
                GitCommit(
                    values.getValue("hash").asStringValue().asString(),
                    values.getValue("author").asStringValue().asString(),
                    values.getValue("timestamp").asIntegerValue().toLong(),
                    values.getValue("subject").asStringValue().asString(),
                )
            },
        )
        "Text" -> GitResult.Text(body.asStringValue().asString())
        else -> GitResult.Other(kind)
    }
}
