package com.nierduolong.morningbell.transfer

import android.content.ContentResolver
import android.net.Uri
import java.io.InputStream
import java.util.Locale

data class TransferListItem(
    val path: String,
    val name: String,
    val size: Long,
    val lastModified: Long,
    val mimeType: String,
    val kind: String,
)

data class TransferReadableEntry(
    val path: String,
    val size: Long,
    val lastModified: Long,
    val mimeType: String,
    val etagSeed: String,
    val openAt: (Long) -> InputStream,
)

interface TransferSourceCatalog {
    val label: String
    val removable: Boolean

    fun isReadable(): Boolean

    fun list(relativePath: String): List<TransferListItem>

    fun resolveFile(relativePath: String): TransferReadableEntry?

    fun listRecursive(relativePath: String, limit: Int = MAX_RECURSIVE_FILES): Pair<List<TransferListItem>, Boolean> {
        val start = relativePath.trim('/')
        val output = ArrayList<TransferListItem>()
        val pending = ArrayDeque<String>()
        pending.add(start)
        var truncated = false
        while (pending.isNotEmpty()) {
            val parent = pending.removeFirst()
            for (item in list(parent)) {
                if (item.kind == "dir") {
                    if (pending.size < MAX_PENDING_DIRECTORIES) pending.addLast(item.path) else truncated = true
                } else {
                    output += item
                    if (output.size >= limit) {
                        truncated = true
                        return output to truncated
                    }
                }
            }
        }
        return output to truncated
    }

    companion object {
        const val MAX_RECURSIVE_FILES = 8_000
        private const val MAX_PENDING_DIRECTORIES = 2_000
    }
}

class DocumentTransferCatalog(
    private val resolver: ContentResolver,
    private val documents: List<TransferDocument>,
) : TransferSourceCatalog {
    override val label: String = if (documents.size == 1) documents.first().name else "${documents.size} 个文件"
    override val removable: Boolean = false
    private val readableDocuments: List<TransferDocument> by lazy {
        documents.filter { item ->
            runCatching { resolver.openFileDescriptor(Uri.parse(item.uri), "r")?.use { true } ?: false }.getOrDefault(false)
        }
    }
    private val byName: Map<String, TransferDocument> by lazy { readableDocuments.associateBy { it.name } }

    override fun isReadable(): Boolean = readableDocuments.isNotEmpty()

    override fun list(relativePath: String): List<TransferListItem> {
        if (relativePath.trim('/').isNotEmpty()) return emptyList()
        return readableDocuments.map { item ->
            TransferListItem(
                path = item.name,
                name = item.name,
                size = item.size,
                lastModified = item.lastModified,
                mimeType = item.mimeType ?: TransferMime.typeFor(item.name),
                kind = TransferMime.kindFor(item.name, item.mimeType),
            )
        }
    }

    override fun resolveFile(relativePath: String): TransferReadableEntry? {
        val parts = TransferSafTree.safePathParts(relativePath) ?: return null
        if (parts.size != 1) return null
        val item = byName[parts.single()] ?: return null
        val uri = Uri.parse(item.uri)
        val actualSize = item.size.takeIf { it > 0 } ?: runCatching {
            resolver.openFileDescriptor(uri, "r")?.use { it.statSize }
        }.getOrNull() ?: -1L
        return TransferReadableEntry(
            path = item.name,
            size = actualSize,
            lastModified = item.lastModified,
            mimeType = item.mimeType ?: TransferMime.typeFor(item.name),
            etagSeed = "${item.uri}|$actualSize|${item.lastModified}",
            openAt = { offset -> TransferSafTree.open(resolver, uri, offset) },
        )
    }
}

