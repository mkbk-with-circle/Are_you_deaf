package com.nierduolong.morningbell.transfer

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

data class TransferSafNode(
    val documentId: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val lastModified: Long,
    val isDirectory: Boolean,
)

/**
 * 相机卡和 U 盘只通过系统 SAF 授权访问。刻意不把 URI 猜成 /storage/UUID：不同厂商、
 * 文件系统和 USB provider 的真实路径并不稳定，直接猜路径既会失效，也会越过用户选择的范围。
 */
object TransferSafTree {
    private val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )

    fun isReadable(resolver: ContentResolver, treeUri: Uri): Boolean =
        runCatching { root(resolver, treeUri) != null }.getOrDefault(false)

    fun root(resolver: ContentResolver, treeUri: Uri): TransferSafNode? {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        return queryNode(resolver, documentUri(treeUri, rootId), rootId)
    }

    fun listChildren(
        resolver: ContentResolver,
        treeUri: Uri,
        documentId: String,
    ): List<TransferSafNode> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val out = ArrayList<TransferSafNode>()
        val cursor = resolver.query(uri, projection, null, null, null) ?: return emptyList()
        cursor.use { value ->
            while (value.moveToNext()) {
                value.toNode()?.takeIf { it.name.isNotBlank() && !it.name.startsWith('.') }?.let(out::add)
            }
        }
        return out.sortedWith(compareBy<TransferSafNode> { !it.isDirectory }.thenBy { it.name.lowercase() })
    }

    fun resolve(
        resolver: ContentResolver,
        treeUri: Uri,
        relativePath: String,
    ): TransferSafNode? {
        val parts = safePathParts(relativePath) ?: return null
        var current = root(resolver, treeUri) ?: return null
        for (part in parts) {
            if (!current.isDirectory) return null
            current = listChildren(resolver, treeUri, current.documentId).firstOrNull { it.name == part } ?: return null
        }
        return current
    }

    fun documentUri(treeUri: Uri, documentId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    fun open(
        resolver: ContentResolver,
        uri: Uri,
        offset: Long = 0L,
    ): InputStream {
        val descriptor: ParcelFileDescriptor = resolver.openFileDescriptor(uri, "r") ?: throw IOException("无法打开文件")
        val stream = FileInputStream(descriptor.fileDescriptor)
        if (offset > 0) {
            val positioned = runCatching {
                stream.channel.position(offset)
                stream.channel.position() == offset
            }.getOrDefault(false)
            if (!positioned) skipFully(stream, offset)
        }
        return object : FilterInputStream(stream) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    runCatching { descriptor.close() }
                }
            }
        }
    }

    fun safePathParts(relativePath: String): List<String>? {
        return TransferPathPolicy.split(relativePath)
    }

    private fun queryNode(
        resolver: ContentResolver,
        uri: Uri,
        fallbackId: String,
    ): TransferSafNode? {
        val cursor = resolver.query(uri, projection, null, null, null) ?: return null
        cursor.use { value ->
            if (!value.moveToFirst()) return null
            return value.toNode(fallbackId)
        }
    }

    private fun Cursor.toNode(fallbackId: String = ""): TransferSafNode? {
        val id = stringColumn(DocumentsContract.Document.COLUMN_DOCUMENT_ID).orEmpty().ifBlank { fallbackId }
        if (id.isBlank()) return null
        val mime = stringColumn(DocumentsContract.Document.COLUMN_MIME_TYPE).orEmpty()
        return TransferSafNode(
            documentId = id,
            name = stringColumn(DocumentsContract.Document.COLUMN_DISPLAY_NAME).orEmpty().ifBlank { "存储" },
            mimeType = mime,
            size = longColumn(DocumentsContract.Document.COLUMN_SIZE),
            lastModified = longColumn(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
            isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR || mime.isBlank(),
        )
    }

    private fun Cursor.stringColumn(name: String): String? =
        getColumnIndex(name).takeIf { it >= 0 && !isNull(it) }?.let(::getString)

    private fun Cursor.longColumn(name: String): Long =
        getColumnIndex(name).takeIf { it >= 0 && !isNull(it) }?.let(::getLong) ?: 0L

    private fun skipFully(input: InputStream, byteCount: Long) {
        var remaining = byteCount
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw IOException("文件短于请求位置")
            remaining -= read
        }
    }

}
