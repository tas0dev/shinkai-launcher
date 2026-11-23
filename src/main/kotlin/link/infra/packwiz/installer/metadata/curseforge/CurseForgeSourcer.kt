package link.infra.packwiz.installer.metadata.curseforge

import com.google.gson.Gson
import link.infra.packwiz.installer.metadata.ModFile
import link.infra.packwiz.installer.target.path.HttpUrlPath
import link.infra.packwiz.installer.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString.Companion.decodeBase64
import java.io.IOException
import java.nio.charset.StandardCharsets

private class GetFilesRequest(val fileIds: List<Int>)
private class GetModsRequest(val modIds: List<Int>)

private class GetFilesResponse {
	class CfFile {
		var id = 0
		var modId = 0
		var downloadUrl: String? = null
	}
	val data = mutableListOf<CfFile>()
}

private class GetModsResponse {
	class CfMod {
		var id = 0
		var links: CfLinks? = null
	}
	class CfLinks {
		var websiteUrl = ""
	}
	val data = mutableListOf<CfMod>()
}

private const val APIServer = "api.curseforge.com"
// If you fork/derive from packwiz, I request that you obtain your own API key.
private val APIKey = "JDJhJDEwJHNBWVhqblU1N0EzSmpzcmJYM3JVdk92UWk2NHBLS3BnQ2VpbGc1TUM1UGNKL0RYTmlGWWxh".decodeBase64()!!
	.string(StandardCharsets.UTF_8)

private inline fun <reified T> requestData(client: OkHttpClient, endpoint: String, payload: Any): T {
	val request = Request.Builder()
		.url("https://$APIServer/v1/$endpoint")
		.header("Accept", "application/json")
		.header("User-Agent", "packwiz-installer")
		.header("X-API-Key", APIKey)
		.post(Gson().toJson(payload).toRequestBody("application/json".toMediaType()))
		.build()
	client.newCall(request).execute().use { response ->
		if (!response.isSuccessful) throw IOException("CurseForge $endpoint returned HTTP ${response.code}")
		val body = response.body ?: throw IOException("CurseForge $endpoint returned an empty response")
		return Gson().fromJson(body.charStream(), T::class.java)
			?: throw IOException("CurseForge $endpoint returned an empty response")
	}
}

fun resolveCfMetadata(mods: List<ModFile>, client: OkHttpClient) {
	val ids = mods.mapNotNull { (it.update["curseforge"] as? CurseForgeUpdateData)?.fileId }.distinct()
	if (ids.isEmpty()) return
	val files = try {
		requestData<GetFilesResponse>(client, "mods/files", GetFilesRequest(ids)).data.associateBy { it.id }
	} catch (e: Exception) {
		mods.forEach { it.curseForgeResolutionError = e }
		return
	}

	for (mod in mods) {
		val update = mod.update["curseforge"] as? CurseForgeUpdateData ?: continue
		val file = files[update.fileId]?.takeIf { it.modId == update.projectId }
		val url = file?.downloadUrl?.toHttpUrlOrNull()
		if (url != null) {
			mod.resolvedUpdateData["curseforge"] = HttpUrlPath(url)
		} else {
			mod.curseForgeResolutionError = IOException("CurseForge did not provide a download URL for file ${update.fileId}")
		}
	}
}

// Only look up website slugs for files that actually need a browser download.
fun getCfProjectPages(projectIds: List<Int>, client: OkHttpClient): Map<Int, String> {
	if (projectIds.isEmpty()) return emptyMap()
	return try {
		requestData<GetModsResponse>(client, "mods", GetModsRequest(projectIds)).data.mapNotNull { mod ->
			mod.links?.websiteUrl?.toHttpUrlOrNull()?.let { mod.id to it.toString().trimEnd('/') }
		}.toMap()
	} catch (e: Exception) {
		Log.warn("Could not look up CurseForge file pages, using project pages", e)
		emptyMap()
	}
}
