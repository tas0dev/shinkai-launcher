package link.infra.packwiz.installer.ui

import java.io.IOException
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences

internal object DownloadDirectories {
	private const val FOLDER_KEY = "manualDownloadDirectory"
	private val preferences get() = Preferences.userNodeForPackage(DownloadDirectories::class.java)
	private val systemDownloads by lazy { detectSystemDownloads() }

	fun defaultDirectory(): Path = systemDownloads

	fun customDirectory(): Path? = try {
		preferences.get(FOLDER_KEY, null)?.let { Paths.get(it) }?.takeIf { it.isAbsolute }
	} catch (e: InvalidPathException) {
		null
	} catch (e: SecurityException) {
		null
	}

	fun preferredDirectory(): Path = customDirectory() ?: defaultDirectory()

	fun setPreferredDirectory(path: Path?) {
		val node = preferences
		if (path == null) node.remove(FOLDER_KEY) else node.put(FOLDER_KEY, path.toAbsolutePath().normalize().toString())
		node.flush()
	}

	private fun detectSystemDownloads(): Path {
		val fallback = Paths.get(System.getProperty("user.home"), "Downloads")
		val os = System.getProperty("os.name")
		val command = when {
			os.startsWith("Windows", ignoreCase = true) -> {
				val systemRoot = System.getenv("SystemRoot") ?: return fallback
				val powershell = Paths.get(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe")
				// Query the Downloads known folder, including redirects, and preserve non-ASCII paths.
				listOf(powershell.toString(), "-NoProfile", "-NonInteractive", "-Command",
					"[Console]::OutputEncoding = [Text.Encoding]::UTF8; " +
						"(New-Object -ComObject Shell.Application).NameSpace('shell:Downloads').Self.Path")
			}
			os.equals("Linux", ignoreCase = true) -> listOf("xdg-user-dir", "DOWNLOAD")
			else -> return fallback
		}

		return try {
			val process = ProcessBuilder(command).redirectErrorStream(true).start()
			try {
				if (!process.waitFor(3, TimeUnit.SECONDS) || process.exitValue() != 0) return fallback
				val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readLine() } ?: return fallback
				Paths.get(output.removePrefix("\uFEFF")).takeIf { it.isAbsolute } ?: fallback
			} finally {
				process.destroyForcibly()
			}
		} catch (e: IOException) {
			fallback
		} catch (e: InvalidPathException) {
			fallback
		} catch (e: SecurityException) {
			fallback
		} catch (e: InterruptedException) {
			Thread.currentThread().interrupt()
			fallback
		}
	}
}
