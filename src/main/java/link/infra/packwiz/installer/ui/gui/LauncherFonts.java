package link.infra.packwiz.installer.ui.gui;

import link.infra.packwiz.installer.util.Log;

import java.awt.Font;
import java.awt.FontFormatException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Locale;
import javax.swing.UIDefaults;
import javax.swing.UIManager;
import javax.swing.plaf.FontUIResource;

/** The bundled Japanese UI fonts, applied before Swing creates the first component. */
final class LauncherFonts {
	private static final String RESOURCE_ROOT = "/link/infra/packwiz/installer/ui/";
	private static final Font REGULAR = load("NotoSansJP-Regular.ttf");
	private static final Font MEDIUM = load("NotoSansJP-Medium.ttf");

	private LauncherFonts() {}

	static void install() {
		if (REGULAR == null) return;
		UIDefaults defaults = UIManager.getLookAndFeelDefaults();
		for (Object key : new ArrayList<>(defaults.keySet())) {
			if (!(key instanceof String) || !((String) key).toLowerCase(Locale.ROOT).endsWith("font")) continue;
			Object value = defaults.get(key);
			if (!(value instanceof Font)) continue;
			Font previous = (Font) value;
			float size = Math.max(13f, previous.getSize2D());
			Font replacement = previous.isBold() ? emphasis(previous, size) : REGULAR.deriveFont(size);
			defaults.put(key, new FontUIResource(replacement));
		}
	}

	static Font emphasis(Font fallback, float size) {
		return MEDIUM != null ? MEDIUM.deriveFont(size) : fallback.deriveFont(Font.BOLD, size);
	}

	private static Font load(String resource) {
		try (InputStream input = LauncherFonts.class.getResourceAsStream(RESOURCE_ROOT + resource)) {
			if (input == null) throw new IOException("Font resource is missing");
			return Font.createFont(Font.TRUETYPE_FONT, input);
		} catch (IOException | FontFormatException e) {
			Log.INSTANCE.warn("Could not load " + resource, e);
			return null;
		}
	}
}
