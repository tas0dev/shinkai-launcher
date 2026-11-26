package link.infra.packwiz.installer

import link.infra.packwiz.installer.request.RequestException
import link.infra.packwiz.installer.target.ClientHolder
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.SecureRandom

class SignedPackTest {
	private val latest = SignedPack.LATEST_URL.toHttpUrl()
	private val release = "/releases/release.Abc12345"
	private val packBytes = "name = \"Shinkai\"\r\n".toByteArray()
	private val key = Ed25519PrivateKeyParameters(SecureRandom())
	private val signature = Ed25519Signer().run {
		init(true, key)
		update(packBytes, 0, packBytes.size)
		generateSignature()
	}
	private val signedPack = SignedPack(latest) {
		SubjectPublicKeyInfo(AlgorithmIdentifier(ASN1ObjectIdentifier("1.3.101.112")), key.generatePublicKey().encoded).encoded
	}

	@Test fun usesTheFirstReleaseForThePackSignatureAndIndex() {
		withServer { client, paths ->
			val verified = signedPack.load(client)
			assertArrayEquals(packBytes, verified.bytes)
			val index = verified.path.resolve("index.toml").source(client).use { it.readUtf8() }
			assertEquals("index for the first release", index)
			assertEquals(listOf("/latest", "$release/pack.toml", "$release/pack.toml.sig", "$release/index.toml"), paths)
		}
	}

	@Test fun rejectsTampering() {
		withServer(pack = "name = \"Modified\"".toByteArray()) { client, _ ->
			val error = assertThrows(IOException::class.java) { signedPack.load(client) }
			assertTrue(error.message.orEmpty().contains("署名が一致しません"))
		}
	}

	@Test fun rejectsMissingSignature() {
		withServer(signatureStatus = 404) { client, paths ->
			assertThrows(RequestException.Response.HTTP.ErrorCode::class.java) { signedPack.load(client) }
			assertEquals("$release/pack.toml.sig", paths.last())
		}
	}

	@Test fun rejectsInvalidPublicKeyBeforeContactingServer() {
		withServer { client, paths ->
			assertThrows(IOException::class.java) { SignedPack(latest) { ByteArray(44) }.load(client) }
			assertTrue(paths.isEmpty())
		}
	}

	@Test fun rejectsCrossOriginAndNonReleaseLocations() {
		for (location in listOf("https://other.example$release/pack.toml", "http://mc.yunagi.uk$release/pack.toml", "/pack.toml")) {
			withServer(location = location) { client, paths ->
				assertThrows(IOException::class.java) { signedPack.load(client) }
				assertEquals(listOf("/latest"), paths)
			}
		}
	}

	@Test fun rejectsRedirectsAfterPinningARelease() {
		withServer(packRedirect = "/releases/release.Second/pack.toml") { client, paths ->
			assertThrows(RequestException.Response.HTTP.ErrorCode::class.java) { signedPack.load(client) }
			assertEquals(listOf("/latest", "$release/pack.toml"), paths)
		}
	}

	private fun withServer(
		location: String = "$release/pack.toml",
		pack: ByteArray = packBytes,
		signatureStatus: Int = 200,
		packRedirect: String? = null,
		test: (ClientHolder, List<String>) -> Unit,
	) {
		val paths = mutableListOf<String>()
		var current = location
		val client = ClientHolder {
			addInterceptor { chain ->
				val request = chain.request()
				val path = request.url.encodedPath
				paths += path
				val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
					.message("test").body("".toResponseBody())
				when (path) {
					"/latest" -> {
						response.code(302).header("Location", current)
						current = "/releases/release.Second/pack.toml"
					}
					"$release/pack.toml" -> if (packRedirect == null) {
						response.code(200).body(pack.toResponseBody())
					} else {
						response.code(302).header("Location", packRedirect)
					}
					"$release/pack.toml.sig" -> response.code(signatureStatus).body(signature.toResponseBody())
					"$release/index.toml" -> response.code(200).body("index for the first release".toResponseBody())
					else -> error("Unexpected request: ${request.url}")
				}
				response.build()
			}
		}
		try {
			test(client, paths)
		} finally {
			client.close()
		}
	}
}
