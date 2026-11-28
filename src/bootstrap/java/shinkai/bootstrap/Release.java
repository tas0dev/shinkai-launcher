package shinkai.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

record Release(long sequence, long size, String sha256) {
	static final String METADATA = "shinkai-launcher.update";
	static final String LAUNCHER = "launcher.jar";
	static final int MAX_METADATA = 512;
	static final long MAX_JAR = 64L * 1024 * 1024;
	static final String KEY = "/shinkai/bootstrap/launcher-signing.der";
	private static final String FORMAT = "shinkai-launcher-update-v1\n";
	private static final Pattern RECORD = Pattern.compile(FORMAT
			+ "([1-9][0-9]{0,17})\n([1-9][0-9]{0,8})\n([a-f0-9]{64})\n([A-Za-z0-9+/]{86}==)\n");

	Release {
		if (sequence <= 0 || sequence > 999999999999999999L || size <= 0 || size > MAX_JAR
				|| !sha256.matches("[a-f0-9]{64}")) {
			throw new IllegalArgumentException("Invalid release number, size or digest");
		}
	}

	static PublicKey trustedKey() throws IOException, GeneralSecurityException {
		try (var resource = Release.class.getResourceAsStream(KEY)) {
			if (resource == null) throw new IOException("Launcher signing key missing from bootstrap");
			return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(read(resource)));
		}
	}

	static byte[] read(InputStream in) throws IOException {
		byte[] bytes = in.readNBytes(MAX_METADATA + 1);
		if (bytes.length > MAX_METADATA) throw new IOException("Release metadata exceeds its size limit");
		return bytes;
	}

	static Release verify(byte[] bytes, PublicKey key) throws GeneralSecurityException, IOException {
		var match = RECORD.matcher(new String(bytes, StandardCharsets.US_ASCII));
		if (!match.matches()) throw new IOException("Invalid release metadata");
		long size = Long.parseLong(match.group(2));
		if (size > MAX_JAR) throw new IOException("Signed launcher size exceeds its limit");
		var release = new Release(Long.parseLong(match.group(1)), size, match.group(3));
		byte[] signature = Base64.getDecoder().decode(match.group(4));
		if (!Arrays.equals(bytes, release.encode(signature))) throw new IOException("Noncanonical release metadata");
		Signature verifier = Signature.getInstance("Ed25519");
		verifier.initVerify(key);
		verifier.update(release.payload());
		if (!verifier.verify(signature)) throw new IOException("Invalid launcher release signature");
		return release;
	}

	byte[] payload() {
		return (FORMAT + sequence + "\n" + size + "\n" + sha256 + "\n").getBytes(StandardCharsets.US_ASCII);
	}

	byte[] encode(byte[] signature) {
		return (new String(payload(), StandardCharsets.US_ASCII) + Base64.getEncoder().encodeToString(signature) + "\n")
				.getBytes(StandardCharsets.US_ASCII);
	}

	static byte[] digest(long size, InputStream in, OutputStream out) throws IOException, GeneralSecurityException {
		var digest = MessageDigest.getInstance("SHA-256");
		byte[] buffer = new byte[32768];
		long count = 0;
		int read;
		while ((read = in.read(buffer)) != -1) {
			count += read;
			if (count > size) throw new IOException("Launcher JAR exceeds its signed size");
			digest.update(buffer, 0, read);
			out.write(buffer, 0, read);
		}
		if (count != size) throw new IOException("Launcher JAR does not match its signed size");
		return digest.digest();
	}

	void checkJar(InputStream in, OutputStream out) throws IOException, GeneralSecurityException {
		if (!MessageDigest.isEqual(digest(size, in, out), HexFormat.of().parseHex(sha256))) {
			throw new IOException("Launcher JAR does not match its signed SHA-256");
		}
	}

	static void requireLauncherJar(Path file) throws IOException {
		try (var jar = new JarFile(file.toFile(), false)) {
			var manifest = jar.getManifest();
			var attrs = manifest == null ? null : manifest.getMainAttributes();
			if (attrs == null || !"link.infra.packwiz.installer.ShinaiLauncher".equals(attrs.getValue(Attributes.Name.MAIN_CLASS))
					|| attrs.getValue(Attributes.Name.CLASS_PATH) != null
					|| jar.getJarEntry("link/infra/packwiz/installer/ShinaiLauncher.class") == null) {
				throw new IOException("Signed artifact is not a standalone Shinkai Launcher JAR");
			}
		}
	}
}
