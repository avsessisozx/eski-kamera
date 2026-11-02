package app.eskikamera

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File

class MediaItem(val uri: Uri, val isVideo: Boolean, val name: String, val dateMs: Long, val size: Long)

/** Uygulamanın kendi klasörlerindeki (EskiKamera) çekimlere erişim. */
object Gallery {
    const val DIR_PHOTO = "Pictures/EskiKamera/"
    const val DIR_VIDEO = "Movies/EskiKamera/"
    const val DIR_ORIG = "Pictures/EskiKamera/Orijinaller/"

    private fun query(ctx: Context, collection: Uri, video: Boolean, relDir: String): List<MediaItem> {
        val out = ArrayList<MediaItem>()
        val proj = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.SIZE)
        try {
            ctx.contentResolver.query(collection, proj, "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf(relDir),
                "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
                while (c.moveToNext()) {
                    out.add(MediaItem(ContentUris.withAppendedId(collection, c.getLong(0)), video, c.getString(1) ?: "", c.getLong(2) * 1000L, c.getLong(3)))
                }
            }
        } catch (e: Exception) {
            ErrorLog.add("Galeri sorgusu hatası: ${e.message}")
        }
        return out
    }

    /** En yeniden eskiye tüm foto ve videolar. */
    fun list(ctx: Context): List<MediaItem> {
        val all = ArrayList<MediaItem>()
        all.addAll(query(ctx, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, DIR_PHOTO))
        all.addAll(query(ctx, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, DIR_VIDEO))
        all.sortByDescending { it.dateMs }
        return all
    }

    /** Bir fotoğrafın "orijinal" eşi (varsa). */
    fun originalFor(ctx: Context, item: MediaItem): Uri? {
        if (item.isVideo) return null
        val base = item.name.substringBeforeLast('.')
        val found = query(ctx, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, DIR_ORIG).firstOrNull { it.name.startsWith(base + "_orijinal") }
        return found?.uri
    }

    fun save(ctx: Context, file: File, name: String, mime: String, relDir: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relDir.trimEnd('/'))
        }
        val collection = when {
            mime.startsWith("video") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            mime.startsWith("image") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Files.getContentUri("external")
        }
        val uri = ctx.contentResolver.insert(collection, values) ?: throw ProcessException("Galeriye kayıt oluşturulamadı")
        ctx.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            ?: throw ProcessException("Galeri dosyası yazılamadı")
        return uri
    }

    fun delete(ctx: Context, uri: Uri): Boolean = try {
        ctx.contentResolver.delete(uri, null, null) > 0
    } catch (e: SecurityException) {
        false
    } catch (e: Exception) {
        false
    }
}
