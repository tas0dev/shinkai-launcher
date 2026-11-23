package link.infra.packwiz.installer.ui.gui;

import javax.swing.ImageIcon;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import java.awt.Image;
import java.net.URL;

public final class LauncherIdentity {
	public static final String NAME = "Shinkai Launcher";
	public static final Image ICON = loadIcon();

	private LauncherIdentity() {}

	private static Image loadIcon() {
		URL resource = LauncherIdentity.class.getResource("/link/infra/packwiz/installer/ui/shinai-icon.png");
		return resource == null ? null : new ImageIcon(resource).getImage();
	}

	public static void showStartupError(String message) {
		JOptionPane pane = new JOptionPane(message, JOptionPane.ERROR_MESSAGE);
		JDialog dialog = pane.createDialog(null, NAME);
		dialog.setIconImage(ICON);
		try {
			dialog.setVisible(true);
		} finally {
			dialog.dispose();
		}
	}
}
