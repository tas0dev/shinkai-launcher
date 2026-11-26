package link.infra.packwiz.installer.ui

import link.infra.packwiz.installer.ui.data.ExceptionDetails
import link.infra.packwiz.installer.ui.data.IOptionDetails
import link.infra.packwiz.installer.ui.data.InstallProgress
import link.infra.packwiz.installer.ui.data.ManualDownload
import java.nio.file.Path

interface IUserInterface {
	fun show()
	fun dispose()

	fun showErrorAndExit(message: String): Nothing {
		showErrorAndExit(message, null)
	}
	fun showErrorAndExit(message: String, e: Exception?): Nothing
	fun showSecurityErrorAndExit(message: String, e: Exception): Nothing = showErrorAndExit(message, e)

	var title: String
	fun setPackName(packName: String) {}
	fun submitProgress(progress: InstallProgress)
	// Return true if the installation was cancelled!
	fun showOptions(options: List<IOptionDetails>): Boolean

	fun showExceptions(exceptions: List<ExceptionDetails>, numTotal: Int, allowsIgnore: Boolean): ExceptionListResult
	/** Return true to continue (any remaining failures are reviewed), or false to cancel the launch. */
	fun showManualDownloads(downloads: List<ManualDownload>, scan: (Path) -> Set<ManualDownload>): Boolean = true
	fun disableOptionsButton(hasOptions: Boolean) {}

	fun showCancellationDialog(): CancellationResult = CancellationResult.QUIT

	fun showUpdateConfirmationDialog(oldVersions: List<Pair<String, String?>>, newVersions: List<Pair<String, String?>>): UpdateConfirmationResult {
		// Always update metadata when using the CLI
		return UpdateConfirmationResult.UPDATE
	}

	fun awaitOptionalButton(showCancel: Boolean, timeout: Long)

	enum class ExceptionListResult {
		CONTINUE, CANCEL, IGNORE
	}

	enum class CancellationResult {
		QUIT, CONTINUE
	}

	enum class UpdateConfirmationResult {
		CANCELLED, CONTINUE, UPDATE
	}

	var optionsButtonPressed: Boolean
	var cancelButtonPressed: Boolean
	var cancelCallback: (() -> Unit)?

	var firstInstall: Boolean

}

inline fun <T> IUserInterface.wrap(message: String, inner: () -> T): T {
	return try {
		inner.invoke()
	} catch (e: Exception) {
		showErrorAndExit(message, e)
	}
}
