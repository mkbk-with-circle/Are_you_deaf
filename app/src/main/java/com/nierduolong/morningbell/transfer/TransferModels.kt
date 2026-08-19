package com.nierduolong.morningbell.transfer

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class TransferDocument(
    val uri: String,
    val name: String,
    val size: Long,
    val mimeType: String?,
    val lastModified: Long,
)

sealed interface TransferSelection {
    data class Documents(val items: List<TransferDocument>) : TransferSelection

    data class Tree(
        val uri: String,
        val label: String,
        val removable: Boolean,
    ) : TransferSelection
}

enum class TransferNetworkMode {
    AUTO_HOTSPOT,
    CURRENT_NETWORK,
}

object NearbyTransferCoordinator {
    sealed interface State {
        data object Idle : State

        data class Starting(val message: String) : State

        data class Sharing(
            val selectionLabel: String,
            val urls: List<String>,
            val ssid: String?,
            val passphrase: String?,
            val startedAt: Long,
            val activeClients: Int,
            val transferredBytes: Long,
        ) : State

        data class Failed(val message: String) : State
    }

    private val mutableState = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = mutableState.asStateFlow()

    internal fun update(value: State) {
        mutableState.value = value
    }

    fun dismissFailure() {
        if (mutableState.value is State.Failed) mutableState.value = State.Idle
    }
}

/** 页面和系统 ACTION_SEND 共用一个选择状态；文件本体从不复制进应用。 */
object TransferSelectionStore {
    private val mutableSelection = MutableStateFlow<TransferSelection?>(null)
    val selection: StateFlow<TransferSelection?> = mutableSelection.asStateFlow()

    fun set(value: TransferSelection?) {
        mutableSelection.value = value
    }

    fun inspectDocuments(context: Context, uris: List<Uri>): TransferSelection.Documents? {
        val distinct = uris.distinctBy(Uri::toString).take(MAX_SELECTED_FILES)
        if (distinct.isEmpty()) return null
        val raw = distinct.mapIndexed { index, uri -> inspectDocument(context.contentResolver, uri, index) }
        val names = uniqueNames(raw.map { it.name })
        return TransferSelection.Documents(raw.mapIndexed { index, item -> item.copy(name = names[index]) })
    }

    fun inspectTree(context: Context, uri: Uri, removable: Boolean): TransferSelection.Tree {
        val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull().orEmpty()
        val volume = id.substringBefore(':', "")
        val label = when {
            removable && volume.isNotBlank() -> "相机卡 / U 盘 $volume"
            removable -> "相机卡 / U 盘"
            volume.equals("primary", ignoreCase = true) -> "手机文件夹"
            volume.isNotBlank() -> "存储 $volume"
            else -> "已选文件夹"
        }
        return TransferSelection.Tree(uri.toString(), label, removable)
    }

    fun acceptShareIntent(context: Context, intent: Intent): Boolean {
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.parcelableUri(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.parcelableUriList(Intent.EXTRA_STREAM)
            else -> emptyList()
        }
        if (uris.isEmpty()) return false
        uris.forEach { tryPersistRead(context, it, intent.flags) }
        mutableSelection.value = inspectDocuments(context, uris)
        return mutableSelection.value != null
    }

    fun tryPersistRead(context: Context, uri: Uri, incomingFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION) {
        val flags = incomingFlags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) return
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    private fun inspectDocument(resolver: ContentResolver, uri: Uri, index: Int): TransferDocument {
        var name: String? = null
        var size = -1L
        var modified = 0L
        runCatching {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = cursor.getString(it) }
                    cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let { size = cursor.getLong(it) }
                    cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                        .takeIf { it >= 0 && !cursor.isNull(it) }
                        ?.let { modified = cursor.getLong(it) }
                }
            }
        }
        if (size <= 0) {
            val probed = runCatching { resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L }.getOrDefault(-1L)
            if (probed >= 0) size = probed
        }
        val fallback = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "file-${index + 1}.bin"
        return TransferDocument(
            uri = uri.toString(),
            name = safeDisplayName(name ?: fallback),
            size = size,
            mimeType = resolver.getType(uri),
            lastModified = modified,
        )
    }

    private fun safeDisplayName(value: String): String {
        val cleaned = value.replace('/', '_').replace('\\', '_').replace('\u0000', '_').trim()
        return cleaned.take(MAX_NAME_CHARS).ifBlank { "shared.bin" }
    }

    internal fun uniqueNames(values: List<String>): List<String> {
        val used = mutableSetOf<String>()
        return values.map { original ->
            val clean = safeDisplayName(original)
            if (used.add(clean)) return@map clean
            val dot = clean.lastIndexOf('.')
            val stem = if (dot > 0) clean.substring(0, dot) else clean
            val suffix = if (dot > 0) clean.substring(dot) else ""
            var number = 2
            var candidate: String
            do {
                candidate = "${stem.take((MAX_NAME_CHARS - suffix.length - 8).coerceAtLeast(1))}-$number$suffix"
                number++
            } while (!used.add(candidate))
            candidate
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.parcelableUri(key: String): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getParcelableExtra(key, Uri::class.java) else getParcelableExtra(key)

    @Suppress("DEPRECATION")
    private fun Intent.parcelableUriList(key: String): List<Uri> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableArrayListExtra(key, Uri::class.java).orEmpty()
        } else {
            getParcelableArrayListExtra<Uri>(key).orEmpty()
        }

    private const val MAX_SELECTED_FILES = 2_000
    private const val MAX_NAME_CHARS = 180
}

object TransferSelectionCodec {
    fun encode(selection: TransferSelection): String = when (selection) {
        is TransferSelection.Tree -> JSONObject()
            .put("type", "tree")
            .put("uri", selection.uri)
            .put("label", selection.label)
            .put("removable", selection.removable)
            .toString()

        is TransferSelection.Documents -> JSONObject()
            .put("type", "documents")
            .put(
                "items",
                JSONArray().apply {
                    selection.items.forEach { item ->
                        put(
                            JSONObject()
                                .put("uri", item.uri)
                                .put("name", item.name)
                                .put("size", item.size)
                                .put("mime", item.mimeType)
                                .put("modified", item.lastModified),
                        )
                    }
                },
            ).toString()
    }

    fun decode(raw: String?): TransferSelection? = runCatching {
        val json = JSONObject(raw ?: return null)
        when (json.getString("type")) {
            "tree" -> TransferSelection.Tree(
                uri = json.getString("uri"),
                label = json.optString("label", "已选文件夹"),
                removable = json.optBoolean("removable", false),
            )

            "documents" -> {
                val array = json.getJSONArray("items")
                val items = buildList {
                    for (index in 0 until array.length()) {
                        val item = array.getJSONObject(index)
                        add(
                            TransferDocument(
                                uri = item.getString("uri"),
                                name = item.getString("name"),
                                size = item.optLong("size", -1L),
                                mimeType = item.optString("mime").takeIf { it.isNotBlank() && it != "null" },
                                lastModified = item.optLong("modified", 0L),
                            ),
                        )
                    }
                }
                TransferSelection.Documents(items)
            }

            else -> null
        }
    }.getOrNull()
}

internal fun TransferSelection.label(): String = when (this) {
    is TransferSelection.Tree -> label
    is TransferSelection.Documents -> if (items.size == 1) items.first().name else "${items.size} 个文件"
}

internal fun TransferSelection.estimatedBytes(): Long = when (this) {
    is TransferSelection.Tree -> -1L
    is TransferSelection.Documents -> items.map { it.size }.filter { it > 0 }.sum()
}
