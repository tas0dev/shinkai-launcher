package link.infra.packwiz.installer.ui.gui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JProgressBar;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.plaf.basic.BasicProgressBarUI;

/** The bevels, pixel artwork and lettering used over the pack cover. */
final class PixelStyle {
	private static final Font DISPLAY = load();
	static final Color CREAM = new Color(0xF1E9CB);
	static final Color INK = new Color(0x20251A);

	private PixelStyle() {}

	static Font font(Font fallback, float size) {
		return (DISPLAY == null ? fallback : DISPLAY).deriveFont(size);
	}

	private static Font load() {
		try (InputStream input = PixelStyle.class.getResourceAsStream("/link/infra/packwiz/installer/ui/DotGothic16-Regular.ttf")) {
			if (input != null) return Font.createFont(Font.TRUETYPE_FONT, input);
		} catch (IOException | FontFormatException e) {
			link.infra.packwiz.installer.util.Log.INSTANCE.warn("Could not load DotGothic16", e);
		}
		return null;
	}

	static void styleButton(JButton button, boolean primary, boolean danger) {
		Color edge = new Color(primary ? 0x261F15 : 0x121C15);
		Color face = new Color(primary ? 0xDCD1A8 : 0x26372B);
		Color hover = new Color(primary ? 0xEFE0B7 : danger ? 0x703734 : 0x384E3D);
		Color light = new Color(primary ? 0xFFF0C5 : 0x738370);
		Color side = new Color(primary ? 0xF4E5B5 : 0x536853);
		Color shade = new Color(primary ? 0x95825A : 0x18251B);
		button.setUI(new BasicButtonUI() {
			@Override public void paint(Graphics graphics, JComponent component) {
				Graphics g = graphics.create();
				try {
					int width = component.getWidth();
					int height = component.getHeight();
					boolean pressed = button.getModel().isArmed() && button.getModel().isPressed();
					g.translate(0, pressed ? 2 : 0);
					g.setColor(edge);
					g.fillRect(0, 0, width, height);
					g.setColor(!button.isEnabled() ? new Color(0x2D382E) : button.getModel().isRollover() ? hover : face);
					g.fillRect(2, 2, width - 4, height - 4);
					g.setColor(pressed ? shade : light);
					g.fillRect(2, 2, width - 4, 2);
					g.setColor(pressed ? shade : side);
					g.fillRect(2, 4, 2, height - 6);
					g.setColor(pressed ? light : shade);
					g.fillRect(2, height - 6, width - 4, 4);
					g.fillRect(width - 4, 4, 2, height - 6);
					super.paint(g, component);
					if (button.hasFocus() && button.isFocusPainted()) {
						g.setColor(new Color(primary ? 0x554B33 : 0xFFF1BD));
						g.drawRect(4, 4, width - 9, height - 10);
					}
				} finally {
					g.dispose();
				}
			}
			@Override protected void paintFocus(Graphics g, javax.swing.AbstractButton b,
					java.awt.Rectangle view, java.awt.Rectangle text, java.awt.Rectangle icon) {}
		});
		button.setOpaque(false);
		button.setContentAreaFilled(false);
		button.setRolloverEnabled(true);
		button.setForeground(primary ? INK : CREAM);
		button.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
		button.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
	}

	static Icon icon(String name) {
		final BufferedImage pixels;
		String path = "/link/infra/packwiz/installer/ui/pixelarticons/" + name + ".png";
		try (InputStream input = PixelStyle.class.getResourceAsStream(path)) {
			if (input == null) throw new IOException("Missing icon resource");
			pixels = ImageIO.read(input);
			if (pixels == null || pixels.getWidth() != 24 || pixels.getHeight() != 24) {
				throw new IOException("Expected a 24×24 PNG");
			}
		} catch (IOException e) {
			throw new IllegalStateException("Could not load Pixelarticons icon: " + name, e);
		}
		return new Icon() {
			@Override public int getIconWidth() { return pixels.getWidth(); }
			@Override public int getIconHeight() { return pixels.getHeight(); }
			@Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
				Graphics2D g = (Graphics2D) graphics.create();
				try {
					g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
					g.drawImage(pixels, x, y, null);
				} finally {
					g.dispose();
				}
			}
		};
	}

	static void styleProgress(JProgressBar progress) {
		progress.setBorderPainted(false);
		progress.setUI(new BasicProgressBarUI() {
			@Override protected void paintDeterminate(Graphics g, JComponent component) {
				paintCells(g, component, (int) Math.floor(progress.getPercentComplete() * 16), -1);
			}

			@Override protected void paintIndeterminate(Graphics g, JComponent component) {
				int start = Math.min(15, getAnimationIndex() * 16 / Math.max(1, getFrameCount()));
				paintCells(g, component, 0, start);
			}

			@Override protected void setAnimationIndex(int index) {
				super.setAnimationIndex(index);
				progress.repaint();
			}

			private void paintCells(Graphics g, JComponent component, int filled, int moving) {
				int available = component.getWidth() - 10;
				int height = component.getHeight() - 10;
				g.setColor(new Color(0x1C2B20));
				g.fillRect(0, 0, component.getWidth(), component.getHeight());
				for (int i = 0; i < 16; i++) {
					int x = 5 + available * i / 16;
					int width = available * (i + 1) / 16 + 5 - x - 3;
					boolean lit = i < filled || (moving >= 0 && (i - moving + 16) % 16 < 3);
					g.setColor(new Color(lit ? 0xAEC881 : 0x354737));
					g.fillRect(x, 5, width, height);
					if (lit) {
						g.setColor(new Color(0xDCE5A7));
						g.fillRect(x, 5, width, 2);
						g.setColor(new Color(0x68854D));
						g.fillRect(x, height + 3, width, 2);
					}
				}
			}
		});
	}
}
