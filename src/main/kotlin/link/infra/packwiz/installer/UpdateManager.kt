package link.infra.packwiz.installer

import cc.ekblad.toml.decode
import com.google.gson.GsonBuilder
import com.google.gson.JsonIOException
import com.google.gson.JsonSyntaxException
import link.infra.packwiz.installer.DownloadTask.Companion.createTasksFromIndex
import link.infra.packwiz.installer.metadata.DownloadMode
import link.infra.packwiz.installer.metadata.IndexFile
import link.infra.packwiz.installer.metadata.ManifestFile
import link.infra.packwiz.installer.metadata.PackFile
import link.infra.packwiz.installer.metadata.curseforge.getCfProjectPages
import link.infra.packwiz.installer.metadata.curseforge.resolveCfMetadata
import link.infra.packwiz.installer.metadata.hash.Hash
import link.infra.packwiz.installer.metadata.hash.HashFormat
import link.infra.packwiz.installer.request.RequestException
import link.infra.packwiz.installer.target.ClientHolder
import link.infra.packwiz.installer.target.Side
import link.infra.packwiz.installer.target.path.PackwizFilePath
import link.infra.packwiz.installer.target.path.PackwizPath
import link.infra.packwiz.installer.ui.IUserInterface
import link.infra.packwiz.installer.ui.IUserInterface.CancellationResult
import link.infra.packwiz.installer.ui.IUserInterface.ExceptionListResult
import link.infra.packwiz.installer.ui.data.InstallProgress
import link.infra.packwiz.installer.util.Log
import okio.buffer
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.CompletionService
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import kotlin.system.exitProcess

class UpdateManager internal constructor(private val opts: Options, val ui: IUserInterface) {
	private var cancelled = false
	private var cancelledStartGame = false
	private var errorsOccurred = false

	init {
		start()
	}

	data class Options(
		val packFile: PackwizPath<*>,
		val manifestFile: PackwizFilePath,
		val packFolder: PackwizFilePath,
		val multimcFolder: PackwizFilePath,
		val side: Side,
		val timeout: Long,
	)

