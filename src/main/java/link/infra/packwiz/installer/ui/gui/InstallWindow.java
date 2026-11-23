package link.infra.packwiz.installer.ui.gui;

import link.infra.packwiz.installer.ui.data.InstallProgress;
import link.infra.packwiz.installer.ui.data.LauncherBranding;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.function.Consumer;

/** The normal packwiz progress window. A cover is loaded only when manual downloads need attention. */
public final class InstallWindow extends JFrame {
	private final JLabel packName = new JLabel(LauncherIdentity.NAME);
	private final JLabel status = new JLabel("パックを確認しています…");
	private final JProgressBar progress = new JProgressBar();
	private final JButton options = new JButton("任意 MOD…");
	private final JButton cancel = new JButton("キャンセル");
	private final JButton ok = new JButton("続ける");
	private LauncherBranding branding = new LauncherBranding(LauncherIdentity.NAME, null, null);
	private PackCover recoveryCover;
	private volatile SettingsWindow settings;
	private final List<Runnable> waitingForSettings = new ArrayList<>();

	public InstallWindow(GUIHandler handler) {
		super(LauncherIdentity.NAME);
		setIconImage(LauncherIdentity.ICON);
		setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
		addWindowListener(new WindowAdapter() {
			@Override public void windowClosing(WindowEvent event) {
				cancel.doClick(0);
			}
		});

		JPanel content = new JPanel(new BorderLayout(0, 12));
		content.setBorder(new EmptyBorder(14, 16, 12, 16));
		setContentPane(content);
		JPanel header = new JPanel(new BorderLayout(12, 0));
		packName.putClientProperty("html.disable", true);
		packName.setFont(packName.getFont().deriveFont(Font.BOLD, 18f));
		if (LauncherIdentity.ICON != null) {
			packName.setIcon(new ImageIcon(LauncherIdentity.ICON.getScaledInstance(32, 32, Image.SCALE_SMOOTH)));
			packName.setIconTextGap(10);
		}
		header.add(packName, BorderLayout.CENTER);
		JButton openSettings = new JButton("設定");
		openSettings.addActionListener(e -> showSettings(this, ignored -> {}));
		header.add(openSettings, BorderLayout.EAST);
		content.add(header, BorderLayout.NORTH);

		JPanel indicator = new JPanel(new BorderLayout(0, 5));
		status.putClientProperty("html.disable", true);
		status.setLabelFor(progress);
		indicator.add(status, BorderLayout.NORTH);
		progress.setIndeterminate(true);
		progress.getAccessibleContext().setAccessibleName("インストールの進捗");
		indicator.add(progress, BorderLayout.CENTER);
		content.add(indicator, BorderLayout.CENTER);

		JPanel actions = new JPanel(new FlowLayout(FlowLayout.TRAILING, 5, 0));
		actions.add(ok);
		actions.add(cancel);
		content.add(actions, BorderLayout.SOUTH);
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
		pack();
		setSize(Math.max(getWidth(), 510), getHeight());
		setMinimumSize(new Dimension(Math.max(getMinimumSize().width, 365), getHeight()));
		setLocationRelativeTo(null);
	}

	public void setBranding(LauncherBranding value) {
		branding = value;
		packName.setText(value.getTitle());
		packName.setToolTipText(value.getTitle());
		if (recoveryCover != null) recoveryCover.setBranding(value);
	}

	public JPanel createRecoveryCover() {
		recoveryCover = new PackCover(branding);
		return recoveryCover;
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
		progress.setIndeterminate(!update.getHasProgress());
		if (update.getHasProgress()) {
			progress.setMaximum(update.getProgressTotal());
			progress.setValue(update.getProgress());
		}
		status.setText(update.getMessage());
		status.setToolTipText(update.getMessage());
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
