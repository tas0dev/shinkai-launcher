package link.infra.packwiz.installer

import com.sun.net.httpserver.HttpServer
import link.infra.packwiz.installer.target.Side
import link.infra.packwiz.installer.target.path.PackwizFilePath
import link.infra.packwiz.installer.ui.IUserInterface
import link.infra.packwiz.installer.ui.cli.CLIHandler
import link.infra.packwiz.installer.ui.data.ExceptionDetails
import link.infra.packwiz.installer.ui.data.ManualDownload
import okio.ByteString.Companion.encodeUtf8
import okio.Path.Companion.toOkioPath
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class ManualDownloadIntegrationTest {
	@get:Rule val temp = TemporaryFolder()

	@Test fun recoversFailedDownloadAcrossDirectoriesAndCachesTheInstallation() {
		val pack = temp.newFolder("pack")
		val instance = temp.newFolder("instance")
		val defaultDownloads = temp.newFolder("Downloads")
		val selected = temp.newFolder("selected")
		val requests = AtomicInteger()
		val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
		server.createContext("/") { exchange ->
			requests.incrementAndGet()
			exchange.sendResponseHeaders(403, -1)
			exchange.close()
		}
		server.start()
		try {
			val url = "http://127.0.0.1:${server.address.port}/download.jar"
			val jar = "expected mod bytes"
			val mod = """
				name = "Example"
				filename = "example.jar"
				[download]
				url = "$url"
				hash-format = "sha256"
				hash = "${jar.encodeUtf8().sha256().hex()}"
			""".trimIndent()
			pack.resolve("mods").mkdir()
			pack.resolve("mods/example.pw.toml").writeText(mod)
			val index = """
				hash-format = "sha256"
				[[files]]
				file = "mods/example.pw.toml"
				hash = "${mod.encodeUtf8().sha256().hex()}"
				metafile = true
			""".trimIndent()
			pack.resolve("index.toml").writeText(index)
			pack.resolve("pack.toml").writeText("""
				name = "Manual download test"
				[index]
				file = "index.toml"
				hash-format = "sha256"
				hash = "${index.encodeUtf8().sha256().hex()}"
			""".trimIndent())

			val target = instance.resolve("mods/example.jar")
			target.parentFile.mkdir()
			target.writeText("previously installed mod")
			defaultDownloads.resolve("example.jar").writeText("wrong version")
			defaultDownloads.resolve("example.jar.crdownload").writeText(jar)
			val browserCopy = selected.resolve("example (1).jar")
			browserCopy.writeText(jar)

			var prompts = 0
			val ui = object : IUserInterface by CLIHandler() {
				override fun showErrorAndExit(message: String, e: Exception?): Nothing = throw AssertionError(message, e)
				override fun showExceptions(exceptions: List<ExceptionDetails>, numTotal: Int, allowsIgnore: Boolean): IUserInterface.ExceptionListResult =
					throw AssertionError("Unexpected installer error", exceptions.first().exception)

				override fun showManualDownloads(downloads: List<ManualDownload>, scan: (Path) -> Set<ManualDownload>): Boolean {
					prompts++
					val file = downloads.single()
					assertEquals(url, file.url)
					assertEquals("example.jar", file.filename)
					assertTrue(scan(defaultDownloads.toPath()).isEmpty())
					if (prompts == 1) {
						assertEquals("previously installed mod", target.readText())
					} else {
						assertFalse(target.exists())
					}
					assertEquals(setOf(file), scan(selected.toPath()))
					return true
				}
			}
			val folder = PackwizFilePath(instance.toOkioPath())
			val opts = UpdateManager.Options(PackwizFilePath(pack.toOkioPath(), "pack.toml"),
				folder / "packwiz.json", folder, folder, Side.CLIENT)
			UpdateManager(opts, ui)
			assertEquals(jar, target.readText())
			assertEquals(jar, browserCopy.readText())
			UpdateManager(opts, ui)
			assertEquals(1, prompts)
			assertEquals(1, requests.get())
			// A missing required mod must be repaired on the next launch, even with an unchanged pack.
			assertTrue(target.delete())
			UpdateManager(opts, ui)
			assertEquals(jar, target.readText())
			assertEquals(2, prompts)
			assertEquals(2, requests.get())
		} finally {
			server.stop(0)
		}
	}
}
