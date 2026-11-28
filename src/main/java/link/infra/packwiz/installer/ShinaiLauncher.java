package link.infra.packwiz.installer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Entry point for the distribution JAR. The bootstrapper calls Main directly. */
public final class ShinaiLauncher {
	private ShinaiLauncher() {}

	public static void main(String[] args) throws Exception {
		if (args.length == 0) {
			Path jar = Paths.get(ShinaiLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
			String instance = System.getProperty("shinkai.instanceDir");
			Path folder = instance != null ? Paths.get(instance)
					: Files.isDirectory(jar) ? Paths.get("").toAbsolutePath() : jar.getParent();
			args = new String[] {"--pack-folder", folder.toString(), SignedPack.LATEST_URL};
		}
		new Main(args);
	}
}
