package com.example.kylesmusicplayerandroid.data.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedInputStream
import java.io.BufferedOutputStream

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

// ------------------------------------------------------------
// Resolve relative path under SAF root
// ------------------------------------------------------------
fun resolveArtworkPath(
    root: DocumentFile?,
    relativePath: String?
): Uri? {

    if (root == null || relativePath.isNullOrBlank()) return null

    var current: DocumentFile? = root

    val parts = relativePath.split("/").filter { it.isNotBlank() }

    for (part in parts) {
        current = current?.findFile(part)
        if (current == null) return null
    }

    return current?.uri
}