	// TODO: make this return a value based on results?
	private fun start() {
		val clientHolder = ClientHolder()
		ui.cancelCallback = {
			clientHolder.close()
		}

		ui.submitProgress(InstallProgress("Loading manifest file..."))
		val gson = GsonBuilder()
			.registerTypeAdapter(Hash::class.java, Hash.TypeHandler())
			.registerTypeAdapter(PackwizFilePath::class.java, PackwizPath.adapterRelativeTo(opts.packFolder))
			.enableComplexMapKeySerialization()
			.create()
		val manifest = try {
			// TODO: kotlinx.serialisation?
			InputStreamReader(opts.manifestFile.source(clientHolder).inputStream(), StandardCharsets.UTF_8).use { reader ->
				gson.fromJson(reader, ManifestFile::class.java)
			}
		} catch (e: RequestException.Response.File.FileNotFound) {
			ui.firstInstall = true
			ManifestFile()
		} catch (e: JsonSyntaxException) {
			ui.showErrorAndExit("Invalid local manifest file, try deleting ${opts.manifestFile}", e)
		} catch (e: JsonIOException) {
			ui.showErrorAndExit("Failed to read local manifest file, try deleting ${opts.manifestFile}", e)
		}

		if (ui.cancelButtonPressed) {
			showCancellationDialog()
			handleCancellation()
		}

		ui.submitProgress(InstallProgress("Loading pack file..."))
		val packFileSource = try {
			val src = opts.packFile.source(clientHolder)
			HashFormat.SHA256.source(src)
		} catch (e: Exception) {
			// TODO: ensure suppressed/caused exceptions are shown?
			ui.showErrorAndExit("Failed to download pack.toml", e)
		}
		val pf = packFileSource.buffer().use {
			try {
				PackFile.mapper(opts.packFile).decode<PackFile>(it.inputStream())
			} catch (e: IllegalStateException) {
				ui.showErrorAndExit("Failed to parse pack.toml", e)
			}
		}
		ui.showPackBranding(pf.name) { loadLauncherBranding(opts.packFile, pf.name, clientHolder) }

		if (ui.cancelButtonPressed) {
			showCancellationDialog()
			handleCancellation()
		}

		// Launcher checks
		val lu = LauncherUtils(opts, ui)

		// MultiMC MC and loader version checker
		ui.submitProgress(InstallProgress("Loading MultiMC pack file..."))
		try {
			when (lu.handleMultiMC(pf, gson)) {
				LauncherUtils.LauncherStatus.CANCELLED -> cancelled = true
				LauncherUtils.LauncherStatus.NOT_FOUND -> Log.info("MultiMC not detected")
				else -> {}
			}
			handleCancellation()
		} catch (e: Exception) {
			ui.showErrorAndExit(e.message!!, e)
		}

		if (ui.cancelButtonPressed) {
			showCancellationDialog()
			handleCancellation()
		}

		ui.submitProgress(InstallProgress("Checking local files..."))

		// If the side changes, invalidate EVERYTHING (even when the index hasn't changed)
		val invalidateAll = opts.side != manifest.cachedSide
		val invalidatedUris: MutableList<PackwizFilePath> = ArrayList()
		if (!invalidateAll) {
			// Invalidation checking must be done here, as it must happen before pack/index hashes are checked
			for ((fileUri, file) in manifest.cachedFiles) {
				// ignore onlyOtherSide files
				if (file.onlyOtherSide) {
					continue
				}

				val required = !file.isOptional || file.optionValue
				if (required && file.cachedLocation?.let { Files.isRegularFile(it.nioPath) } != true) {
					Log.info("File ${fileUri.filename} invalidated, marked for redownloading")
					invalidatedUris.add(fileUri)
				}
			}

			if (manifest.packFileHash?.let { it == packFileSource.hash } == true && invalidatedUris.isEmpty()) {
				// todo: --force?
				ui.submitProgress(InstallProgress("Modpack is already up to date!", 1, 1))
				if (manifest.cachedFiles.any { it.value.isOptional }) {
					ui.awaitOptionalButton(false, opts.timeout)
				}
				if (ui.cancelButtonPressed) {
					showCancellationDialog()
					handleCancellation()
				}
				if (!ui.optionsButtonPressed) {
					return
				}
			}
		}

		Log.info("Modpack name: ${pf.name}")

		if (ui.cancelButtonPressed) {
			showCancellationDialog()
			handleCancellation()
		}
		try {
			processIndex(
				pf.index.file,
				pf.index.hashFormat.fromString(pf.index.hash),
				pf.index.hashFormat,
				manifest,
				invalidatedUris,
				invalidateAll,
				clientHolder
			)
		} catch (e1: Exception) {
			ui.showErrorAndExit("Failed to process index file", e1)
		}

		handleCancellation()


		// If there were errors, don't write the manifest/index hashes, to ensure they are rechecked later
		if (errorsOccurred) {
			manifest.indexFileHash = null
			manifest.packFileHash = null
		} else {
			manifest.packFileHash = packFileSource.hash
		}

		manifest.cachedSide = opts.side
		try {
			Files.newBufferedWriter(opts.manifestFile.nioPath, StandardCharsets.UTF_8).use { writer -> gson.toJson(manifest, writer) }
		} catch (e: IOException) {
			ui.showErrorAndExit("Failed to save local manifest file", e)
		}
	}

