package link.infra.packwiz.installer;

import java.nio.file.Files;
import java.nio.file.Path;

public final class ShinaiLauncher {
	public static void main(String[] args) throws Exception {
		Path instance = Path.of(System.getProperty("shinkai.instanceDir"));
		if (!Files.isSameFile(instance, Path.of(".")) || args.length != 1) System.exit(1);
		Files.writeString(instance.resolve("invoked.txt"), args[0]);
		System.exit(7);
	}
}
