package link.infra.packwiz.installer

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import link.infra.packwiz.installer.ui.gui.LauncherIdentity
import link.infra.packwiz.installer.ui.data.LauncherBranding
import link.infra.packwiz.installer.util.Log
import okio.BufferedSource
import okio.buffer
import okio.source
import java.awt.Image
import java.awt.Toolkit
import java.awt.image.ImageObserver
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.imageio.stream.FileImageInputStream

internal const val LAUNCHER_CONFIG_NAME = "packwiz-launcher.json"
private const val CONFIG_BYTES = 16L * 1024
private const val IMAGE_BYTES = 8L * 1024 * 1024
private const val GIF_BYTES = 128L * 1024 * 1024
private const val IMAGE_PIXELS = 8L * 1024 * 1024

internal fun loadLauncherBranding(folder: Path): LauncherBranding {
	val root = folder.toAbsolutePath().normalize()
	val config = try {
		Files.createDirectories(root)
		val path = root.resolve(LAUNCHER_CONFIG_NAME)
		if (Files.notExists(path)) {
			try {
				val defaults = """
					{
					  "title": "${LauncherIdentity.NAME}",
					  "subtitle": "",
					  "background": ""
					}
				""".trimIndent() + "\n"
				Files.write(path, defaults.toByteArray(Charsets.UTF_8), CREATE_NEW, WRITE)
			} catch (_: FileAlreadyExistsException) {
				// Another launcher already created the settings.
			}
		}
		val bytes = readBrandingFile(root, LAUNCHER_CONFIG_NAME, CONFIG_BYTES) ?: return LauncherBranding("")
		JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
	} catch (e: Exception) {
		Log.warn("Could not load ${root.resolve(LAUNCHER_CONFIG_NAME)}", e)
		return LauncherBranding("")
	}
	val title = config.string("title").orEmpty()
	val subtitle = config.string("subtitle")
	val background = config.string("background")?.let { path ->
		try {
			if (!isRelativeAssetPath(path)) throw IOException("Expected a path inside the pack directory")
			findLocalAsset(root, path)?.let(::loadBackground)
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

private fun readBrandingFile(directory: Path, relative: String, limit: Long): ByteArray? {
	val file = findLocalAsset(directory, relative) ?: return null
	return Files.newInputStream(file).source().buffer().use { it.readLimited(limit) }
}

private fun findLocalAsset(directory: Path, relative: String): Path? {
	val root = directory.toRealPath()
	val file = root.resolve(relative)
	if (!Files.isRegularFile(file)) return null
	val realFile = file.toRealPath()
	if (!realFile.startsWith(root)) throw IOException("Asset resolves outside the launcher directory")
	return realFile
}

private fun BufferedSource.readLimited(limit: Long): ByteArray {
	if (request(limit + 1)) throw IOException("Asset exceeds $limit bytes")
	return readByteArray()
}

private fun loadBackground(file: Path): Image? {
	FileImageInputStream(file.toFile()).use { stream ->
		val readers = ImageIO.getImageReaders(stream)
		if (!readers.hasNext()) return null
		val reader = readers.next()
		try {
			val animated = reader.formatName.equals("gif", ignoreCase = true)
			val limit = if (animated) GIF_BYTES else IMAGE_BYTES
			if (stream.length() > limit) throw IOException("Asset exceeds $limit bytes")
			reader.setInput(stream, true, !animated)
			checkBackgroundDimensions(reader.getWidth(0), reader.getHeight(0))
			if (animated) {
				val screen = reader.streamMetadata?.getAsTree("javax_imageio_gif_stream_1.0")?.let {
					(it as javax.imageio.metadata.IIOMetadataNode).getElementsByTagName("LogicalScreenDescriptor").item(0)
				} ?: throw IOException("GIF has no logical screen")
				checkBackgroundDimensions(
					screen.attributes.getNamedItem("logicalScreenWidth").nodeValue.toInt(),
					screen.attributes.getNamedItem("logicalScreenHeight").nodeValue.toInt()
				)
				// AWT decodes consecutive frames with their delays and disposal rules as the image is displayed.
				return prepareGif(file)
			}
			return reader.read(0)
		} finally {
			reader.dispose()
		}
	}
}

private fun prepareGif(file: Path): Image {
	val toolkit = Toolkit.getDefaultToolkit()
	val image = toolkit.createImage(file.toString())
	val firstFrame = CompletableFuture<Boolean>()
	val observer = ImageObserver { _, flags, _, _, _, _ ->
		when {
			flags and (ImageObserver.ERROR or ImageObserver.ABORT) != 0 -> {
				firstFrame.complete(false)
				false
			}
			flags and ImageObserver.ALLBITS != 0 -> {
				firstFrame.complete(true)
				false
			}
			else -> {
				if (flags and ImageObserver.FRAMEBITS != 0) firstFrame.complete(true)
				true
			}
		}
	}
	if (!toolkit.prepareImage(image, -1, -1, observer) && !firstFrame.get(5, TimeUnit.SECONDS)) {
		throw IOException("Could not decode GIF")
	}
	return image
}

private fun checkBackgroundDimensions(width: Int, height: Int) {
	if (width !in 1..8192 || height !in 1..8192 || width.toLong() * height > IMAGE_PIXELS) {
		throw IOException("Launcher background dimensions are too large")
	}
}