	private fun processIndex(indexUri: PackwizPath<*>, indexHash: Hash<*>, hashFormat: HashFormat<*>, manifest: ManifestFile, invalidatedFiles: List<PackwizFilePath>, invalidateAll: Boolean, clientHolder: ClientHolder) {
		if (!invalidateAll) {
			if (manifest.indexFileHash == indexHash && invalidatedFiles.isEmpty()) {
				ui.submitProgress(InstallProgress("Modpack files are already up to date!", 1, 1))
				if (manifest.cachedFiles.any { it.value.isOptional }) {
					ui.awaitOptionalButton(false, opts.timeout)
				}
				if (ui.cancelButtonPressed) {
					showCancellationDialog()
					return
				}
				if (!ui.optionsButtonPressed) {
					return
				}
			}
		}
		manifest.indexFileHash = indexHash

		val indexFileSource = try {
			val src = indexUri.source(clientHolder)
			hashFormat.source(src)
		} catch (e: Exception) {
			ui.showErrorAndExit("Failed to download index file", e)
		}

		val indexFile = try {
			IndexFile.mapper(indexUri).decode<IndexFile>(indexFileSource.buffer().inputStream())
		} catch (e: IllegalStateException) {
			ui.showErrorAndExit("Failed to parse index file", e)
		}
		if (indexHash != indexFileSource.hash) {
			ui.showErrorAndExit("Your index file hash is invalid! The pack developer should packwiz refresh on the pack again")
		}

		if (ui.cancelButtonPressed) {
			showCancellationDialog()
			return
		}

		ui.submitProgress(InstallProgress("Checking local files..."))
		val it: MutableIterator<Map.Entry<PackwizFilePath, ManifestFile.File>> = manifest.cachedFiles.entries.iterator()
		while (it.hasNext()) {
			val (uri, file) = it.next()
			if (file.cachedLocation != null) {
				if (indexFile.files.none { it.file.rebase(opts.packFolder) == uri }) { // File has been removed from the index
					try {
						Files.deleteIfExists(file.cachedLocation!!.nioPath)
					} catch (e: IOException) {
						Log.warn("Failed to delete file removed from index", e)
					}
					Log.info("Deleted ${file.cachedLocation!!.filename} (removed from pack)")
					it.remove()
				}
			}
		}

		if (ui.cancelButtonPressed) {
			showCancellationDialog()
			return
		}
		ui.submitProgress(InstallProgress("Comparing new files..."))

		// TODO: progress bar?
		if (indexFile.files.isEmpty()) {
			Log.warn("Index is empty!")
		}
		val tasks = createTasksFromIndex(indexFile, opts.side)
		if (invalidateAll) {
			Log.info("Side changed, invalidating all mods")
		}
		tasks.forEach{ f ->
			// TODO: should linkedfile be checked as well? should this be done in the download section?
			if (invalidateAll) {
				f.invalidate()
			} else if (invalidatedFiles.contains(f.metadata.file.rebase(opts.packFolder))) {
				f.invalidate()
			}
			val file = manifest.cachedFiles[f.metadata.file.rebase(opts.packFolder)]
			// Ensure the file can be reverted later if necessary - the DownloadTask modifies the file so if it fails we need the old version back
			file?.backup()
			// If it is null, the DownloadTask will make a new empty cachedFile
			f.updateFromCache(file)
		}

		if (ui.cancelButtonPressed) {
			showCancellationDialog()
			return
		}

		// Let's hope downloadMetadata is a pure function!!!
		tasks.parallelStream().forEach { f -> f.downloadMetadata(clientHolder) }

		val failedTaskDetails = tasks.asSequence().map(DownloadTask::exceptionDetails).filterNotNull().toList()
		if (failedTaskDetails.isNotEmpty()) {
			errorsOccurred = true
			when (ui.showExceptions(failedTaskDetails, tasks.size, true)) {
				ExceptionListResult.CONTINUE -> {}
				ExceptionListResult.CANCEL -> {
					cancelled = true
					return
				}
				ExceptionListResult.IGNORE -> {
					cancelledStartGame = true
					return
				}
			}
		}

		if (ui.cancelButtonPressed) {
			showCancellationDialog()
			return
		}

		// TODO: task failed function?
		tasks.removeAll { it.failed() }
		val optionTasks = tasks.filter(DownloadTask::correctSide).filter(DownloadTask::isOptional).toList()
		val optionsChanged = optionTasks.any(DownloadTask::isNewOptional)
		if (optionTasks.isNotEmpty() && !optionsChanged) {
			if (!ui.optionsButtonPressed) {
				// TODO: this is so ugly
				ui.submitProgress(InstallProgress("Reconfigure optional mods?", 0,1))
				ui.awaitOptionalButton(true, opts.timeout)
				if (ui.cancelButtonPressed) {
					showCancellationDialog()
					return
				}
			}
		}
		// If options changed, present all options again
		if (ui.optionsButtonPressed || optionsChanged) {
			// new ArrayList is required so it's an IOptionDetails rather than a DownloadTask list
			if (ui.showOptions(ArrayList(optionTasks))) {
				cancelled = true
				handleCancellation()
			}
		}
		// TODO: keep this enabled? then apply changes after download process?
		ui.disableOptionsButton(optionTasks.isNotEmpty())

		validateAndResolve(tasks, clientHolder)

		// TODO: different thread pool type?
		val threadPool = Executors.newFixedThreadPool(10)
		val completionService: CompletionService<DownloadTask> = ExecutorCompletionService(threadPool)
		tasks.forEach { t ->
			completionService.submit {
				t.download(opts.packFolder, clientHolder)
				t
			}
		}
		for (i in tasks.indices) {
			val task: DownloadTask = try {
				completionService.take().get()
			} catch (e: InterruptedException) {
				ui.showErrorAndExit("Interrupted when consuming download tasks", e)
			} catch (e: ExecutionException) {
				ui.showErrorAndExit("Failed to execute download task", e)
			}
			val exDetails = task.exceptionDetails
			val progress = if (exDetails != null) {
				"Failed to download ${exDetails.name}: ${exDetails.exception.message}"
			} else {
				when (task.completionStatus) {
					DownloadTask.CompletionStatus.INCOMPLETE -> "${task.name} pending (you should never see this...)"
					DownloadTask.CompletionStatus.DOWNLOADED -> "Downloaded ${task.name}"
					DownloadTask.CompletionStatus.ALREADY_EXISTS_CACHED -> "${task.name} already exists (cached)"
					DownloadTask.CompletionStatus.ALREADY_EXISTS_VALIDATED -> "${task.name} already exists (validated)"
					DownloadTask.CompletionStatus.SKIPPED_DISABLED -> "Skipped ${task.name} (disabled)"
					DownloadTask.CompletionStatus.SKIPPED_WRONG_SIDE -> "Skipped ${task.name} (wrong side)"
					DownloadTask.CompletionStatus.DELETED_DISABLED -> "Deleted ${task.name} (disabled)"
					DownloadTask.CompletionStatus.DELETED_WRONG_SIDE -> "Deleted ${task.name} (wrong side)"
				}
			}
			ui.submitProgress(InstallProgress(progress, i + 1, tasks.size))

			if (ui.cancelButtonPressed) { // Stop all tasks, don't launch the game (it's in an invalid state!)
				// TODO: close client holder in more places?
				clientHolder.close()
				threadPool.shutdown()
				cancelled = true
				return
			}
		}

		// Shut down the thread pool when the update is done
		threadPool.shutdown()

		val cfPages = getCfProjectPages(tasks.mapNotNull { it.failedCurseForgeUpdate?.projectId }.distinct(), clientHolder.okHttpClient)
		val manual = tasks.mapNotNull { task ->
			task.manualDownload(opts.packFolder, cfPages)?.let { it to task }
		}.toMap()
		if (manual.isNotEmpty()) {
			val scanner = ManualDownloadScanner(manual, opts.packFolder)
			if (!ui.showManualDownloads(manual.keys.toList(), scanner::scan)) {
				cancelled = true
				return
			}
			if (manual.values.none(DownloadTask::failed)) {
				ui.submitProgress(InstallProgress("Finished importing manual downloads", tasks.size, tasks.size))
			}
		}

		for (task in tasks) {
			val file = task.cachedFile ?: continue
			val saved = if (task.failed()) file.revert else file
			val key = task.metadata.file.rebase(opts.packFolder)
			if (saved != null) manifest.cachedFiles[key] = saved else manifest.cachedFiles.remove(key)
		}

		val pagesByTask = manual.entries.associate { (download, task) -> task to download.url }
		val failed = tasks.mapNotNull { task -> task.exceptionDetails?.copy(modUrl = pagesByTask[task]) }
		if (failed.isNotEmpty()) {
			errorsOccurred = true
			when (ui.showExceptions(failed, tasks.size, false)) {
				ExceptionListResult.CONTINUE -> {}
				ExceptionListResult.CANCEL -> cancelled = true
				ExceptionListResult.IGNORE -> cancelledStartGame = true
			}
		}
	}

