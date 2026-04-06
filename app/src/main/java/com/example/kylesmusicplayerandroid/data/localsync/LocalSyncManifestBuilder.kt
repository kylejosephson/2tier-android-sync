package com.example.kylesmusicplayerandroid.data.localsync

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

object LocalSyncManifestBuilder {

    fun build(context: Context, managedRootUri: String): SyncManifestResult {
        val treeUri = Uri.parse(managedRootUri)
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalStateException("Managed root is unavailable.")

        val entries = ArrayList<SyncManifestEntry>()
        var totalSizeBytes = 0L

        fun walk(dir: DocumentFile, relativeDir: String) {
            val children = try {
                dir.listFiles()
            } catch (t: Throwable) {
                throw IllegalStateException("Unable to scan managed root.", t)
            }

            for (child in children) {
                val name = child.name?.trim().orEmpty()
                if (name.isBlank()) continue

                val relativePath = if (relativeDir.isBlank()) name else "$relativeDir/$name"

                if (child.isDirectory) {
                    walk(child, relativePath)
                    continue
                }

                if (!child.isFile) continue

                val sizeBytes = try {
                    child.length()
                } catch (_: Throwable) {
                    0L
                }

                val lastModified = try {
                    child.lastModified()
                } catch (_: Throwable) {
                    0L
                }

                entries += SyncManifestEntry(
                    relativePath = relativePath,
                    sizeBytes = sizeBytes,
                    lastModified = lastModified
                )
                totalSizeBytes += sizeBytes.coerceAtLeast(0L)
            }
        }

        walk(root, "")
        entries.sortBy { it.relativePath.lowercase() }

        return SyncManifestResult(
            meta = SyncManifestMeta(
                entryCount = entries.size,
                totalSizeBytes = totalSizeBytes,
                generatedAtEpochMs = System.currentTimeMillis()
            ),
            entries = entries
        )
    }
}
