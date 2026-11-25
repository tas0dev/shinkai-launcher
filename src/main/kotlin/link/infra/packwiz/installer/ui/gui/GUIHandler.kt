package link.infra.packwiz.installer.ui.gui

import link.infra.packwiz.installer.ui.DownloadDirectories
import link.infra.packwiz.installer.ui.IUserInterface
import link.infra.packwiz.installer.ui.IUserInterface.ExceptionListResult
import link.infra.packwiz.installer.ui.data.ExceptionDetails
import link.infra.packwiz.installer.ui.data.IOptionDetails
import link.infra.packwiz.installer.ui.data.InstallProgress
import link.infra.packwiz.installer.ui.data.LauncherBranding
import link.infra.packwiz.installer.ui.data.ManualDownload
import link.infra.packwiz.installer.util.Log
import java.awt.EventQueue
import java.io.IOException
import java.nio.file.Path
import java.util.Timer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import javax.swing.JDialog
import javax.swing.JOptionPane
import javax.swing.UIManager
import kotlin.concurrent.timer
import kotlin.system.exitProcess

class GUIHandler : IUserInterface {
	private lateinit var frmPackwizlauncher: InstallWindow
	private var homeBranding = LauncherBranding("")
	private var defaultPackName = LauncherIdentity.NAME
	private val launchDecision = CompletableFuture<Boolean>()

	@Volatile
	override var optionsButtonPressed = false
		set(value) {
			field = value
			optionalSelectedLatch.countDown()
		}
	@Volatile
	override var cancelButtonPressed = false
		set(value) {
			field = value
			optionalSelectedLatch.countDown()
			cancelCallback?.invoke()
		}
	@Volatile
	override var cancelCallback: (() -> Unit)? = null
	var okButtonPressed = false
		set(value) {
			field = value
			optionalSelectedLatch.countDown()
		}
	@Volatile
	override var firstInstall = false

	@Volatile private var explicitTitle: String? = null
	@Volatile private var packTitle = LauncherIdentity.NAME
	override var title: String
		get() = explicitTitle ?: packTitle
		set(value) {
			explicitTitle = value
			EventQueue.invokeLater { frmPackwizlauncher.title = value }
		}

	init {
		EventQueue.invokeAndWait {
			try {
				UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
			} catch (e: Exception) {
				Log.warn("Failed to set look and feel", e)
			}
			frmPackwizlauncher = InstallWindow(this).apply {
				title = this@GUIHandler.title
			}
		}
	}

	private val visibleCountdownLatch = CountDownLatch(1)
	private val optionalSelectedLatch = CountDownLatch(1)

	override fun show() = EventQueue.invokeLater {
		frmPackwizlauncher.isVisible = true
		visibleCountdownLatch.countDown()
	}

	override fun dispose() = frmPackwizlauncher.afterSettingsClose {
		frmPackwizlauncher.dispose()
	}

	fun showLaunchReady() {
		frmPackwizlauncher.afterSettingsClose { frmPackwizlauncher.showLaunchReady() }
		// Give Swing a repaint before the process exits and hands control back to the launcher.
		Thread.sleep(600)
	}

	fun awaitLaunch(): Boolean = launchDecision.get()

	fun requestLaunch() {
		frmPackwizlauncher.startInstalling()
		launchDecision.complete(true)
	}

	fun requestWindowClose() {
		if (launchDecision.complete(false)) {
			frmPackwizlauncher.dispose()
		} else {
			cancelButtonPressed = true
		}
	}

	fun setHomeBranding(branding: LauncherBranding) = EventQueue.invokeAndWait {
		homeBranding = branding
		refreshBranding()
	}

	override fun setPackName(packName: String) = EventQueue.invokeLater {
		defaultPackName = packName
		refreshBranding()
	}

	private fun refreshBranding() {
		val current = homeBranding
		applyBranding(if (current.title.isBlank()) current.copy(title = defaultPackName) else current)
	}

	private fun applyBranding(branding: LauncherBranding) {
		packTitle = if (branding.title == LauncherIdentity.NAME) LauncherIdentity.NAME else "${branding.title} | ${LauncherIdentity.NAME}"
		frmPackwizlauncher.title = title
		frmPackwizlauncher.setBranding(branding)
	}

