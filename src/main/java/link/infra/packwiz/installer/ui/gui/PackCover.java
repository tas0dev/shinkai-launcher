package link.infra.packwiz.installer.ui.gui;

import link.infra.packwiz.installer.ui.data.LauncherBranding;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.font.TextAttribute;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;

/** Artwork and pack information for the home screen. */
final class PackCover extends JPanel {
	private static final Color PAPER = new Color(0xFFF3CC);
	private static final BufferedImage DEFAULT_ART = loadDefaultArt();

	private final JTextArea title = textArea("パック名");
	private final JTextArea subtitle = textArea("パックの説明");
	private final JComponent launchArea;
	private final JComponent titleBar;
	private final JComponent cornerActions;
	private Image artwork = DEFAULT_ART;
	private Font titleFamily = PixelStyle.font(new Font(Font.SANS_SERIF, Font.PLAIN, 1), 1f);
	private int layoutWidth = -1;
	private int layoutHeight;

	PackCover(LauncherBranding branding, JComponent launchArea, JComponent titleBar, JComponent cornerActions) {
		super(null);
		this.launchArea = launchArea;
		this.titleBar = titleBar;
		this.cornerActions = cornerActions;
		setBackground(new Color(25, 44, 42));
		setPreferredSize(new Dimension(1152, 700));
		add(titleBar);
		add(cornerActions);
		add(title);
		add(subtitle);
		add(launchArea);
		setBranding(branding);
	}

	private static JTextArea textArea(String accessibleName) {
		JTextArea area = new JTextArea() {
			private boolean paintingShadow;

			@Override public Color getForeground() {
				return paintingShadow ? new Color(0x1E2A1E) : super.getForeground();
			}

			@Override protected void paintComponent(Graphics graphics) {
				Graphics shadow = graphics.create();
				try {
					paintingShadow = true;
					shadow.translate(3, 4);
					super.paintComponent(shadow);
				} finally {
					paintingShadow = false;
					shadow.dispose();
				}
				super.paintComponent(graphics);
			}
		};
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
		titleFamily = PixelStyle.font(UIManager.getFont("Label.font"), 1f);
		if (titleFamily.canDisplayUpTo(name) >= 0) titleFamily = UIManager.getFont("Label.font");
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
		int titleBarHeight = titleBar.getPreferredSize().height;
		titleBar.setBounds(0, 0, width, titleBarHeight);
		cornerActions.setBounds(0, height - 60, width - 17, 44);
		int inset = Math.max(24, (int) (width * .07));
		int contentWidth = Math.max(1, width - 2 * inset);
		int bottom = height - Math.max(64, Math.min(105, (int) (width * .09)));
		launchArea.setBounds(inset, bottom - 63, Math.min(contentWidth, 390), 63);
		bottom -= 90;
		if (subtitle.isVisible()) {
			int subtitleWidth = Math.min(contentWidth, 620);
			Font fallback = UIManager.getFont("Label.font");
			Font font = PixelStyle.font(fallback, 18f);
			Font subtitleFont = font.canDisplayUpTo(subtitle.getText()) < 0 ? font : fallback.deriveFont(18f);
			subtitle.setFont(subtitleFont.deriveFont(Collections.singletonMap(TextAttribute.TRACKING, .06f)));
			subtitle.setSize(subtitleWidth, 1);
			int linesHeight = Math.min(subtitle.getPreferredSize().height, height / 3);
			bottom -= linesHeight;
			subtitle.setBounds(inset, bottom, subtitleWidth, linesHeight);
		}

		int available = Math.max(20, bottom - titleBarHeight - 8);
		boolean longTitle = title.getText().codePointCount(0, title.getText().length()) > 32;
		int size = Math.min(longTitle ? 66 : 110, Math.max(56, (int) (width * .095)));
		int titleWidth = Math.min(contentWidth, longTitle ? 880 : Math.max(480, size * 6));
		int titleHeight;
		do {
			title.setFont(titleFamily.deriveFont((float) size)
					.deriveFont(Collections.singletonMap(TextAttribute.TRACKING, .05f)));
			title.setSize(titleWidth, 1);
			titleHeight = title.getPreferredSize().height;
			if (titleHeight <= available || size <= 18) break;
			size -= 4;
		} while (true);
		titleHeight = Math.min(titleHeight, available);
		title.setBounds(inset, bottom - titleHeight, titleWidth, titleHeight + 4);
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
		int imageWidth = artwork.getWidth(this);
		int imageHeight = artwork.getHeight(this);
		if (imageWidth <= 0 || imageHeight <= 0) return;
		Graphics2D g = (Graphics2D) graphics.create();
		try {
			double scale = Math.max((double) width / imageWidth, (double) height / imageHeight);
			int scaledWidth = (int) Math.ceil(imageWidth * scale);
			int scaledHeight = (int) Math.ceil(imageHeight * scale);
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(artwork, (int) ((width - scaledWidth) * .58), (int) ((height - scaledHeight) * .51),
					scaledWidth, scaledHeight, this);
			g.setPaint(new GradientPaint(0f, 0f, new Color(11, 24, 18, 121), width * .66f, 0f, new Color(11, 24, 18, 0)));
			g.fillRect(0, 0, width, height);
			g.setPaint(new GradientPaint(0f, height * .28f, new Color(17, 35, 27, 25), 0f, height, new Color(16, 27, 21, 240)));
			g.fillRect(0, 0, width, height);
			g.setPaint(new GradientPaint(0f, 0f, new Color(14, 26, 21, 160), 0f, height * .28f, new Color(14, 26, 21, 0)));
			g.fillRect(0, 0, width, height);
		} finally {
			g.dispose();
		}
	}
}
