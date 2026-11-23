package link.infra.packwiz.installer

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

class LauncherBrandingLoaderTest {
	@get:Rule val temp = TemporaryFolder()

	@Test fun readsOptionalBrandingNextToThePackAndConfinesBackgroundsToItsDirectory() {
		val root = temp.newFolder("pack")

		val config = root.resolve("packwiz-launcher.json")
		val assets = root.resolve("assets").also { it.mkdir() }
		val png = assets.resolve("背景.png")
		ImageIO.write(BufferedImage(4, 3, BufferedImage.TYPE_INT_ARGB), "png", png)
		val fallback = loadLauncherBranding(root.toPath())
		assertEquals("Shinkai Launcher", fallback.title)
		assertNull(fallback.background)
		config.writeText("{")
		assertEquals("", loadLauncherBranding(root.toPath()).title)
		config.writeText("""{"title":"Custom","subtitle":"Survival","background":"./assets/背景.png"}""")
		val branded = loadLauncherBranding(root.toPath())
		assertEquals("Custom", branded.title)
		assertEquals("Survival", branded.subtitle)
		assertEquals(4, branded.background?.getWidth(null))
		assertEquals(3, branded.background?.getHeight(null))

		png.copyTo(temp.root.resolve("outside.png"))
		root.resolve("invalid.png").writeText("invalid image")
		for (bad in listOf("../outside.png", "%2e%2e/outside.png", "/outside.png", "https://example.invalid/image.png", "invalid.png")) {
			config.writeText("""{"title":"Custom","background":"$bad"}""")
			val branding = loadLauncherBranding(root.toPath())
			assertEquals("Custom", branding.title)
			assertNull(branding.background)
		}
	}

	@Test fun rejectsOversizedDimensionsBeforeDecodingAndOversizedConfigBeforeParsing() {
		val root = temp.newFolder("pack")

		ImageIO.write(BufferedImage(8193, 1, BufferedImage.TYPE_INT_RGB), "png", root.resolve("long.png"))
		val config = root.resolve("packwiz-launcher.json")
		config.writeText("""{"title":"Custom","background":"long.png"}""")
		assertNull(loadLauncherBranding(root.toPath()).background)
		config.writeText("""{"title":"Custom","padding":"${"a".repeat(16384)}"}""")
		assertEquals("", loadLauncherBranding(root.toPath()).title)
	}

	@Test fun createsDefaultsThenPreservesAnEditedConfig() {
		val folder = temp.root.resolve("new-instance").toPath()
		val config = folder.resolve("packwiz-launcher.json").toFile()
		assertFalse(config.exists())
		assertEquals("Shinkai Launcher", loadLauncherBranding(folder).title)
		assertTrue(config.isFile)
		val edited = """{"title":"Local settings","subtitle":"Custom subtitle"}"""
		config.writeText(edited)
		assertEquals("Local settings", loadLauncherBranding(folder).title)
		assertEquals(edited, config.readText())
	}
}
