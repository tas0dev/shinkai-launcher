package shinkai.bootstrap;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Arrays;
import java.util.Comparator;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

public final class BootstrapTest {
	public static void main(String[] args) throws Exception {
		var key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
		Path root = Files.createTempDirectory("shinkai-bootstrap-test-");
		try {
			Path cache = Files.createDirectory(root.resolve(".shinkai-update"));
			Path starter = fixture(root.resolve(Release.LAUNCHER), "starter");
			Path upgrade = fixture(root.resolve("candidate.jar"), "upgrade");
			var first = SignRelease.describe(1, starter);
			var second = SignRelease.describe(2, upgrade);
			byte[] upgradeJar = Files.readAllBytes(upgrade);
			byte[] firstMetadata = signed(first, key.getPrivate());
			byte[] secondMetadata = signed(second, key.getPrivate());
			Bootstrap.Download offline = path -> { throw new IOException("offline"); };
			var publicKey = key.getPublic();

			fails(() -> Bootstrap.selectLauncher(root, cache, publicKey, offline), "An unsigned starter was accepted");
			Files.write(root.resolve(Release.METADATA), firstMetadata);
			check(Bootstrap.selectLauncher(root, cache, publicKey, offline).equals(starter), "Signed starter didn't start offline");
			int status = Bootstrap.launch(root, starter, new String[] {"引数 with spaces"});
			check(status == 7 && Files.readString(root.resolve("invoked.txt")).equals("引数 with spaces"), "Child process did not preserve location, args or status");

			byte[] forged = secondMetadata.clone();
			forged[forged.length - 5] = forged[forged.length - 5] == 'A' ? (byte) 'B' : (byte) 'A';
			check(Bootstrap.selectLauncher(root, cache, publicKey, remote(forged, 2, upgradeJar)).equals(starter), "Forged release replaced starter");
			check(Bootstrap.selectLauncher(root, cache, publicKey, remote(secondMetadata, 2, Arrays.copyOf(upgradeJar, upgradeJar.length - 1)))
					.equals(starter), "A truncated download replaced the starter");
			byte[] tampered = upgradeJar.clone();
			tampered[20] ^= 1;
			check(Bootstrap.selectLauncher(root, cache, publicKey, remote(secondMetadata, 2, tampered)).equals(starter), "A bad digest replaced the starter");

			Path installed = Bootstrap.selectLauncher(root, cache, publicKey, remote(secondMetadata, 2, upgradeJar));
			check(installed.equals(cache.resolve(second.sha256() + ".jar")), "Signed update was not selected");
			check(Bootstrap.selectLauncher(root, cache, publicKey, offline).equals(installed), "Cached launcher didn't start offline");
			check(Bootstrap.selectLauncher(root, cache, publicKey, remote(firstMetadata, 1, new byte[0])).equals(installed), "Downgrade was accepted");
			byte[] reusedSequence = signed(SignRelease.describe(2, starter), key.getPrivate());
			check(Bootstrap.selectLauncher(root, cache, publicKey, remote(reusedSequence, 2, new byte[0])).equals(installed), "Reused sequence was accepted");
			Files.writeString(installed, "tampered cache");
			fails(() -> Bootstrap.selectLauncher(root, cache, publicKey, offline), "A corrupt cached JAR was launched");
			check(Bootstrap.selectLauncher(root, cache, publicKey, remote(secondMetadata, 2, upgradeJar)).equals(installed), "Same release did not repair cache");
			try (var files = Files.list(cache)) {
				check(files.noneMatch(file -> file.getFileName().toString().endsWith(".part")), "Staging files remained");
			}
			check(Bootstrap.allowed(URI.create("https://release-assets.githubusercontent.com/path?query")), "GitHub asset host was rejected");
			check(Bootstrap.allowed(URI.create("https://github.com/tas0dev/shinkai-launcher/releases/download/shinkai-v1/launcher.jar")), "Release URL was rejected");
			check(!Bootstrap.allowed(URI.create("http://github.com/tas0dev/shinkai-launcher/releases/download/v1/a")), "HTTP was accepted");
			check(!Bootstrap.allowed(URI.create("https://github.com/other/repo/releases/download/v1/a")), "An unrelated repository was accepted");
			check(!Bootstrap.allowed(URI.create("https://github.com/tas0dev/shinkai-launcher/releases/%2e%2e/launcher.jar")), "Encoded path traversal was accepted");
			check(!Bootstrap.allowed(URI.create("https://release-assets.githubusercontent.com.example/path")), "An unrelated domain was accepted");
			System.out.println("Shinkai bootstrap tests passed");
		} finally {
			try (var files = Files.walk(root)) {
				for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
			}
		}
	}

	private static Bootstrap.Download remote(byte[] metadata, long sequence, byte[] jar) {
		return path -> {
			if (path.equals("latest/download/" + Release.METADATA)) return new ByteArrayInputStream(metadata);
			if (path.equals("download/shinkai-v" + sequence + "/" + Release.LAUNCHER)) return new ByteArrayInputStream(jar);
			throw new IOException("Unexpected path " + path);
		};
	}

	private static byte[] signed(Release release, java.security.PrivateKey key) throws Exception {
		var signer = Signature.getInstance("Ed25519");
		signer.initSign(key);
		signer.update(release.payload());
		return release.encode(signer.sign());
	}

	private static Path fixture(Path file, String content) throws Exception {
		String path = "link/infra/packwiz/installer/ShinaiLauncher.class";
		var manifest = new Manifest();
		manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
		manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "link.infra.packwiz.installer.ShinaiLauncher");
		try (var jar = new JarOutputStream(Files.newOutputStream(file), manifest);
			 var entry = BootstrapTest.class.getResourceAsStream("/" + path)) {
			jar.putNextEntry(new JarEntry(path));
			entry.transferTo(jar);
			jar.closeEntry();
			jar.putNextEntry(new JarEntry("version.txt"));
			jar.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			jar.closeEntry();
		}
		return file;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private static void fails(Throwing action, String message) throws Exception {
		try {
			action.run();
		} catch (IOException expected) {
			return;
		}
		throw new AssertionError(message);
	}

	@FunctionalInterface private interface Throwing { void run() throws Exception; }
}
