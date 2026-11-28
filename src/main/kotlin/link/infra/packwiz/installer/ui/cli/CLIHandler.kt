package link.infra.packwiz.installer.ui.cli

import link.infra.packwiz.installer.ui.DownloadDirectories
import link.infra.packwiz.installer.ui.IUserInterface
import link.infra.packwiz.installer.ui.IUserInterface.ExceptionListResult
import link.infra.packwiz.installer.ui.data.ExceptionDetails
import link.infra.packwiz.installer.ui.data.IOptionDetails
import link.infra.packwiz.installer.ui.data.InstallProgress
import link.infra.packwiz.installer.ui.data.ManualDownload
import link.infra.packwiz.installer.util.Log
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

class CLIHandler : IUserInterface {
	override val selectNewOptionalMods = true
	@Volatile
	override var optionsButtonPressed = false
	// TODO: treat ctrl+c as cancel?
	@Volatile
	override var cancelButtonPressed = false
	@Volatile
	override var cancelCallback: (() -> Unit)? = null
	@Volatile
	override var firstInstall = false

	override var title: String = ""

	override fun showErrorAndExit(message: String, e: Exception?): Nothing {
		if (e != null) {
			Log.fatal(message, e)
		} else {
			Log.fatal(message)
		}
		exitProcess(1)
	}

	override fun show() {}
	override fun dispose() {}
	override fun submitProgress(progress: InstallProgress) {
		val sb = StringBuilder()
		if (progress.hasProgress) {
			sb.append('(')
			sb.append(progress.progress)
			sb.append('/')
			sb.append(progress.progressTotal)
			sb.append(") ")
		}
		sb.append(progress.message)
		println(sb.toString())
	}

	override fun showOptions(options: List<IOptionDetails>): Boolean {
		for (opt in options) {
			opt.optionValue = true
			// TODO: implement option choice in the CLI?
			Log.warn("Accepting option ${opt.name} as option choosing is not implemented in the CLI")
		}
		return false // Can't be cancelled!
	}

	override fun showExceptions(exceptions: List<ExceptionDetails>, numTotal: Int, allowsIgnore: Boolean): ExceptionListResult {
		println("Failed to download modpack, the following errors were encountered:")
		for (ex in exceptions) {
			print(ex.name + ": ")
			ex.exception.printStackTrace()
		}
		return ExceptionListResult.CANCEL
	}

	override fun showManualDownloads(downloads: List<ManualDownload>, scan: (Path) -> Set<ManualDownload>): Boolean {
		val directory = DownloadDirectories.preferredDirectory()
		val installed = try {
			if (Files.isDirectory(directory)) scan(directory) else emptySet()
		} catch (e: IOException) {
			Log.warn("Could not import manual downloads from $directory", e)
			emptySet()
		}
		val missing = downloads.filterNot { it in installed }
		if (missing.isEmpty()) return true
		Log.warn("Download the following files into $directory or save each to its listed installation path, then rerun the installer.")
		for (file in missing) {
			println("${file.name} (${file.filename}): ${file.url}\n  Installation path: ${file.destination}")
		}
		return false
	}
}
