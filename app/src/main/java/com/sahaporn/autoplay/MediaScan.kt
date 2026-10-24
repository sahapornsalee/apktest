package com.sahaporn.autoplay

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

object MediaScan {
    private val VIDEO_EXT = setOf(
        "mp4", "mkv", "avi", "mov", "webm", "m4v", "3gp", "ts", "flv", "wmv", "mpg", "mpeg",
    )

    private fun isVideo(name: String, type: String?): Boolean =
        type?.startsWith("video/") == true || name.substringAfterLast('.', "").lowercase() in VIDEO_EXT

    fun persist(ctx: Context, uri: Uri) {
        try {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // Some providers don't offer persistable grants; playback still works this session.
        }
    }

    fun folderName(ctx: Context, treeUri: Uri): String? = DocumentFile.fromTreeUri(ctx, treeUri)?.name

    /** Lists every video in the folder and its subfolders (e.g. one subfolder per season), in natural order. */
    fun scanTree(ctx: Context, treeUri: Uri): List<Episode> {
        val root = DocumentFile.fromTreeUri(ctx, treeUri) ?: return emptyList()
        val out = mutableListOf<Episode>()
        fun walk(dir: DocumentFile, prefix: String) {
            for (f in dir.listFiles()) {
                val name = f.name ?: continue
                if (f.isDirectory) {
                    walk(f, "$prefix$name/")
                } else if (isVideo(name, f.type)) {
                    out.add(Episode(f.uri.toString(), prefix + name))
                }
            }
        }
        walk(root, "")
        return sorted(out)
    }

    fun fromUris(ctx: Context, uris: List<Uri>): List<Episode> = sorted(uris.map {
        Episode(it.toString(), DocumentFile.fromSingleUri(ctx, it)?.name ?: it.lastPathSegment ?: "video")
    })

    fun sorted(list: List<Episode>): List<Episode> =
        list.sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }
}

/** Compares strings so that "EP2" comes before "EP10". */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ei = i
                while (ei < a.length && a[ei].isDigit()) ei++
                var ej = j
                while (ej < b.length && b[ej].isDigit()) ej++
                val na = a.substring(i, ei).trimStart('0')
                val nb = b.substring(j, ej).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
                i = ei
                j = ej
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}
