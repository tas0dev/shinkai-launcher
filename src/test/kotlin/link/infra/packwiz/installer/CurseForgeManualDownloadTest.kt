package link.infra.packwiz.installer

import link.infra.packwiz.installer.metadata.DownloadMode
import link.infra.packwiz.installer.metadata.IndexFile
import link.infra.packwiz.installer.metadata.ModFile
import link.infra.packwiz.installer.metadata.curseforge.CurseForgeUpdateData
import link.infra.packwiz.installer.metadata.curseforge.getCfProjectPages
import link.infra.packwiz.installer.metadata.curseforge.resolveCfMetadata
import link.infra.packwiz.installer.metadata.hash.HashFormat
import link.infra.packwiz.installer.target.ClientHolder
import link.infra.packwiz.installer.target.Side
import link.infra.packwiz.installer.target.path.PackwizFilePath
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.ByteString.Companion.encodeUtf8
import okio.Path.Companion.toOkioPath
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CurseForgeManualDownloadTest {
	@get:Rule val temp = TemporaryFolder()

	@Test fun resolvesDownloadableRestrictedAndMissingFilesIndividually() {
		val folder = PackwizFilePath(temp.newFolder("instance").toOkioPath())
		val files = (1..3).map { id ->
			val hash = "jar $id".encodeUtf8().sha1().hex()
			IndexFile.File(folder / "mods/$id.pw.toml", hash = hash, alias = null, metafile = true).also {
				it.linkedFile = ModFile("Mod $id", folder / "mods/$id.jar",
					download = ModFile.Download(null, HashFormat.SHA1, hash, DownloadMode.CURSEFORGE),
					update = mapOf("curseforge" to CurseForgeUpdateData(id, 10)))
			}
		}
		val client = OkHttpClient.Builder().addInterceptor { chain ->
			val json = when (chain.request().url.encodedPath) {
				"/v1/mods/files" -> """{"data":[{"id":1,"modId":10,"downloadUrl":"https://example.invalid/1.jar"},{"id":2,"modId":10,"downloadUrl":null}]}"""
				"/v1/mods" -> """{"data":[{"id":10,"links":{"websiteUrl":"https://www.curseforge.com/minecraft/mc-mods/example"}}]}"""
				else -> error("Unexpected request")
			}
			Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
				.code(200).message("OK").body(json.toResponseBody()).build()
		}.build()
		resolveCfMetadata(files.map { it.linkedFile!! }, client)
		assertEquals("https://example.invalid/1.jar", files.first().linkedFile!!.resolvedUpdateData["curseforge"].toString())

		val clientHolder = ClientHolder()
		val tasks = DownloadTask.createTasksFromIndex(IndexFile(HashFormat.SHA1, files), Side.CLIENT).drop(1)
		tasks.forEach {
			it.updateFromCache(null)
			it.download(folder, clientHolder)
		}
		val pages = getCfProjectPages(listOf(10), client)
		val manual = tasks.associateBy { it.manualDownload(folder, pages)!! }
		assertEquals(listOf("2", "3").map { "https://www.curseforge.com/minecraft/mc-mods/example/files/$it" },
			manual.keys.map { it.url })
		val offline = OkHttpClient.Builder().addInterceptor { throw java.io.IOException("offline") }.build()
		val unresolved = files.first().linkedFile!!.copy()
		resolveCfMetadata(listOf(unresolved), offline)
		assertEquals("offline", unresolved.curseForgeResolutionError?.message)
		val fallback = getCfProjectPages(listOf(10), offline)
		assertEquals("https://www.curseforge.com/projects/10", tasks.first().manualDownload(folder, fallback)!!.url)

		val scanner = ManualDownloadScanner(manual, folder)
		val downloads = temp.newFolder("downloads")
		downloads.resolve("download.jar").writeText("jar 3")
		assertEquals(listOf("3.jar"), scanner.scan(downloads.toPath()).map { it.filename })
		downloads.resolve("download (1).jar").writeText("jar 2")
		assertEquals(manual.keys, scanner.scan(downloads.toPath()))
		assertTrue(tasks.none { it.failed() })
		assertEquals("jar 2", (folder / "mods/2.jar").nioPath.toFile().readText())
		assertEquals("jar 3", (folder / "mods/3.jar").nioPath.toFile().readText())
	}
}
