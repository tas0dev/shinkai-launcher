package link.infra.packwiz.installer.ui.gui;

import link.infra.packwiz.installer.ui.data.InstallProgress;
import link.infra.packwiz.installer.ui.data.LauncherBranding;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
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
	private final JButton launch = new JButton("起動", PixelStyle.icon("play"));
	private final CardLayout launchCards = new CardLayout();
	private final JPanel launchArea = new JPanel(launchCards);
	private final JLabel stage = new JLabel();
	private final JLabel count = new JLabel();
	private final JProgressBar progress = new JProgressBar();
	private final JButton options = new JButton("任意 MOD…");
	private final JButton cancel = new JButton("キャンセル");
	private final JButton ok = new JButton("続ける");
	private final PackCover homeCover;
	private volatile SettingsWindow settings;
	private final List<Runnable> waitingForSettings = new ArrayList<>();

	public InstallWindow(GUIHandler handler) {
		super(LauncherIdentity.NAME);
		setUndecorated(true);
		setResizable(false);
		setIconImage(LauncherIdentity.ICON);
		setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
		addWindowListener(new WindowAdapter() {
			@Override public void windowOpened(WindowEvent event) {
				launch.requestFocusInWindow();
			}

			@Override public void windowClosing(WindowEvent event) {
				handler.requestWindowClose();
			}
		});

		JPanel header = new JPanel(new BorderLayout(12, 0));
		header.setOpaque(false);
		header.setBorder(new EmptyBorder(15, 22, 10, 17));
		JLabel appIcon = new JLabel();
		appIcon.getAccessibleContext().setAccessibleName(LauncherIdentity.NAME);
		if (LauncherIdentity.ICON != null) {
			appIcon.setIcon(new ImageIcon(LauncherIdentity.ICON.getScaledInstance(32, 32, Image.SCALE_REPLICATE)));
		}
		header.add(appIcon, BorderLayout.WEST);
		JButton openSettings = iconButton("sliders", "設定", 44);
		openSettings.addActionListener(e -> showSettings(this, ignored -> {}));
		JButton minimize = iconButton("minus", "最小化", 36);
		minimize.addActionListener(e -> setState(Frame.ICONIFIED));
		JButton close = iconButton("close", "閉じる", 36);
		close.addActionListener(e -> dispatchEvent(new WindowEvent(this, WindowEvent.WINDOW_CLOSING)));
		JPanel windowControls = new JPanel(new GridLayout(1, 0, 6, 0));
		windowControls.setOpaque(false);
		windowControls.add(minimize);
		windowControls.add(close);
		header.add(windowControls, BorderLayout.EAST);
		enableHeaderDrag(header, appIcon);

		PixelStyle.styleButton(launch, true, false);
		launch.setFont(PixelStyle.font(launch.getFont(), 23f));
		launch.setIconTextGap(10);
		launch.addActionListener(e -> handler.requestLaunch());
		launchArea.setOpaque(false);
		JPanel launchCard = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
		launchCard.setOpaque(false);
		launch.setPreferredSize(new Dimension(175, 54));
		launchCard.add(launch);
		launchArea.add(launchCard, "launch");
		launchArea.add(createProgressCard(), "progress");
		getRootPane().setDefaultButton(launch);

		JPanel actions = new JPanel(new FlowLayout(FlowLayout.TRAILING, 7, 0));
		actions.setOpaque(false);
		PixelStyle.styleButton(ok, true, false);
		PixelStyle.styleButton(cancel, false, false);
		actions.add(ok);
		actions.add(cancel);
		actions.add(openSettings);
		homeCover = new PackCover(new LauncherBranding(LauncherIdentity.NAME, null, null), launchArea, header, actions);
		setContentPane(homeCover);
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
		setLocationRelativeTo(null);
	}

	private void enableHeaderDrag(Component... surfaces) {
		MouseAdapter drag = new MouseAdapter() {
			private Point offset;

			@Override public void mousePressed(MouseEvent event) {
				if (!SwingUtilities.isLeftMouseButton(event)) return;
				offset = SwingUtilities.convertPoint(event.getComponent(), event.getPoint(), InstallWindow.this);
			}

			@Override public void mouseDragged(MouseEvent event) {
				if (offset != null) setLocation(event.getXOnScreen() - offset.x, event.getYOnScreen() - offset.y);
			}

			@Override public void mouseReleased(MouseEvent event) {
				offset = null;
			}
		};
		for (Component surface : surfaces) {
			surface.addMouseListener(drag);
			surface.addMouseMotionListener(drag);
		}
	}

	private static JButton iconButton(String icon, String label, int size) {
		JButton button = new JButton(PixelStyle.icon(icon));
		PixelStyle.styleButton(button, false, "close".equals(icon));
		button.setBorder(new EmptyBorder(0, 0, 0, 0));
		button.setPreferredSize(new Dimension(size, size));
		button.setToolTipText(label);
		button.getAccessibleContext().setAccessibleName(label);
		return button;
	}

	private JPanel createProgressCard() {
		JPanel panel = new JPanel(new BorderLayout(0, 10));
		panel.setOpaque(false);
		JPanel row = new JPanel(new BorderLayout(10, 0));
		row.setOpaque(false);
		stage.setForeground(PixelStyle.CREAM);
		stage.setFont(PixelStyle.font(stage.getFont(), 17f));
		count.setForeground(new Color(0xDFEBCC));
		count.setFont(PixelStyle.font(count.getFont(), 17f));
		row.add(stage, BorderLayout.CENTER);
		row.add(count, BorderLayout.EAST);
		panel.add(row, BorderLayout.NORTH);
		PixelStyle.styleProgress(progress);
		progress.setPreferredSize(new Dimension(160, 23));
		progress.getAccessibleContext().setAccessibleName("起動準備の進捗");
		stage.setLabelFor(progress);
		panel.add(progress, BorderLayout.SOUTH);
		return panel;
	}

	public void startInstalling() {
		launchCards.show(launchArea, "progress");
		getRootPane().setDefaultButton(null);
		stage.setText("パックを確認中");
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
		stage.setToolTipText(update.getMessage());
	}

	void showManualDownloadProgress(int remaining, int total) {
		stage.setText("手動ダウンロード待ち");
		count.setText("残り " + remaining + " 件");
		progress.setIndeterminate(false);
		progress.setMaximum(total);
		progress.setValue(total - remaining);
		stage.setToolTipText(null);
	}

	public void showLaunchReady() {
		stage.setText("起動準備が完了しました");
		count.setText("");
		progress.setIndeterminate(false);
		progress.setValue(progress.getMaximum());
		stage.setToolTipText(null);
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
