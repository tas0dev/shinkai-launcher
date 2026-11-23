package link.infra.packwiz.installer

import com.sun.net.httpserver.HttpServer
import link.infra.packwiz.installer.target.ClientHolder
import link.infra.packwiz.installer.target.path.HttpUrlPath
import link.infra.packwiz.installer.target.path.PackwizFilePath
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Path.Companion.toOkioPath
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

class LauncherBrandingLoaderTest {
	@get:Rule val temp = TemporaryFolder()
	private val client = ClientHolder()

	@Test fun readsOptionalBrandingNextToThePackAndConfinesBackgroundsToItsDirectory() {
		val root = temp.newFolder("pack")
		val pack = PackwizFilePath(root.toOkioPath(), "pack.toml")
		val config = root.resolve("packwiz-launcher.json")
		val assets = root.resolve("assets").also { it.mkdir() }
		val png = assets.resolve("背景.png")
		ImageIO.write(BufferedImage(4, 3, BufferedImage.TYPE_INT_ARGB), "png", png)
		val fallback = loadLauncherBranding(pack, "Pack", client)
		assertEquals("Pack", fallback.title)
		assertNull(fallback.background)
		config.writeText("{")
		assertEquals(fallback, loadLauncherBranding(pack, "Pack", client))
		config.writeText("""{"title":"Custom","subtitle":"Survival","background":"./assets/背景.png"}""")
		val branded = loadLauncherBranding(pack, "Pack", client)
		assertEquals("Custom", branded.title)
		assertEquals("Survival", branded.subtitle)
		assertEquals(4, branded.background?.width)
		assertEquals(3, branded.background?.height)

		png.copyTo(temp.root.resolve("outside.png"))
		root.resolve("invalid.png").writeText("invalid image")
		for (bad in listOf("../outside.png", "%2e%2e/outside.png", "/outside.png", "https://example.invalid/image.png", "invalid.png")) {
			config.writeText("""{"title":"Custom","background":"$bad"}""")
			val branding = loadLauncherBranding(pack, "Pack", client)
			assertEquals("Custom", branding.title)
			assertNull(branding.background)
		}
	}

	@Test fun rejectsOversizedDimensionsBeforeDecodingAndOversizedConfigBeforeParsing() {
		val root = temp.newFolder("pack")
		val pack = PackwizFilePath(root.toOkioPath(), "pack.toml")
		ImageIO.write(BufferedImage(8193, 1, BufferedImage.TYPE_INT_RGB), "png", root.resolve("long.png"))
		val config = root.resolve("packwiz-launcher.json")
		config.writeText("""{"title":"Custom","background":"long.png"}""")
		assertNull(loadLauncherBranding(pack, "Pack", client).background)
		config.writeText("""{"title":"Custom","padding":"${"a".repeat(16384)}"}""")
		assertEquals("Pack", loadLauncherBranding(pack, "Pack", client).title)
	}

	@Test fun fetchesRelativeHttpAssetsAndDoesNotFollowRedirectsOutsideThePack() {
		val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
		val redirected = AtomicInteger()
		val base = "http://127.0.0.1:${server.address.port}"
		server.createContext("/packs/forest/packwiz-launcher.json") { exchange ->
			val data = """{"title":"Forest","background":"images/sky.png"}""".toByteArray()
			exchange.sendResponseHeaders(200, data.size.toLong())
			exchange.responseBody.use { it.write(data) }
		}
		val imageRequests = AtomicInteger()
		server.createContext("/packs/forest/images/sky.png") { exchange ->
			imageRequests.incrementAndGet()
			exchange.responseHeaders.add("Location", "$base/outside.png")
			exchange.sendResponseHeaders(302, -1)
			exchange.close()
		}
		server.createContext("/outside.png") { exchange ->
			redirected.incrementAndGet()
			exchange.sendResponseHeaders(404, -1)
			exchange.close()
		}
		server.start()
		try {
			val pack = HttpUrlPath("$base/packs/forest/pack.toml".toHttpUrl())
			val branding = loadLauncherBranding(pack, "Pack", client)
			assertEquals("Forest", branding.title)
			assertNull(branding.background)
			assertEquals(1, imageRequests.get())
			assertEquals(0, redirected.get())
		} finally {
			server.stop(0)
			client.close()
		}
	}
}
