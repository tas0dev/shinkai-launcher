package link.infra.packwiz.installer.ui.gui;

import link.infra.packwiz.installer.ui.data.InstallProgress;
import link.infra.packwiz.installer.ui.data.LauncherBranding;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.plaf.basic.BasicProgressBarUI;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.function.Consumer;

/** The pack home screen and packwiz progress. */
public final class InstallWindow extends JFrame {
	private static final Color PINE = new Color(0x10211F);
	private static final Color CREAM = new Color(0xFAF7E9);
	private final JLabel status = new JLabel("待機中");
	private final JButton launch = new JButton("起動", new PlayIcon());
	private final CardLayout launchCards = new CardLayout();
	private final JPanel launchArea = new JPanel(launchCards);
	private final JLabel stage = new JLabel();
	private final JLabel count = new JLabel();
	private final JProgressBar progress = new JProgressBar();
	private final JButton options = new JButton("任意 MOD…");
	private final JButton cancel = new JButton("キャンセル");
	private final JButton ok = new JButton("続ける");
	private final PackCover homeCover = new PackCover(new LauncherBranding(LauncherIdentity.NAME, null, null), launchArea);
	private volatile SettingsWindow settings;
	private final List<Runnable> waitingForSettings = new ArrayList<>();

	public InstallWindow(GUIHandler handler) {
		super(LauncherIdentity.NAME);
		setIconImage(LauncherIdentity.ICON);
		setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
		addWindowListener(new WindowAdapter() {
			@Override public void windowClosing(WindowEvent event) {
				handler.requestWindowClose();
			}
		});

		JPanel content = new JPanel(new BorderLayout());
		setContentPane(content);
		JPanel header = new JPanel(new BorderLayout(12, 0));
		header.setBackground(PINE);
		header.setBorder(new EmptyBorder(6, 36, 6, 36));
		JLabel packName = new JLabel(LauncherIdentity.NAME);
		packName.setForeground(CREAM);
		packName.setFont(packName.getFont().deriveFont(Font.BOLD, 16f));
		if (LauncherIdentity.ICON != null) {
			packName.setIcon(new ImageIcon(LauncherIdentity.ICON.getScaledInstance(32, 32, Image.SCALE_SMOOTH)));
			packName.setIconTextGap(10);
		}
		header.add(packName, BorderLayout.CENTER);
		JButton openSettings = new JButton("設定");
		styleButton(openSettings, CREAM, PINE, new Color(0x2B403C));
		openSettings.addActionListener(e -> showSettings(this, ignored -> {}));
		header.add(openSettings, BorderLayout.EAST);
		content.add(header, BorderLayout.NORTH);

		styleButton(launch, PINE, CREAM, Color.WHITE);
		launch.setFont(launch.getFont().deriveFont(Font.BOLD, 16f));
		launch.setIconTextGap(12);
		launch.addActionListener(e -> handler.requestLaunch());
		launchArea.setOpaque(false);
		JPanel launchCard = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 6));
		launchCard.setOpaque(false);
		launch.setPreferredSize(new Dimension(160, 47));
		launchCard.add(launch);
		launchArea.add(launchCard, "launch");
		launchArea.add(createProgressCard(), "progress");
		getRootPane().setDefaultButton(launch);
		content.add(homeCover, BorderLayout.CENTER);

		JPanel footer = new JPanel(new BorderLayout());
		footer.setBackground(PINE);
		footer.setBorder(new EmptyBorder(0, 36, 0, 26));
		status.putClientProperty("html.disable", true);
		status.setForeground(new Color(0xD8DFD4));
		footer.add(status, BorderLayout.CENTER);

		JPanel actions = new JPanel(new FlowLayout(FlowLayout.TRAILING, 5, 6));
		actions.setOpaque(false);
		actions.add(ok);
		actions.add(cancel);
		actions.setPreferredSize(new Dimension(210, Math.max(39, cancel.getPreferredSize().height + 12)));
		footer.add(actions, BorderLayout.EAST);
		content.add(footer, BorderLayout.SOUTH);
		options.addActionListener(e -> {
			if (settings != null && !settings.saveAndClose()) return;
			options.setText("読込中…");
			options.setEnabled(false);
			handler.setOptionsButtonPressed(true);
		});
		cancel.addActionListener(e -> {
			cancel.setEnabled(false);
			handler.setCancelButtonPressed(true);
		});
		ok.addActionListener(e -> handler.setOkButtonPressed(true));
		ok.setVisible(false);
		cancel.setVisible(false);
		pack();
		Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
		setSize(Math.min(getWidth(), screen.width), Math.min(getHeight(), screen.height));
		setMinimumSize(new Dimension(Math.min(450, screen.width), Math.min(420, screen.height)));
		setLocationRelativeTo(null);
	}

	private JPanel createProgressCard() {
		JPanel panel = new JPanel(new BorderLayout(0, 7));
		panel.setBackground(new Color(0x142925));
		panel.setBorder(new EmptyBorder(9, 12, 9, 12));
		JPanel row = new JPanel(new BorderLayout(10, 0));
		row.setOpaque(false);
		stage.setForeground(CREAM);
		stage.setFont(stage.getFont().deriveFont(Font.BOLD, 14f));
		count.setForeground(new Color(0xD8DFD4));
		row.add(stage, BorderLayout.CENTER);
		row.add(count, BorderLayout.EAST);
		panel.add(row, BorderLayout.NORTH);
		progress.setUI(new BasicProgressBarUI() {
			@Override protected int getCellLength() { return 1; }
			@Override protected int getCellSpacing() { return 0; }
		});
		progress.setBackground(new Color(0x40544F));
		progress.setForeground(new Color(0xA9C9A6));
		progress.setBorderPainted(false);
		progress.setPreferredSize(new Dimension(160, 5));
		progress.getAccessibleContext().setAccessibleName("起動準備の進捗");
		stage.setLabelFor(progress);
		panel.add(progress, BorderLayout.SOUTH);
		return panel;
	}

	private static void styleButton(JButton button, Color foreground, Color background, Color hover) {
		button.setUI(new BasicButtonUI());
		button.setOpaque(true);
		button.setRolloverEnabled(true);
		button.setForeground(foreground);
		button.setBackground(background);
		button.setBorder(new EmptyBorder(8, 13, 8, 13));
		button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		button.addChangeListener(e -> button.setBackground(button.getModel().isRollover()
				|| button.getModel().isPressed() ? hover : background));
		button.addFocusListener(new FocusAdapter() {
			private void focus(boolean focused) {
				button.setBorder(focused
						? BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(foreground, 2),
								new EmptyBorder(6, 11, 6, 11))
						: new EmptyBorder(8, 13, 8, 13));
			}
			@Override public void focusGained(FocusEvent e) { focus(true); }
			@Override public void focusLost(FocusEvent e) { focus(false); }
		});
	}

	private static final class PlayIcon implements Icon {
		@Override public int getIconWidth() { return 9; }
		@Override public int getIconHeight() { return 12; }
		@Override public void paintIcon(Component component, Graphics g, int x, int y) {
			g.setColor(component.getForeground());
			g.fillPolygon(new int[] {x, x + 9, x}, new int[] {y, y + 6, y + 12}, 3);
		}
	}

	public void startInstalling() {
		launchCards.show(launchArea, "progress");
		getRootPane().setDefaultButton(null);
		stage.setText("パックを確認中");
		status.setText("");
		progress.setIndeterminate(true);
		cancel.setVisible(true);
	}

	public void setBranding(LauncherBranding value) {
		homeCover.setBranding(value);
	}

	public boolean getSettingsOpen() {
		return settings != null;
	}

	public void showSettings(Window owner, Consumer<Path> folderChanged) {
		try {
			settings = new SettingsWindow(owner, owner == this ? options : null, folderChanged);
			settings.setVisible(true);
		} finally {
			settings = null;
			for (Runnable action : waitingForSettings) EventQueue.invokeLater(action);
			waitingForSettings.clear();
		}
	}

	/** Called by the installer thread before displaying another dialog or handing off to the game. */
	public <T> T afterSettingsClose(Callable<T> action) throws InterruptedException, ExecutionException {
		FutureTask<T> task = new FutureTask<>(action);
		EventQueue.invokeLater(() -> {
			if (settings != null) waitingForSettings.add(task);
			else task.run();
		});
		return task.get();
	}

	public void displayProgress(InstallProgress update) {
		stage.setText(update.getLabel());
		progress.setIndeterminate(!update.getHasProgress());
		if (update.getHasProgress()) {
			progress.setMaximum(update.getProgressTotal());
			progress.setValue(update.getProgress());
		}
		count.setText(update.getHasProgress() ? update.getProgress() + " / " + update.getProgressTotal() : "");
		status.setText(update.getMessage());
		status.setToolTipText(update.getMessage());
	}

	void showManualDownloadProgress(int remaining, int total) {
		stage.setText("手動ダウンロード待ち");
		count.setText("残り " + remaining + " 件");
		progress.setIndeterminate(false);
		progress.setMaximum(total);
		progress.setValue(total - remaining);
		status.setText("");
		status.setToolTipText(null);
	}

	public void showLaunchReady() {
		stage.setText("起動準備が完了しました");
		count.setText("");
		progress.setIndeterminate(false);
		progress.setValue(progress.getMaximum());
		status.setText("");
		status.setToolTipText(null);
		cancel.setVisible(false);
	}

	public void disableOptionsButton(boolean hasOptions) {
		options.setText(hasOptions ? "任意 MOD…" : "任意 MOD なし");
		options.setEnabled(false);
	}

	public void showOk(boolean hideCancel) {
		cancel.setVisible(!hideCancel);
		ok.setText("続ける");
		ok.setVisible(true);
		getRootPane().setDefaultButton(ok);
	}

	public void hideOk() {
		ok.setVisible(false);
		cancel.setVisible(true);
		getRootPane().setDefaultButton(null);
	}

	public void timeoutOk(long remaining) {
		ok.setText("続ける (" + remaining + ")");
	}
}
