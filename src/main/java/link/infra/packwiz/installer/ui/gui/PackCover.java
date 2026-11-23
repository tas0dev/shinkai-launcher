package link.infra.packwiz.installer.ui.gui;

import link.infra.packwiz.installer.ui.data.LauncherBranding;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

/** A compact edition of the pack cover for the missing-mod dialog. */
final class PackCover extends JPanel {
	private static final Color PAPER = new Color(250, 247, 233);
	private static final Font DISPLAY_FONT = loadDisplayFont();
	private static final BufferedImage DEFAULT_ART = loadDefaultArt();

	private final JTextArea title = textArea("パック名");
	private final JTextArea subtitle = textArea("パックの説明");
	private BufferedImage artwork = DEFAULT_ART;
	private Font titleFamily = DISPLAY_FONT;
	private int layoutWidth = -1;
	private int layoutHeight;

	PackCover(LauncherBranding branding) {
		super(null);
		setBackground(new Color(25, 44, 42));
		setPreferredSize(new Dimension(400, 150));
		add(title);
		add(subtitle);
		setBranding(branding);
	}

	private static JTextArea textArea(String accessibleName) {
		JTextArea area = new JTextArea();
		area.setFont(UIManager.getFont("Label.font"));
		area.setEditable(false);
		area.setFocusable(false);
		area.setOpaque(false);
		area.setForeground(PAPER);
		area.setBorder(BorderFactory.createEmptyBorder());
		area.setLineWrap(true);
		area.setWrapStyleWord(true);
		area.getAccessibleContext().setAccessibleName(accessibleName);
		return area;
	}

	private static Font loadDisplayFont() {
		try (InputStream in = PackCover.class.getResourceAsStream("/link/infra/packwiz/installer/ui/YoungSerif-Regular.ttf")) {
			if (in != null) return Font.createFont(Font.TRUETYPE_FONT, in);
		} catch (IOException | FontFormatException ignored) {
			// The logical font also supplies glyphs outside the embedded font's range.
		}
		return new Font(Font.SERIF, Font.PLAIN, 1);
	}

	private static BufferedImage loadDefaultArt() {
		try (InputStream in = PackCover.class.getResourceAsStream("/link/infra/packwiz/installer/ui/evergreen-valley.jpg")) {
			if (in != null) return ImageIO.read(in);
		} catch (IOException ignored) {
			// The cover's solid background remains usable without artwork.
		}
		return null;
	}

	void setBranding(LauncherBranding branding) {
		String name = branding.getTitle();
		title.setText(name);
		title.setToolTipText(name);
		titleFamily = DISPLAY_FONT.canDisplayUpTo(name) < 0 ? DISPLAY_FONT : new Font(Font.SERIF, Font.PLAIN, 1);
		String description = branding.getSubtitle();
		subtitle.setText(description);
		subtitle.setToolTipText(description);
		subtitle.setVisible(description != null && !description.trim().isEmpty());
		artwork = branding.getBackground() == null ? DEFAULT_ART : branding.getBackground();
		layoutWidth = -1;
		revalidate();
		repaint();
	}

	@Override
	public void doLayout() {
		int width = getWidth();
		int height = getHeight();
		if (layoutWidth == width && layoutHeight == height) return;
		layoutWidth = width;
		layoutHeight = height;
		int inset = 22;
		int contentWidth = Math.max(1, width - 2 * inset);
		int bottom = height - 15;
		if (subtitle.isVisible()) {
			subtitle.setFont(UIManager.getFont("Label.font").deriveFont(14f));
			subtitle.setSize(contentWidth, 1);
			int linesHeight = Math.min(subtitle.getPreferredSize().height, height / 3);
			bottom -= linesHeight;
			subtitle.setBounds(inset, bottom, contentWidth, linesHeight);
			bottom -= 4;
		}

		int available = Math.max(20, bottom - 8);
		int size = 38;
		int titleHeight;
		do {
			title.setFont(titleFamily.deriveFont((float) size));
			title.setSize(contentWidth, 1);
			titleHeight = title.getPreferredSize().height;
			if (titleHeight <= available || size <= 18) break;
			size -= 4;
		} while (true);
		titleHeight = Math.min(titleHeight, available);
		title.setBounds(inset, bottom - titleHeight, contentWidth, titleHeight);
	}

	@Override
	protected void paintChildren(Graphics graphics) {
		Graphics2D g = (Graphics2D) graphics.create();
		try {
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			super.paintChildren(g);
		} finally {
			g.dispose();
		}
	}

	@Override
	protected void paintComponent(Graphics graphics) {
		super.paintComponent(graphics);
		int width = getWidth();
		int height = getHeight();
		if (artwork == null || width <= 0 || height <= 0) return;
		Graphics2D g = (Graphics2D) graphics.create();
		try {
			double scale = Math.max((double) width / artwork.getWidth(), (double) height / artwork.getHeight());
			int scaledWidth = (int) Math.ceil(artwork.getWidth() * scale);
			int scaledHeight = (int) Math.ceil(artwork.getHeight() * scale);
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(artwork, (int) ((width - scaledWidth) * .58), (int) ((height - scaledHeight) * .51),
					scaledWidth, scaledHeight, null);
			g.setPaint(new GradientPaint(0f, 0f, new Color(9, 23, 23, 170), width, 0f, new Color(9, 23, 23, 65)));
			g.fillRect(0, 0, width, height);
			g.setPaint(new GradientPaint(0f, 0f, new Color(9, 23, 23, 15), 0f, height, new Color(9, 23, 23, 205)));
			g.fillRect(0, 0, width, height);
		} finally {
			g.dispose();
		}
	}
}
