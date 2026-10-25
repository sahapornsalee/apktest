package com.sahaporn.autoplay

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

object MediaScan {
    private val VIDEO_EXT = setOf(
        "mp4", "m4v", "mkv", "mk3d", "webm", "mov", "qt", "3gp", "3g2", "ts", "mts", "m2ts", "m2t", "tp", "trp",
        "avi", "divx", "xvid", "wmv", "asf", "flv", "f4v", "mpg", "mpeg", "mpe", "m1v", "m2v", "vob",
        "ogv", "ogm", "rm", "rmvb", "dv", "mxf", "amv", "y4m", "h264", "h265", "hevc", "264", "265",
    )

    fun isVideo(name: String, type: String?): Boolean =
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

    /** Picked files; anything that is clearly not a video (by name and type) is left out. */
    fun fromUris(ctx: Context, uris: List<Uri>): List<Episode> = sorted(uris.mapNotNull {
        val doc = DocumentFile.fromSingleUri(ctx, it)
        val name = doc?.name ?: it.lastPathSegment ?: "video"
        if (isVideo(name, doc?.type)) Episode(it.toString(), name) else null
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
