package link.infra.packwiz.installer

import link.infra.packwiz.installer.metadata.hash.Hash
import link.infra.packwiz.installer.metadata.hash.HashFormat
import link.infra.packwiz.installer.target.path.PackwizFilePath
import link.infra.packwiz.installer.ui.data.ManualDownload
import okio.blackholeSink
import okio.buffer
import okio.source
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

internal class ManualDownloadScanner(
	private val tasks: Map<ManualDownload, DownloadTask>,
	private val packFolder: PackwizFilePath
) {
	private val installed = mutableSetOf<ManualDownload>()
	private val cache = mutableMapOf<Path, CachedHashes>()
	private var currentDirectory: Path? = null

	private data class Stamp(val size: Long, val modified: FileTime, val fileKey: Any?)
	private class CachedHashes(val stamp: Stamp, val values: MutableMap<HashFormat<*>, Hash<*>> = mutableMapOf())

	fun scan(directory: Path): Set<ManualDownload> {
		if (directory != currentDirectory) {
			currentDirectory = directory
			cache.clear()
		}
		val pending = (tasks.keys - installed).groupBy { it.filename.substringAfterLast('.', "").lowercase() }
		try {
			Files.newDirectoryStream(directory).use { entries ->
				for (entry in entries) {
					if (Thread.currentThread().isInterrupted || installed.size == tasks.size) break
					val extension = entry.fileName.toString().substringAfterLast('.', "").lowercase()
					val possible = pending[extension]?.filter { it !in installed } ?: continue
					if (possible.isNotEmpty()) checkFile(entry, possible)
				}
			}
		} catch (e: DirectoryIteratorException) {
			throw e.cause!!
		}
		return installed.toSet()
	}

	private fun checkFile(entry: Path, possible: List<ManualDownload>) {
		val attributes = try {
			Files.readAttributes(entry, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
		} catch (e: IOException) {
			return // A browser may rename a file while scanning.
		}
		if (!attributes.isRegularFile) return
		val stamp = Stamp(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey())
		val hashes = cache[entry]?.takeIf { it.stamp == stamp } ?: CachedHashes(stamp).also { cache[entry] = it }

		for ((format, downloads) in possible.groupBy { it.hash.type }) {
			val hash = try {
				hashes.values.getOrPut(format) {
					format.source(Files.newInputStream(entry).source()).use { source ->
						source.buffer().readAll(blackholeSink())
						source.hash
					}
				}
			} catch (e: IOException) {
				continue // Retry files still locked by the browser on the next scan.
			}
			for (download in downloads.filter { it.hash == hash }) {
				if (tasks.getValue(download).importManualFile(packFolder, entry)) {
					installed.add(download)
				} else {
					cache.remove(entry)
				}
			}
		}
	}
}
