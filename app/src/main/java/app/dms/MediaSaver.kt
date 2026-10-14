package app.dms

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.widget.Toast
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Saves images/videos to Pictures/DMs (other files to Download/DMs).
 * Never logs URLs, cookies or content.
 */
class MediaSaver(context: Context) {

    private val app = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun download(url: String, mimeHint: String?, userAgent: String) {
        toast(R.string.saving)
        val cookies = CookieManager.getInstance().getCookie(url)
        executor.execute {
            val ok = try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", userAgent)
                    setRequestProperty("Referer", "https://www.instagram.com/")
                    if (!cookies.isNullOrEmpty()) setRequestProperty("Cookie", cookies)
                }
                try {
                    if (conn.responseCode !in 200..299) {
                        false
                    } else {
                        val mime = resolveMime(conn.contentType, mimeHint, url)
                        conn.inputStream.use { write(it, mime) }
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                false
            }
            main.post { toast(if (ok) R.string.saved else R.string.save_failed) }
        }
    }

    /** Saves a `data:<mime>;base64,<payload>` URL produced by the page. */
    fun saveDataUrl(dataUrl: String) {
        toast(R.string.saving)
        executor.execute {
            val ok = try {
                val comma = dataUrl.indexOf(',')
                val header = dataUrl.substring(5, comma) // after "data:"
                val mime = header.substringBefore(';').ifEmpty { "application/octet-stream" }
                val bytes = if (header.endsWith(";base64")) {
                    Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
                } else {
                    java.net.URLDecoder.decode(dataUrl.substring(comma + 1), "UTF-8").toByteArray()
                }
                ByteArrayInputStream(bytes).use { write(it, mime) }
            } catch (e: Exception) {
                false
            }
            main.post { toast(if (ok) R.string.saved else R.string.save_failed) }
        }
    }

    private fun resolveMime(contentType: String?, hint: String?, url: String): String {
        val fromHeader = contentType?.substringBefore(';')?.trim()?.lowercase()
        if (!fromHeader.isNullOrEmpty() && fromHeader != "application/octet-stream") return fromHeader
        val ext = MimeTypeMap.getFileExtensionFromUrl(url)
        val fromExt = ext?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.lowercase()) }
        return fromExt ?: hint?.takeIf { it.contains('/') } ?: "image/jpeg"
    }

    private fun write(input: InputStream, mime: String): Boolean {
        val isImage = mime.startsWith("image/")
        val isVideo = mime.startsWith("video/")
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            ?: if (isVideo) "mp4" else if (isImage) "jpg" else "bin"
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val name = "DMs_$stamp.$ext"
        val folder = if (isImage || isVideo) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOWNLOADS

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = app.contentResolver
            val collection = when {
                isVideo -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                isImage -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/DMs")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: return false
            return try {
                val out = resolver.openOutputStream(uri) ?: throw IllegalStateException()
                out.use { input.copyTo(it) }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                true
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                false
            }
        }

        // Android 8–9: write to the public folder (needs WRITE_EXTERNAL_STORAGE).
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(folder), "DMs")
        if (!dir.exists() && !dir.mkdirs()) return false
        val file = File(dir, name)
        file.outputStream().use { input.copyTo(it) }
        MediaScannerConnection.scanFile(app, arrayOf(file.absolutePath), arrayOf(mime), null)
        return true
    }

    private fun toast(res: Int) = Toast.makeText(app, res, Toast.LENGTH_SHORT).show()
}