	private fun validateAndResolve(tasks: List<DownloadTask>, clientHolder: ClientHolder) {
		ui.submitProgress(InstallProgress("Validating existing files..."))

		// Validate existing files
		for (downloadTask in tasks.filter(DownloadTask::correctSide)) {
			downloadTask.validateExistingFile(opts.packFolder, clientHolder)
		}

		// Resolve CurseForge metadata
		val cfFiles = tasks.asSequence().filter { !it.alreadyUpToDate && !it.failed() }
			.filter(DownloadTask::correctSide)
			.filter { !it.isOptional || it.optionValue }
			.mapNotNull { it.metadata.linkedFile }
			.filter { it.download.mode == DownloadMode.CURSEFORGE }.toList()
		if (cfFiles.isNotEmpty()) {
			ui.submitProgress(InstallProgress("Resolving CurseForge metadata..."))
			resolveCfMetadata(cfFiles, clientHolder.okHttpClient)
		}
	}

	private fun showCancellationDialog() {
		when (ui.showCancellationDialog()) {
			CancellationResult.QUIT -> cancelled = true
			CancellationResult.CONTINUE -> cancelledStartGame = true
		}
	}

	// TODO: move to UI?
	private fun handleCancellation() {
		if (cancelled) {
			println("Update cancelled by user!")
			exitProcess(1)
		} else if (cancelledStartGame) {
			println("Update cancelled by user! Continuing to start game...")
			exitProcess(0)
		}
	}

}
