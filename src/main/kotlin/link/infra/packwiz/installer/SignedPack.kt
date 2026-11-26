package link.infra.packwiz.installer

import link.infra.packwiz.installer.target.ClientHolder
import link.infra.packwiz.installer.target.path.HttpUrlPath
import okhttp3.CacheControl
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.IOException

internal class SignedPack(private val latestUrl: HttpUrl, private val readPublicKey: () -> ByteArray) {
	data class Verified(val path: HttpUrlPath, val bytes: ByteArray)

	fun load(clientHolder: ClientHolder): Verified {
		val publicKey = try {
			val keyInfo = SubjectPublicKeyInfo.getInstance(readPublicKey())
			require(keyInfo.algorithm.algorithm == ED25519_OID)
			require(keyInfo.algorithm.parameters == null)
			Ed25519PublicKeyParameters(keyInfo.publicKeyData.octets)
		} catch (e: Exception) {
			throw IOException("ランチャーの配布用公開鍵が見つからないか、形式が不正です。", e)
		}
		val releaseUrl = resolveRelease(clientHolder)
		val packPath = HttpUrlPath(requireNotNull(releaseUrl.resolve(".")), "pack.toml", followRedirects = false)
		val bytes = packPath.source(clientHolder).use {
			if (it.request(MAX_PACK_BYTES + 1)) throw IOException("pack.toml が大きすぎます。")
			it.readByteArray()
		}
		val signature = packPath.resolve("pack.toml.sig").source(clientHolder).use {
			if (!it.request(SIGNATURE_BYTES) || it.request(SIGNATURE_BYTES + 1)) {
				throw IOException("pack.toml.sig のサイズが不正です。")
			}
			it.readByteArray()
		}
		val verifier = Ed25519Signer()
		verifier.init(false, publicKey)
		verifier.update(bytes, 0, bytes.size)
		if (!verifier.verifySignature(signature)) throw IOException("pack.toml の署名が一致しません。")
		return Verified(packPath, bytes)
	}

	private fun resolveRelease(clientHolder: ClientHolder): HttpUrl {
		val request = Request.Builder().url(latestUrl).cacheControl(CacheControl.FORCE_NETWORK).build()
		return clientHolder.directHttpClient.newCall(request).execute().use { response ->
			if (response.code !in TEMPORARY_REDIRECTS) {
				throw IOException("配信中のリリースを取得できませんでした（HTTP ${response.code}）。")
			}
			val target = response.header("Location")?.let(latestUrl::resolve)
			if (target == null || target.scheme != "https" || target.host != latestUrl.host ||
				target.port != latestUrl.port || target.username.isNotEmpty() || target.password.isNotEmpty() ||
				target.query != null || target.fragment != null || !RELEASE_PATH.matches(target.encodedPath)) {
				throw IOException("配信中のリリースの URL が不正です。")
			}
			target
		}
	}

	companion object {
		const val LATEST_URL = "https://mc.yunagi.uk/latest"
		private val productionUrl = LATEST_URL.toHttpUrl()
		private val ED25519_OID = ASN1ObjectIdentifier("1.3.101.112")
		private val RELEASE_PATH = Regex("/releases/release\\.[A-Za-z0-9_-]+/pack\\.toml")
		private val TEMPORARY_REDIRECTS = setOf(302, 303, 307)
		private const val MAX_PACK_BYTES = 1024L * 1024
		private const val SIGNATURE_BYTES = 64L

		fun forHost(url: HttpUrl): SignedPack? {
			if (url.host.trimEnd('.') != productionUrl.host) return null
			return SignedPack(productionUrl) {
				val key = SignedPack::class.java.getResourceAsStream("shinkai-pack-signing.der")
					?: throw IOException("The JAR has no Shinkai signing public key.")
				key.use { it.readBytes() }
			}
		}
	}
}
