package shinkai.bootstrap;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.net.ssl.HttpsURLConnection;

public final class Bootstrap {
	private static final String RELEASES = "/tas0dev/shinkai-launcher/releases/";
	private static final URI GITHUB = URI.create("https://github.com" + RELEASES);
	private static final Pattern RELEASE_PATH = Pattern.compile(RELEASES
			+ "(?:latest/download/shinkai-launcher\\.update|download/shinkai-v[1-9][0-9]{0,17}/(?:launcher\\.jar|shinkai-launcher\\.update))");
	private static final Set<String> ASSET_HOSTS = Set.of("release-assets.githubusercontent.com", "objects.githubusercontent.com");
	private static final String CACHE = ".shinkai-update";
	private static final String ACTIVE = "current.update";
	private static final String LOCK = "running.lock";

	private Bootstrap() {}

	public static void main(String[] args) {
		try {
			Path instance = Path.of(Bootstrap.class.getProtectionDomain().getCodeSource().getLocation().toURI())
					.toRealPath().getParent();
			Path cache = instance.resolve(CACHE);
			Files.createDirectories(cache);
			if (!Files.isDirectory(cache, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid launcher cache directory");
			int status;
			// Hold the lock until the child exits, so simultaneous starts cannot replace or remove its JAR.
			try (var channel = FileChannel.open(cache.resolve(LOCK), StandardOpenOption.CREATE,
					StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
				 var lock = channel.tryLock()) {
				if (lock == null) throw new IOException("Shinkai Launcher is already running for this instance");
				Path launcher = selectLauncher(instance, cache, Release.trustedKey(), Bootstrap::download);
				status = launch(instance, launcher, args);
			}
			System.exit(status);
		} catch (Exception failure) {
			System.err.println("Shinkai Bootstrap: " + failure.getMessage());
			System.exit(1);
		}
	}

	@FunctionalInterface
	interface Download {
		InputStream open(String releasePath) throws IOException;
	}

	static Path selectLauncher(Path instance, Path cache, PublicKey key, Download remote)
			throws IOException, GeneralSecurityException {
		Path active = cache.resolve(ACTIVE);
		boolean installed = !Files.notExists(active, LinkOption.NOFOLLOW_LINKS);
		Release current = installed ? readLocalMetadata(active, key) : readStarter(instance, key);
		Path oldJar = current == null ? null : installed ? cache.resolve(current.sha256() + ".jar") : instance.resolve(Release.LAUNCHER);
		boolean oldJarValid = current != null && verifiedJar(oldJar, current);
		try {
			byte[] bytes;
			try (var in = remote.open("latest/download/" + Release.METADATA)) {
				bytes = Release.read(in);
			}
			Release latest = Release.verify(bytes, key);
			if (current != null && latest.sequence() < current.sequence()) throw new IOException("Refusing an older launcher release");
			if (current != null && latest.sequence() == current.sequence() && !latest.equals(current)) {
				throw new IOException("Launcher release number was reused with different contents");
			}
			if (oldJarValid && latest.equals(current)) return oldJar;
			return install(cache, active, latest, bytes, remote);
		} catch (IOException | GeneralSecurityException failure) {
			if (!oldJarValid) throw new IOException("No verified launcher is available: " + failure.getMessage(), failure);
			System.err.println("Shinkai Bootstrap: update skipped: " + failure.getMessage());
			return oldJar;
		}
	}

	private static Release readStarter(Path instance, PublicKey key) {
		Path metadata = instance.resolve(Release.METADATA);
		if (Files.notExists(metadata, LinkOption.NOFOLLOW_LINKS)) return null;
		try {
			return readLocalMetadata(metadata, key);
		} catch (IOException | GeneralSecurityException failure) {
			System.err.println("Shinkai Bootstrap: signed starter ignored: " + failure.getMessage());
			return null;
		}
	}

	private static Release readLocalMetadata(Path file, PublicKey key) throws IOException, GeneralSecurityException {
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid local launcher metadata");
		try (var in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
			return Release.verify(Release.read(in), key);
		}
	}

	private static boolean verifiedJar(Path file, Release release) {
		try {
			if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) != release.size()) return false;
			try (var in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
				release.checkJar(in, OutputStream.nullOutputStream());
			}
			return true;
		} catch (IOException | GeneralSecurityException failure) {
			return false;
		}
	}

	private static Path install(Path cache, Path active, Release latest, byte[] metadata, Download remote)
			throws IOException, GeneralSecurityException {
		Path destination = cache.resolve(latest.sha256() + ".jar");
		Path staged = Files.createTempFile(cache, "launcher-", ".part");
		try {
			try (var in = remote.open("download/shinkai-v" + latest.sequence() + "/" + Release.LAUNCHER);
				 var out = Files.newOutputStream(staged)) {
				latest.checkJar(in, out);
			}
			Release.requireLauncherJar(staged);
			replace(staged, destination);
			staged = Files.createTempFile(cache, "release-", ".part");
			Files.write(staged, metadata);
			// The signed pointer is the commit. Until it is replaced the old launcher remains usable.
			replace(staged, active);
		} finally {
			Files.deleteIfExists(staged);
		}
		try (var jars = Files.newDirectoryStream(cache, "*.jar")) {
			for (Path old : jars) {
				if (!old.equals(destination) && old.getFileName().toString().matches("[a-f0-9]{64}\\.jar")
						&& Files.isRegularFile(old, LinkOption.NOFOLLOW_LINKS)) Files.deleteIfExists(old);
			}
		} catch (IOException failure) {
			System.err.println("Shinkai Bootstrap: could not remove an unused launcher: " + failure.getMessage());
		}
		return destination;
	}

	private static void replace(Path staged, Path destination) throws IOException {
		try (var channel = FileChannel.open(staged, StandardOpenOption.WRITE)) {
			channel.force(true);
		}
		Files.move(staged, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
	}

	static int launch(Path instance, Path launcher, String[] args) throws IOException, InterruptedException {
		String executable = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows") ? "java.exe" : "java";
		Path java = Path.of(System.getProperty("java.home"), "bin", executable);
		var command = new ArrayList<>(List.of(java.toString(), "-Dshinkai.instanceDir=" + instance, "-jar", launcher.toString()));
		command.addAll(List.of(args));
		return new ProcessBuilder(command).directory(instance.toFile()).inheritIO().start().waitFor();
	}

	static boolean allowed(URI uri) {
		return "https".equals(uri.getScheme()) && uri.getUserInfo() == null && uri.getFragment() == null
				&& (uri.getPort() == -1 || uri.getPort() == 443) && uri.getHost() != null
				&& ("github.com".equals(uri.getHost()) && RELEASE_PATH.matcher(uri.getRawPath()).matches()
						|| ASSET_HOSTS.contains(uri.getHost()));
	}

	private static InputStream download(String path) throws IOException {
		URI uri = GITHUB.resolve(path);
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(path.endsWith(".jar") ? 90 : 15);
		for (int redirects = 0; redirects <= 5; redirects++) {
			if (!allowed(uri)) throw new IOException("GitHub redirected outside the allowed release hosts");
			checkDeadline(deadline);
			var connection = (HttpsURLConnection) uri.toURL().openConnection();
			connection.setInstanceFollowRedirects(false);
			connection.setConnectTimeout(4000);
			connection.setReadTimeout(5000);
			connection.setUseCaches(false);
			connection.setRequestProperty("User-Agent", "Shinkai-Bootstrap/1");
			connection.setRequestProperty("Accept-Encoding", "identity");
			try {
				int status = connection.getResponseCode();
				if (status == 200) {
					String encoding = connection.getContentEncoding();
					if (encoding != null && !"identity".equalsIgnoreCase(encoding)) throw new IOException("Unexpected release content encoding");
					return timed(connection, deadline);
				}
				if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308) {
					throw new IOException("GitHub Releases returned HTTP " + status);
				}
				String location = connection.getHeaderField("Location");
				if (location == null) throw new IOException("GitHub redirect missing its destination");
				uri = uri.resolve(location);
			} catch (IllegalArgumentException invalid) {
				connection.disconnect();
				throw new IOException("Invalid GitHub release redirect", invalid);
			} catch (IOException failure) {
				connection.disconnect();
				throw failure;
			}
			connection.disconnect();
		}
		throw new IOException("Too many GitHub release redirects");
	}

	private static InputStream timed(HttpsURLConnection connection, long deadline) throws IOException {
		return new FilterInputStream(connection.getInputStream()) {
			@Override public int read() throws IOException {
				checkDeadline(deadline);
				return super.read();
			}

			@Override public int read(byte[] buffer, int offset, int length) throws IOException {
				checkDeadline(deadline);
				return super.read(buffer, offset, length);
			}

			@Override public void close() throws IOException {
				try {
					super.close();
				} finally {
					connection.disconnect();
				}
			}
		};
	}

	private static void checkDeadline(long deadline) throws IOException {
		if (System.nanoTime() - deadline >= 0) throw new IOException("GitHub release download timed out");
	}
}
