package link.infra.packwiz.installer

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import link.infra.packwiz.installer.target.ClientHolder
import link.infra.packwiz.installer.target.path.HttpUrlPath
import link.infra.packwiz.installer.target.path.PackwizFilePath
import link.infra.packwiz.installer.target.path.PackwizPath
import link.infra.packwiz.installer.ui.data.LauncherBranding
import link.infra.packwiz.installer.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okio.BufferedSource
import okio.Path.Companion.toOkioPath
import java.awt.image.BufferedImage
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream

private const val CONFIG_NAME = "packwiz-launcher.json"
private const val CONFIG_BYTES = 16L * 1024
private const val IMAGE_BYTES = 8L * 1024 * 1024
private const val IMAGE_PIXELS = 8L * 1024 * 1024

internal fun loadLauncherBranding(packFile: PackwizPath<*>, packName: String, client: ClientHolder): LauncherBranding {
	val config = try {
		val bytes = readBrandingFile(packFile, CONFIG_NAME, CONFIG_BYTES, client) ?: return LauncherBranding(packName)
		JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
	} catch (e: Exception) {
		Log.warn("Could not load $CONFIG_NAME", e)
		return LauncherBranding(packName)
	}
	val title = config.string("title") ?: packName
	val subtitle = config.string("subtitle")
	val background = config.string("background")?.let { path ->
		try {
			if (!isRelativeAssetPath(path)) throw IOException("Expected a path inside the pack directory")
			readBrandingFile(packFile, path, IMAGE_BYTES, client)?.let(::decodeBackground)
		} catch (e: Exception) {
			Log.warn("Could not load launcher background", e)
			null
		}
	}
	return LauncherBranding(title, subtitle, background)
}

private fun JsonObject.string(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
	?.asString?.trim()?.takeIf { it.isNotEmpty() }

private fun isRelativeAssetPath(path: String): Boolean {
	// Reject URI escapes as well as literal separators used by Windows, URLs and parent traversals.
	if (path.any { it.isISOControl() || it in "\\:%?#" }) return false
	return path.split('/').all { it.isNotEmpty() && it != ".." }
}

private fun readBrandingFile(packFile: PackwizPath<*>, relative: String, limit: Long, client: ClientHolder): ByteArray? {
	return when (packFile) {
		is PackwizFilePath -> {
			val root = packFile.nioPath.toAbsolutePath().parent.toRealPath()
			val file = root.resolve(relative)
			if (!Files.isRegularFile(file)) return null
			val realFile = file.toRealPath()
			if (!realFile.startsWith(root)) throw IOException("Asset resolves outside the pack directory")
			PackwizFilePath(realFile.toOkioPath()).source(client).use { it.readLimited(limit) }
		}
		is HttpUrlPath -> {
			val root = packFile.toString().toHttpUrl().resolve(".") ?: return null
			val url = root.newBuilder().addPathSegments(relative).build()
			// A redirect can lead outside the pack origin or directory. Branding is optional.
			val http = client.okHttpClient.newBuilder().followRedirects(false).callTimeout(5, TimeUnit.SECONDS).build()
			val request = Request.Builder().url(url).header("User-Agent", "packwiz-installer").build()
			http.newCall(request).execute().use { response ->
				if (!response.isSuccessful) return null
				response.body?.source()?.readLimited(limit)
			}
		}
		else -> null
	}
}

private fun BufferedSource.readLimited(limit: Long): ByteArray {
	if (request(limit + 1)) throw IOException("Asset exceeds $limit bytes")
	return readByteArray()
}

private fun decodeBackground(bytes: ByteArray): BufferedImage? {
	MemoryCacheImageInputStream(bytes.inputStream()).use { stream ->
		val readers = ImageIO.getImageReaders(stream)
		if (!readers.hasNext()) return null
		val reader = readers.next()
		try {
			reader.setInput(stream, true, true)
			val width = reader.getWidth(0)
			val height = reader.getHeight(0)
			if (width !in 1..8192 || height !in 1..8192 || width.toLong() * height > IMAGE_PIXELS) {
				throw IOException("Launcher background dimensions are too large")
			}
			return reader.read(0)
		} finally {
			reader.dispose()
		}
	}
}
