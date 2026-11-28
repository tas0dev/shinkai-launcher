package shinkai.bootstrap;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.HexFormat;

/** Local release tooling. This class is kept out of the bootstrap and launcher JARs. */
public final class SignRelease {
	private SignRelease() {}

	public static void main(String[] args) throws Exception {
		if (args.length != 4) throw new IllegalArgumentException("Expected sequence, launcher JAR, private key DER and output folder");
		long sequence = Long.parseLong(args[0]);
		Path source = Path.of(args[1]);
		Path output = Path.of(args[3]);
		Files.createDirectories(output);
		Path staged = Files.createTempFile(output, "signed-launcher-", ".part");
		try {
			if (Files.size(source) > Release.MAX_JAR) throw new IOException("Launcher JAR exceeds the bootstrap size limit");
			Files.copy(source, staged, StandardCopyOption.REPLACE_EXISTING);
			Release.requireLauncherJar(staged);
			var release = describe(sequence, staged);
			var key = Release.trustedKey();
			var signer = Signature.getInstance("Ed25519");
			try (var in = Files.newInputStream(Path.of(args[2]))) {
				signer.initSign(KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(Release.read(in))));
			}
			signer.update(release.payload());
			byte[] signed = release.encode(signer.sign());
			Release.verify(signed, key);
			Path metadata = output.resolve(Release.METADATA);
			if (Files.exists(metadata)) {
				try (var in = Files.newInputStream(metadata)) {
					var previous = Release.verify(Release.read(in), key);
					if (release.sequence() < previous.sequence() || release.sequence() == previous.sequence() && !release.equals(previous)) {
						throw new IOException("Use a new release sequence for different launcher contents");
					}
				}
			}
			Files.move(staged, output.resolve(Release.LAUNCHER), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			Path signedMetadata = Files.createTempFile(output, "signed-metadata-", ".part");
			try {
				Files.write(signedMetadata, signed);
				Files.move(signedMetadata, metadata, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} finally {
				Files.deleteIfExists(signedMetadata);
			}
			System.out.println("Signed Shinkai release shinkai-v" + sequence + " (SHA-256 " + release.sha256() + ")");
		} finally {
			Files.deleteIfExists(staged);
		}
	}

	static Release describe(long sequence, Path file) throws Exception {
		long size = Files.size(file);
		try (var in = Files.newInputStream(file)) {
			return new Release(sequence, size, HexFormat.of().formatHex(Release.digest(size, in, OutputStream.nullOutputStream())));
		}
	}
}
