package link.infra.packwiz.installer

import com.sun.net.httpserver.HttpServer
import link.infra.packwiz.installer.target.Side
import link.infra.packwiz.installer.target.path.PackwizFilePath
import link.infra.packwiz.installer.ui.IUserInterface
import link.infra.packwiz.installer.ui.cli.CLIHandler
import link.infra.packwiz.installer.ui.data.ExceptionDetails
import link.infra.packwiz.installer.ui.data.IOptionDetails
import okio.ByteString.Companion.encodeUtf8
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress

class OptionalModsIntegrationTest {
	@get:Rule val temp = TemporaryFolder()

	@Test fun launchesWithSavedChoicesAndDefaultsUnlessSettingsWereRequested() {
		val pack = temp.newFolder("pack")
		val instance = temp.newFolder("instance")
		val jars = "contents of an optional mod"
		val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
		server.createContext("/") { exchange ->
			val bytes = jars.toByteArray(Charsets.UTF_8)
			exchange.sendResponseHeaders(200, bytes.size.toLong())
			exchange.responseBody.use { it.write(bytes) }
		}
		server.start()
		try {
			pack.resolve("mods").mkdir()
			fun writePack(title: String, mods: Map<String, Boolean>) {
				val index = buildString {
					appendLine("hash-format = \"sha256\"")
					for ((name, default) in mods) {
						val path = "mods/$name.pw.toml"
						val metadata = """
							name = "$name"
							filename = "$name.jar"
							[download]
							url = "http://127.0.0.1:${server.address.port}/$name.jar"
							hash-format = "sha256"
							hash = "${jars.encodeUtf8().sha256().hex()}"
							[option]
							optional = true
							default = $default
						""".trimIndent()
						pack.resolve(path).writeText(metadata)
						appendLine("[[files]]")
						appendLine("file = \"$path\"")
						appendLine("hash = \"${metadata.encodeUtf8().sha256().hex()}\"")
						appendLine("metafile = true")
					}
				}
				pack.resolve("index.toml").writeText(index)
				pack.resolve("pack.toml").writeText("""
					name = "$title"
					[index]
					file = "index.toml"
					hash-format = "sha256"
					hash = "${index.encodeUtf8().sha256().hex()}"
				""".trimIndent())
			}

			var dialogs = 0
			val ui = object : IUserInterface by CLIHandler() {
				override val selectNewOptionalMods = false
				override fun showErrorAndExit(message: String, e: Exception?): Nothing = throw AssertionError(message, e)
				override fun showExceptions(exceptions: List<ExceptionDetails>, numTotal: Int, allowsIgnore: Boolean): IUserInterface.ExceptionListResult =
					throw AssertionError("Unexpected installer error", exceptions.first().exception)
				override fun showOptions(options: List<IOptionDetails>): Boolean {
					assertTrue("Only an explicit request may open the options dialog", optionsButtonPressed)
					dialogs++
					val choices = options.associateBy { it.name }
					assertTrue(choices.getValue("enabled").optionValue)
					assertFalse(choices.getValue("disabled").optionValue)
					choices.getValue("enabled").optionValue = false
					choices.getValue("disabled").optionValue = true
					return false
				}
			}
			val folder = PackwizFilePath(instance.toOkioPath())
			val opts = UpdateManager.Options(PackwizFilePath(pack.toOkioPath(), "pack.toml"), folder / "packwiz.json", folder, folder, Side.CLIENT)
			val defaults = mapOf("enabled" to true, "disabled" to false)
			fun installed(name: String) = instance.resolve("mods/$name.jar").isFile

			writePack("First install", defaults)
			UpdateManager(opts, ui)
			assertTrue(installed("enabled"))
			assertFalse(installed("disabled"))

			ui.optionsButtonPressed = true
			UpdateManager(opts, ui)
			ui.optionsButtonPressed = false
			assertFalse(installed("enabled"))
			assertTrue(installed("disabled"))

			UpdateManager(opts, ui)
			writePack("Same index", defaults)
			UpdateManager(opts, ui)
			writePack("New optional mod", defaults + ("new" to true))
			UpdateManager(opts, ui)
			assertFalse(installed("enabled"))
			assertTrue(installed("disabled"))
			assertTrue(installed("new"))
			assertEquals(1, dialogs)
		} finally {
			server.stop(0)
		}
	}
}
