package link.infra.packwiz.installer

import link.infra.packwiz.installer.metadata.IndexFile
import link.infra.packwiz.installer.metadata.ManifestFile
import link.infra.packwiz.installer.metadata.curseforge.CurseForgeUpdateData
import link.infra.packwiz.installer.metadata.hash.Hash
import link.infra.packwiz.installer.metadata.hash.HashFormat
import link.infra.packwiz.installer.request.RequestException
import link.infra.packwiz.installer.target.ClientHolder
import link.infra.packwiz.installer.target.Side
import link.infra.packwiz.installer.target.path.HttpUrlPath
import link.infra.packwiz.installer.target.path.PackwizFilePath
import link.infra.packwiz.installer.ui.data.ExceptionDetails
import link.infra.packwiz.installer.ui.data.IOptionDetails
import link.infra.packwiz.installer.ui.data.ManualDownload
import link.infra.packwiz.installer.util.Log
import okio.Buffer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Source
import okio.blackholeSink
import okio.buffer
import okio.sink
import okio.source
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal class DownloadTask private constructor(val metadata: IndexFile.File, val index: IndexFile, private val downloadSide: Side) : IOptionDetails {
	var cachedFile: ManifestFile.File? = null
		private set

	private var err: Exception? = null
	private var failedDownloadHash: Hash<*>? = null
	val exceptionDetails get() = err?.let { e -> ExceptionDetails(name, e) }

	fun failed() = err != null

	var alreadyUpToDate = false
		private set
	private var metadataRequired = true
	private var invalidated = false
	// If file is new or isOptional changed to true, the option needs to be presented again
	private var newOptional = true
	var completionStatus = CompletionStatus.INCOMPLETE
		private set

	enum class CompletionStatus {
		INCOMPLETE,
		DOWNLOADED,
		ALREADY_EXISTS_CACHED,
		ALREADY_EXISTS_VALIDATED,
		SKIPPED_DISABLED,
		SKIPPED_WRONG_SIDE,
		DELETED_DISABLED,
		DELETED_WRONG_SIDE;
	}

	val isOptional get() = metadata.linkedFile?.option?.optional ?: false

	fun isNewOptional() = isOptional && newOptional

	fun correctSide() = metadata.linkedFile?.side?.let { downloadSide.hasSide(it) } ?: true

	override val name get() = metadata.name

	// Ensure that an update is done if it changes from false to true, or from true to false
	override var optionValue: Boolean
		get() = cachedFile?.optionValue ?: true
		set(value) {
			if (value && !optionValue) { // Ensure that an update is done if it changes from false to true, or from true to false
				alreadyUpToDate = false
			}
			cachedFile?.optionValue = value
		}

	override val optionDescription get() = metadata.linkedFile?.option?.description ?: ""

	fun invalidate() {
		invalidated = true
		alreadyUpToDate = false
	}

	fun updateFromCache(cachedFile: ManifestFile.File?) {
		if (err != null) return

		if (cachedFile == null) {
			this.cachedFile = ManifestFile.File()
			return
		}
		this.cachedFile = cachedFile
		if (!invalidated) {
			val currHash = try {
				metadata.getHashObj(index)
			} catch (e: Exception) {
				err = e
				return
			}
			if (currHash == cachedFile.hash) { // Already up to date
				alreadyUpToDate = true
				metadataRequired = false
				completionStatus = CompletionStatus.ALREADY_EXISTS_CACHED
			}
		}
		if (cachedFile.isOptional) {
			// Because option selection dialog might set this task to true/false, metadata is always needed to download
			// the file, and to show the description and name
			metadataRequired = true
		}
	}

	fun downloadMetadata(clientHolder: ClientHolder) {
		if (err != null) return

		if (metadataRequired) {
			try {
				// Retrieve the linked metadata file
				metadata.downloadMeta(index, clientHolder)
			} catch (e: Exception) {
				err = e
				return
			}
			cachedFile?.let { cachedFile ->
				val linkedFile = metadata.linkedFile
				if (linkedFile != null) {
					if (linkedFile.option.optional) {
						if (cachedFile.isOptional) {
							// isOptional didn't change
							newOptional = false
						} else {
							// isOptional false -> true, set option to it's default value
							// TODO: preserve previous option value, somehow??
							cachedFile.optionValue = linkedFile.option.defaultValue
						}
					}
				}
				cachedFile.isOptional = isOptional
				cachedFile.onlyOtherSide = !correctSide()
			}
		}
	}

	/**
	 * Check if the file in the destination location is already valid
	 * Must be done after metadata retrieval
	 */
	fun validateExistingFile(packFolder: PackwizFilePath, clientHolder: ClientHolder) {
		if (!alreadyUpToDate) {
			try {
				// TODO: only do this for files that didn't exist before or have been modified since last full update?
				val destPath = metadata.destURI.rebase(packFolder)
				destPath.source(clientHolder).use { src ->
					// TODO: clean up duplicated code
					val hash: Hash<*>
					val fileHashFormat: HashFormat<*>
					val linkedFile = metadata.linkedFile

					if (linkedFile != null) {
						hash = linkedFile.hash
						fileHashFormat = linkedFile.download.hashFormat
					} else {
						hash = metadata.getHashObj(index)
						fileHashFormat = metadata.hashFormat(index)
					}

					val fileSource = fileHashFormat.source(src)
					fileSource.buffer().readAll(blackholeSink())
					if (hash == fileSource.hash) {
						alreadyUpToDate = true
						completionStatus = CompletionStatus.ALREADY_EXISTS_VALIDATED

						// Update the manifest file
						cachedFile = (cachedFile ?: ManifestFile.File()).also {
							try {
								it.hash = metadata.getHashObj(index)
							} catch (e: Exception) {
								err = e
								return
							}
							it.isOptional = isOptional
							it.cachedLocation = metadata.destURI.rebase(packFolder)
							metadata.linkedFile?.let { linked ->
								try {
									it.linkedFileHash = linked.hash
								} catch (e: Exception) {
									err = e
								}
							}
						}
					}
				}
			} catch (e: RequestException) {
				// Ignore exceptions; if the file doesn't exist we'll be downloading it
			} catch (e: IOException) {
				// Ignore exceptions; if the file doesn't exist we'll be downloading it
			}
		}
	}

	fun download(packFolder: PackwizFilePath, clientHolder: ClientHolder) {
		if (err != null) return

		// Exclude wrong-side and optional false files
		cachedFile?.let {
			if ((it.isOptional && !it.optionValue) || !correctSide()) {
				if (it.cachedLocation != null) {
					// Ensure wrong-side or optional false files are removed
					try {
						completionStatus = if (Files.deleteIfExists(it.cachedLocation!!.nioPath)) {
							if (correctSide()) { CompletionStatus.DELETED_DISABLED } else { CompletionStatus.DELETED_WRONG_SIDE }
						} else {
							if (correctSide()) { CompletionStatus.SKIPPED_DISABLED } else { CompletionStatus.SKIPPED_WRONG_SIDE }
						}
					} catch (e: IOException) {
						Log.warn("Failed to delete file", e)
					}
				} else {
					completionStatus =
						if (correctSide()) { CompletionStatus.SKIPPED_DISABLED }
						else { CompletionStatus.SKIPPED_WRONG_SIDE }
				}
				it.cachedLocation = null
				return
			}
		}
		if (alreadyUpToDate) return

		val destPath = metadata.destURI.rebase(packFolder)

		// Don't update files marked with preserve if they already exist on disk
		if (metadata.preserve) {
			if (destPath.nioPath.toFile().exists()) {
				return
			}
		}

		val expected = try {
			metadata.linkedFile?.hash ?: metadata.getHashObj(index)
		} catch (e: Exception) {
			err = e
			return
		}
		try {
			install(packFolder, expected) { metadata.getSource(clientHolder) }
		} catch (e: DownloadSourceException) {
			failedDownloadHash = expected
			err = e
		} catch (e: Exception) {
			err = e
		}
	}

	val failedCurseForgeUpdate: CurseForgeUpdateData?
		get() = if (failedDownloadHash != null) metadata.linkedFile?.update?.get("curseforge") as? CurseForgeUpdateData else null

	fun manualDownload(packFolder: PackwizFilePath, cfPages: Map<Int, String>): ManualDownload? {
		val hash = failedDownloadHash ?: return null
		val linked = metadata.linkedFile
		val filename = linked?.filename?.filename ?: metadata.file.filename
		val cf = failedCurseForgeUpdate
		val page = when {
			cf != null -> cfPages[cf.projectId]?.let { "$it/files/${cf.fileId}" }
				?: "https://www.curseforge.com/projects/${cf.projectId}"
			linked?.download?.url is HttpUrlPath -> browserUrl(linked.download.url.toString())
			!metadata.metafile && filename.endsWith(".jar", true) && metadata.file is HttpUrlPath -> metadata.file.toString()
			else -> return null
		}
		return ManualDownload(name, filename, page, metadata.destURI.rebase(packFolder).nioPath.toAbsolutePath(), hash)
	}

	private fun browserUrl(downloadUrl: String): String {
		val url = downloadUrl.toHttpUrl()
		val segments = url.pathSegments
		if (url.host == "cdn.modrinth.com" && segments.size >= 5 && segments[0] == "data" && segments[2] == "versions") {
			return "https://modrinth.com/mod".toHttpUrl().newBuilder()
				.addPathSegment(segments[1]).addPathSegment("version").addPathSegment(segments[3]).build().toString()
		}
		if (url.host == "github.com" && segments.size == 6 && segments[2] == "releases" && segments[3] == "download") {
			return url.newBuilder().setPathSegment(3, "tag").removePathSegment(5).query(null).build().toString()
		}
		return downloadUrl
	}

	fun importManualFile(packFolder: PackwizFilePath, file: Path): Boolean {
		val expected = failedDownloadHash ?: return false
		try {
			install(packFolder, expected) { Files.newInputStream(file).source() }
		} catch (e: DownloadSourceException) {
			// The browser may have replaced or removed the file since the directory scan.
			return false
		} catch (e: IOException) {
			val failure = IOException("Could not install ${file.fileName} at ${metadata.destURI.rebase(packFolder)}: ${e.message}", e)
			err = failure
			throw failure
		}
		failedDownloadHash = null
		err = null
		return true
	}

	private class DownloadSourceException(message: String, cause: Exception? = null) : IOException(message, cause)
	private inline fun <T> readSource(block: () -> T): T = try {
		block()
	} catch (e: Exception) {
		throw DownloadSourceException(e.message ?: "Could not read download", e)
	}

	private fun install(packFolder: PackwizFilePath, expected: Hash<*>, openSource: () -> Source) {
		val indexHash = metadata.getHashObj(index)
		val destPath = metadata.destURI.rebase(packFolder)
		val destination = destPath.nioPath.toAbsolutePath()
		// createDirectories doesn't follow a final directory symlink on all platforms.
		if (!Files.isDirectory(destination.parent)) Files.createDirectories(destination.parent)
		val staged = Files.createTempFile(destination.parent, ".packwiz-", ".tmp")
		try {
			readSource(openSource).use { src ->
				val fileSource = expected.type.source(src)
				Files.newOutputStream(staged).sink().buffer().use { sink ->
					val chunk = Buffer()
					while (true) {
						val count = readSource { fileSource.read(chunk, 64 * 1024L) }
						if (count == -1L) break
						sink.write(chunk, count)
					}
				}
				if (fileSource.hash != expected) {
					throw DownloadSourceException("Invalid ${expected.type} for ${destPath.filename}: expected ${expected.value}, got ${fileSource.hash.value}")
				}
			}
			try {
				Files.move(staged, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
			} catch (e: AtomicMoveNotSupportedException) {
				Files.move(staged, destination, StandardCopyOption.REPLACE_EXISTING)
			}
		} finally {
			Files.deleteIfExists(staged)
		}

		cachedFile?.cachedLocation?.takeIf { it != destPath }?.let { previous ->
			try {
				Files.deleteIfExists(previous.nioPath)
			} catch (e: IOException) {
				Log.warn("Failed to remove previous version at $previous", e)
			}
		}
		cachedFile = (cachedFile ?: ManifestFile.File()).also {
			it.hash = indexHash
			it.isOptional = isOptional
			it.cachedLocation = destPath
			it.linkedFileHash = metadata.linkedFile?.let { expected }
		}
		completionStatus = CompletionStatus.DOWNLOADED
	}

	companion object {
		fun createTasksFromIndex(index: IndexFile, downloadSide: Side): MutableList<DownloadTask> {
			val tasks = ArrayList<DownloadTask>()
			for (file in index.files) {
				tasks.add(DownloadTask(file, index, downloadSide))
			}
			return tasks
		}
	}
}