	override fun showErrorAndExit(message: String, e: Exception?): Nothing {
		val buttons = arrayOf("Quit", if (firstInstall) "Continue without installing" else "Continue without updating")
		if (e == null) Log.fatal(message) else Log.fatal(message, e)
		val result = frmPackwizlauncher.afterSettingsClose {
			JOptionPane.showOptionDialog(frmPackwizlauncher, if (e == null) message else "$message: $e",
				title, JOptionPane.YES_NO_OPTION, JOptionPane.ERROR_MESSAGE, null, buttons, buttons[0])
		}
		if (result == 1) {
			Log.info("User selected to continue without installing/updating, exiting with code 0...")
			exitProcess(0)
		}
		Log.info("User selected to quit, exiting with code 1...")
		exitProcess(1)
	}

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
		Log.info(sb.toString())
		EventQueue.invokeLater {
			frmPackwizlauncher.displayProgress(progress)
		}
	}

	override fun showOptions(options: List<IOptionDetails>): Boolean {
		val future = CompletableFuture<Boolean>()
		frmPackwizlauncher.afterSettingsClose {
			if (options.isEmpty()) {
				JOptionPane.showMessageDialog(frmPackwizlauncher,
					"このパックには任意 MOD がありません。",
					"任意 MOD", JOptionPane.INFORMATION_MESSAGE)
				future.complete(false)
			} else {
				OptionsSelectWindow(options, future, frmPackwizlauncher).apply {
					defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
					isVisible = true
				}
			}
		}
		return future.get()
	}

	override fun showExceptions(exceptions: List<ExceptionDetails>, numTotal: Int, allowsIgnore: Boolean): ExceptionListResult {
		val future = CompletableFuture<ExceptionListResult>()
		frmPackwizlauncher.afterSettingsClose {
			ExceptionListWindow(exceptions, future, numTotal, allowsIgnore, frmPackwizlauncher).apply {
				defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
				isVisible = true
			}
		}
		return future.get()
	}

	override fun showManualDownloads(downloads: List<ManualDownload>, scan: (Path) -> Set<ManualDownload>): Boolean {
		val initialFolder = frmPackwizlauncher.afterSettingsClose { DownloadDirectories.customDirectory() }
			?: DownloadDirectories.defaultDirectory()
		val installed = try {
			scan(initialFolder)
		} catch (e: IOException) {
			emptySet()
		}
		val missing = downloads.filterNot { it in installed }
		if (missing.isEmpty()) return true

		val systemFolder = DownloadDirectories.defaultDirectory()
		val window = frmPackwizlauncher.afterSettingsClose {
			val folder = DownloadDirectories.customDirectory() ?: systemFolder
			ManualDownloadWindow(frmPackwizlauncher, missing, folder, systemFolder).also { dialog ->
				EventQueue.invokeLater { dialog.showOverHome() }
			}
		}
		return window.awaitDownloads { folder -> scan(folder) }
	}

	override fun disableOptionsButton(hasOptions: Boolean) = EventQueue.invokeLater {
		frmPackwizlauncher.disableOptionsButton(hasOptions)
	}

	override fun showCancellationDialog(): IUserInterface.CancellationResult {
		val future = CompletableFuture<IUserInterface.CancellationResult>()
		frmPackwizlauncher.afterSettingsClose {
			val buttons = arrayOf("起動を中止", "更新をスキップして起動")
			val result = JOptionPane.showOptionDialog(frmPackwizlauncher,
					"インストールを中断しました。このままゲームの起動も中止しますか？",
					"インストールの中断",
					JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE, null, buttons, buttons[0])
			future.complete(if (result == 1) IUserInterface.CancellationResult.CONTINUE else IUserInterface.CancellationResult.QUIT)
		}
		return future.get()
	}

	override fun showUpdateConfirmationDialog(oldVersions: List<Pair<String, String?>>, newVersions: List<Pair<String, String?>>): IUserInterface.UpdateConfirmationResult {
		assert(newVersions.isNotEmpty())
		val future = CompletableFuture<IUserInterface.UpdateConfirmationResult>()
		frmPackwizlauncher.afterSettingsClose {
			val oldVersIndex = oldVersions.map { it.first to it.second }.toMap()
			val newVersIndex = newVersions.map { it.first to it.second }.toMap()
			val message = StringBuilder()
			message.append("<html>" +
					"This modpack uses newer versions of the following:<br>" +
					"<ul>")

			for (oldVer in oldVersions) {
				val correspondingNewVer = newVersIndex[oldVer.first]
				message.append("<li>")
				message.append(oldVer.first.replaceFirstChar { it.uppercase() })
				message.append(": <font color=${if (oldVer.second != correspondingNewVer) "#ff0000" else "#000000"}>")
				message.append(oldVer.second ?: "Not found")
				message.append("</font></li>")
			}
			message.append("</ul>")

			message.append("New versions:" +
					"<ul>")
			for (newVer in newVersions) {
				val correspondingOldVer = oldVersIndex[newVer.first]
				message.append("<li>")
				message.append(newVer.first.replaceFirstChar { it.uppercase() })
				message.append(": <font color=${if (newVer.second != correspondingOldVer) "#00ff00" else "#000000"}>")
				message.append(newVer.second ?: "Not found")
				message.append("</font></li>")
			}
			message.append("</ul><br>" +
					"Would you like to update the versions, launch without updating, or cancel the launch?")


			val options = arrayOf("Cancel", "Continue anyways", "Update")
			val result = JOptionPane.showOptionDialog(frmPackwizlauncher, message,
					"Updating MultiMC versions",
					JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[2])
			future.complete(
				when (result) {
					JOptionPane.CLOSED_OPTION, 0 -> IUserInterface.UpdateConfirmationResult.CANCELLED
					1 -> IUserInterface.UpdateConfirmationResult.CONTINUE
					2 -> IUserInterface.UpdateConfirmationResult.UPDATE
					else -> IUserInterface.UpdateConfirmationResult.CANCELLED
				}
			)
		}
		return future.get()
	}

	override fun awaitOptionalButton(showCancel: Boolean, timeout: Long) {
		EventQueue.invokeAndWait {
			frmPackwizlauncher.showOk(!showCancel)
		}
		visibleCountdownLatch.await()

		var closeTimer: Timer? = null
		if (timeout >= 0) {
			var count = 0
			closeTimer = timer("timeout", true, 0, 1000) {
				if (frmPackwizlauncher.settingsOpen) return@timer
				if (count >= timeout) {
					optionalSelectedLatch.countDown()
					cancel()
				} else {
					val remaining = timeout - count
					EventQueue.invokeLater { frmPackwizlauncher.timeoutOk(remaining) }
					count += 1
				}
			};
		}

		optionalSelectedLatch.await()
		closeTimer?.cancel()
		EventQueue.invokeLater {
			frmPackwizlauncher.hideOk()
		}
	}
}
