package com.example.kylesmusicplayerandroid.data.storage

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.util.Locale

data class ManagedRelativePath(
    val normalizedPath: String,
    val segments: List<String>
) {
    val fileName: String get() = segments.last()
    val parentSegments: List<String> get() = segments.dropLast(1)
}

// ------------------------------------------------------------
// Read file text safely from DocumentFile
// ------------------------------------------------------------
fun DocumentFile.readTextSafely(ctx: Context): String? =
    try {
        ctx.contentResolver.openInputStream(uri)
            ?.bufferedReader()
            ?.use { it.readText() }
    } catch (e: Exception) {
        null
    }

// ------------------------------------------------------------
// Write byte array safely to DocumentFile
// ------------------------------------------------------------
fun DocumentFile.writeBytesSafely(ctx: Context, bytes: ByteArray): Boolean =
    try {
        ctx.contentResolver.openOutputStream(uri)
            ?.use { it.write(bytes) } != null
    } catch (e: Exception) {
        false
    }

// ------------------------------------------------------------
// Copy one DocumentFile → another DocumentFile
// ------------------------------------------------------------
fun DocumentFile.copyTo(ctx: Context, dest: DocumentFile): Boolean =
    try {
        val input = ctx.contentResolver.openInputStream(uri) ?: return false
        val output = ctx.contentResolver.openOutputStream(dest.uri) ?: return false

        BufferedInputStream(input).use { bis ->
            BufferedOutputStream(output).use { bos ->
                val buffer = ByteArray(4096)
                while (true) {
                    val read = bis.read(buffer)
                    if (read <= 0) break
                    bos.write(buffer, 0, read)
                }
                bos.flush()
            }
        }
        true
    } catch (e: Exception) {
        false
    }

// ------------------------------------------------------------
// Find direct child by name
// ------------------------------------------------------------
fun DocumentFile.findFile(name: String): DocumentFile? =
    listFiles().firstOrNull { it.name == name }

fun parseManagedRelativePath(rawPath: String?): ManagedRelativePath? {
    val trimmed = rawPath?.trim().orEmpty()
    if (trimmed.isBlank()) return null

    val normalized = trimmed.replace('\\', '/')
    if (normalized.startsWith("/")) return null
    if (normalized.startsWith("~/")) return null
    if (normalized.contains('\u0000')) return null
    if (Regex("^[A-Za-z]:").containsMatchIn(normalized)) return null

    val segments = normalized.split("/")
    if (segments.isEmpty()) return null
    if (segments.any { it.isBlank() || it == "." || it == ".." }) return null

    return ManagedRelativePath(
        normalizedPath = segments.joinToString("/"),
        segments = segments
    )
}

fun resolveRelativeDocument(
    root: DocumentFile,
    relativePath: ManagedRelativePath
): DocumentFile? {
    var current: DocumentFile? = root
    for (segment in relativePath.segments) {
        current = current?.findFile(segment) ?: return null
    }
    return current
}

fun ensureParentDirectories(
    root: DocumentFile,
    relativePath: ManagedRelativePath
): DocumentFile? {
    var current: DocumentFile = root

    for (segment in relativePath.parentSegments) {
        val existing = current.findFile(segment)
        current = when {
            existing == null -> current.createDirectory(segment) ?: return null
            existing.isDirectory -> existing
            else -> return null
        }
    }

    return current
}

fun guessMimeTypeFromFileName(fileName: String): String {
    val extension = fileName.substringAfterLast('.', "").lowercase(Locale.US)
    if (extension.isBlank()) return "application/octet-stream"

    val mapped = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    if (!mapped.isNullOrBlank()) return mapped

    return when (extension) {
        "flac" -> "audio/flac"
        "m4a" -> "audio/mp4"
        "wma" -> "audio/x-ms-wma"
        else -> "application/octet-stream"
    }
}

// ------------------------------------------------------------
// Resolve relative path under SAF root
// ------------------------------------------------------------
fun resolveArtworkPath(
    root: DocumentFile?,
    relativePath: String?
): Uri? {

    if (root == null || relativePath.isNullOrBlank()) return null

    val parsed = parseManagedRelativePath(relativePath) ?: return null
    return resolveRelativeDocument(root, parsed)?.uri
}