class TreeTransferCatalog(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
    override val label: String,
    override val removable: Boolean,
) : TransferSourceCatalog {
    private data class CachedDirectory(val createdAt: Long, val items: List<TransferListItem>)

    private val directoryCache = object : LinkedHashMap<String, CachedDirectory>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedDirectory>?): Boolean = size > MAX_CACHED_DIRECTORIES
    }
    private val nodeCache = object : LinkedHashMap<String, TransferSafNode>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TransferSafNode>?): Boolean = size > MAX_CACHED_NODES
    }

    override fun isReadable(): Boolean = TransferSafTree.isReadable(resolver, treeUri)

    override fun list(relativePath: String): List<TransferListItem> {
        val clean = relativePath.trim('/')
        synchronized(directoryCache) {
            directoryCache[clean]?.takeIf { System.currentTimeMillis() - it.createdAt <= DIRECTORY_CACHE_MS }?.let { return it.items }
        }
        val node = if (clean.isBlank()) TransferSafTree.root(resolver, treeUri) else TransferSafTree.resolve(resolver, treeUri, clean)
        if (node?.isDirectory != true) return emptyList()
        val children = TransferSafTree.listChildren(resolver, treeUri, node.documentId).map { child ->
            val path = if (clean.isBlank()) child.name else "$clean/${child.name}"
            synchronized(nodeCache) { nodeCache[path] = child }
            TransferListItem(
                path = path,
                name = child.name,
                size = child.size,
                lastModified = child.lastModified,
                mimeType = child.mimeType.ifBlank { TransferMime.typeFor(child.name) },
                kind = if (child.isDirectory) "dir" else TransferMime.kindFor(child.name, child.mimeType),
            )
        }
        synchronized(directoryCache) { directoryCache[clean] = CachedDirectory(System.currentTimeMillis(), children) }
        return children
    }

    override fun resolveFile(relativePath: String): TransferReadableEntry? {
        val clean = relativePath.trim('/')
        if (TransferSafTree.safePathParts(clean) == null) return null
        val node = synchronized(nodeCache) { nodeCache[clean] } ?: TransferSafTree.resolve(resolver, treeUri, clean) ?: return null
        if (node.isDirectory) return null
        val uri = TransferSafTree.documentUri(treeUri, node.documentId)
        val actualSize = node.size.takeIf { it > 0 } ?: runCatching {
            resolver.openFileDescriptor(uri, "r")?.use { it.statSize }
        }.getOrNull() ?: -1L
        return TransferReadableEntry(
            path = clean,
            size = actualSize,
            lastModified = node.lastModified,
            mimeType = node.mimeType.ifBlank { TransferMime.typeFor(node.name) },
            etagSeed = "$treeUri|${node.documentId}|$actualSize|${node.lastModified}",
            openAt = { offset -> TransferSafTree.open(resolver, uri, offset) },
        )
    }

    companion object {
        private const val DIRECTORY_CACHE_MS = 30_000L
        private const val MAX_CACHED_DIRECTORIES = 64
        private const val MAX_CACHED_NODES = 8_000
    }
}

object TransferMime {
    fun kindFor(name: String, declared: String? = null): String {
        val type = declared.orEmpty().lowercase(Locale.ROOT)
        val lower = name.lowercase(Locale.ROOT)
        return when {
            type == "vnd.android.document/directory" -> "dir"
            isRaw(lower) -> "raw"
            type.startsWith("image/") || isImage(lower) -> "image"
            type.startsWith("video/") || isVideo(lower) -> "video"
            type.startsWith("audio/") || isAudio(lower) -> "audio"
            else -> "file"
        }
    }

    fun typeFor(name: String): String {
        val lower = name.lowercase(Locale.ROOT)
        return when {
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".heic") || lower.endsWith(".heif") -> "image/heic"
            lower.endsWith(".mp4") || lower.endsWith(".m4v") -> "video/mp4"
            lower.endsWith(".mov") -> "video/quicktime"
            lower.endsWith(".mkv") -> "video/x-matroska"
            lower.endsWith(".webm") -> "video/webm"
            lower.endsWith(".mp3") -> "audio/mpeg"
            lower.endsWith(".wav") -> "audio/wav"
            lower.endsWith(".pdf") -> "application/pdf"
            else -> "application/octet-stream"
        }
    }

    fun isImage(name: String): Boolean {
        val value = name.lowercase(Locale.ROOT)
        return value.endsWith(".jpg") || value.endsWith(".jpeg") || value.endsWith(".png") ||
            value.endsWith(".webp") || value.endsWith(".bmp")
    }

    private fun isVideo(value: String): Boolean =
        value.endsWith(".mp4") || value.endsWith(".mov") || value.endsWith(".m4v") ||
            value.endsWith(".webm") || value.endsWith(".mkv")

    private fun isAudio(value: String): Boolean =
        value.endsWith(".mp3") || value.endsWith(".wav") || value.endsWith(".m4a") || value.endsWith(".flac")

    private fun isRaw(value: String): Boolean =
        listOf(".cr2", ".cr3", ".nef", ".arw", ".dng", ".raf", ".orf", ".rw2", ".pef", ".srw")
            .any(value::endsWith)
}
