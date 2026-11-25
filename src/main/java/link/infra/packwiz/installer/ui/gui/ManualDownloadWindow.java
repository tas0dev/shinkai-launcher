package link.infra.packwiz.installer.ui.gui;

import link.infra.packwiz.installer.ui.data.ManualDownload;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.GridLayout;
import java.awt.LayoutManager;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingWorker;
import javax.swing.SwingConstants;
import javax.swing.plaf.basic.BasicButtonUI;

final class ManualDownloadWindow extends JDialog {
	private static final Color SURFACE = new Color(0x192C26);
	private static final Color TEXT = new Color(0xF4F1E6);
	private static final Color MUTED = new Color(0xBDCFC1);
	private static final Color LINE = new Color(0x3A5146);
	private static final Color CONFIRMED = new Color(0xBFDFAB);
	private static final String RESUME_NOTE = "すべて確認できると、自動で起動準備を再開します。";

	@FunctionalInterface
	interface Scanner {
		Set<ManualDownload> scan(Path folder) throws IOException;
	}

	private final InstallWindow parentWindow;
	private final List<ManualDownload> downloads;
	private final List<JPanel> rowActions = new ArrayList<>();
	private final JButton openAll = action("未取得の配布ページをすべて開く", true);
	private final JButton openFolder = action("フォルダーを開く", false);
	private final JLabel remaining = label("");
	private final JLabel status = label(RESUME_NOTE);
	private final JTextField directory = new JTextField(22);
	private final Semaphore folderChanged = new Semaphore(0);
	private volatile Set<ManualDownload> verified = Collections.emptySet();
	private volatile Path watchedFolder;
	private volatile boolean reviewErrors;

	ManualDownloadWindow(InstallWindow parentWindow, List<ManualDownload> downloads, Path initialFolder, Path systemFolder) {
		super(parentWindow, "不足している MOD", ModalityType.APPLICATION_MODAL);
		this.parentWindow = parentWindow;
		this.downloads = new ArrayList<>(downloads);
		watchedFolder = initialFolder;
		setUndecorated(true);
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);
		getRootPane().setBorder(BorderFactory.createLineBorder(new Color(0x657F70)));
		getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
				JComponent.WHEN_IN_FOCUSED_WINDOW);
		JPanel body = panel(new BorderLayout(0, 14));
		body.setBorder(BorderFactory.createEmptyBorder(21, 24, 18, 24));
		setContentPane(body);

		JPanel instructions = panel(new BorderLayout(0, 14));
		JPanel overview = panel(new BorderLayout(12, 0));
		JPanel titles = panel(new BorderLayout(0, 3));
		JLabel title = label("不足しているMOD");
		title.setFont(title.getFont().deriveFont(Font.BOLD, 21f));
		titles.add(title, BorderLayout.NORTH);
		remaining.setForeground(MUTED);
		titles.add(remaining, BorderLayout.CENTER);
		overview.add(titles, BorderLayout.CENTER);
		JPanel bulkAction = panel(new FlowLayout(FlowLayout.TRAILING, 0, 0));
		bulkAction.add(openAll);
		overview.add(bulkAction, BorderLayout.EAST);
		instructions.add(overview, BorderLayout.NORTH);
		JPanel destination = panel(new BorderLayout(8, 0));
		JLabel folderLabel = label("保存先");
		folderLabel.setForeground(MUTED);
		folderLabel.setLabelFor(directory);
		directory.setEditable(false);
		directory.setBorder(null);
		directory.setBackground(SURFACE);
		directory.setForeground(TEXT);
		directory.setCaretColor(TEXT);
		showFolder(initialFolder);
		JPanel folder = panel(new BorderLayout());
		folder.add(folderLabel, BorderLayout.NORTH);
		folder.add(directory, BorderLayout.CENTER);
		destination.add(folder, BorderLayout.CENTER);
		openFolder.addActionListener(e -> openDownloadFolder());
		JButton settings = action("保存先を変更", false);
		settings.addActionListener(e -> parentWindow.showSettings(this, customFolder -> {
			Path selectedFolder = customFolder == null ? systemFolder : customFolder;
			showFolder(selectedFolder);
			status.setText("ダウンロードフォルダーを確認しています…");
			status.setToolTipText(null);
			watchedFolder = selectedFolder;
			folderChanged.release();
		}));
		JPanel folderActions = panel(new FlowLayout(FlowLayout.TRAILING, 6, 0));
		folderActions.add(openFolder);
		folderActions.add(settings);
		destination.add(folderActions, BorderLayout.EAST);
		instructions.add(destination, BorderLayout.SOUTH);
		body.add(instructions, BorderLayout.NORTH);

		JPanel rows = panel(new GridLayout(0, 1));
		for (ManualDownload download : this.downloads) {
			JPanel row = panel(new BorderLayout(10, 0));
			row.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, LINE),
					BorderFactory.createEmptyBorder(9, 0, 9, 0)));
			JPanel names = panel(new GridLayout(2, 1));
			JLabel name = label(download.getName());
			name.setFont(name.getFont().deriveFont(Font.BOLD));
			name.setToolTipText(download.getName());
			names.add(name);
			JLabel filename = label(download.getFilename());
			filename.setForeground(MUTED);
			filename.setToolTipText(download.getFilename());
			names.add(filename);
			// Keep the browser action visible even when a mod has a very long filename.
			names.setPreferredSize(new Dimension(180, names.getPreferredSize().height));
			row.add(names, BorderLayout.CENTER);
			JButton page = action("配布ページを開く", false);
			page.setToolTipText(download.getUrl());
			page.getAccessibleContext().setAccessibleDescription(download.getName());
			page.addActionListener(e -> BrowserPages.open(this, Collections.singleton(download.getUrl()), () -> {}));
			JPanel action = panel(new CardLayout());
			action.add(page, "download");
			JLabel verifiedLabel = label("確認済み");
			verifiedLabel.setForeground(CONFIRMED);
			verifiedLabel.setHorizontalAlignment(SwingConstants.CENTER);
			action.add(verifiedLabel, "verified");
			rowActions.add(action);
			row.add(action, BorderLayout.EAST);
			rows.add(row);
		}
		JPanel list = panel(new BorderLayout());
		list.add(rows, BorderLayout.NORTH);
		JScrollPane scroll = new JScrollPane(list);
		int visibleRows = Math.max(1, Math.min(7, downloads.size()));
		int rowHeight = rows.getComponentCount() == 0 ? 54 : rows.getComponent(0).getPreferredSize().height;
		scroll.setPreferredSize(new Dimension(490, rowHeight * visibleRows + 1));
		scroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, LINE));
		scroll.getVerticalScrollBar().setUnitIncrement(rowHeight);
		body.add(scroll, BorderLayout.CENTER);
		JPanel footer = panel(new BorderLayout(0, 8));
		status.setForeground(MUTED);
		footer.add(status, BorderLayout.NORTH);
		body.add(footer, BorderLayout.SOUTH);

		openAll.addActionListener(e -> {
			List<String> urls = new ArrayList<>();
			Set<ManualDownload> installed = verified;
			for (ManualDownload download : this.downloads) {
				if (!installed.contains(download)) urls.add(download.getUrl());
			}
			if (urls.isEmpty()) return;
			openAll.setEnabled(false);
			BrowserPages.open(this, urls, () -> openAll.setEnabled(!verified.containsAll(this.downloads)));
		});
		updateRemaining(downloads.size());
		JPanel actions = panel(new BorderLayout());
		JButton errors = action("エラーの詳細…", false);
		errors.addActionListener(e -> {
			reviewErrors = true;
			dispose();
		});
		actions.add(errors, BorderLayout.WEST);
		JButton cancel = action("起動をキャンセル", false);
		cancel.addActionListener(e -> dispose());
		actions.add(cancel, BorderLayout.EAST);
		footer.add(actions, BorderLayout.SOUTH);
		pack();
		setLocationRelativeTo(parentWindow);
	}

	void showOverHome() {
		if (watchedFolder == null) return;
		Component previous = parentWindow.getGlassPane();
		boolean wasVisible = previous.isVisible();
		JComponent shade = new JComponent() {
			@Override protected void paintComponent(Graphics g) {
				g.setColor(new Color(9, 23, 19, 156));
				g.fillRect(0, 0, getWidth(), getHeight());
			}
		};
		parentWindow.setGlassPane(shade);
		shade.setVisible(true);
		try {
			setVisible(true);
		} finally {
			parentWindow.setGlassPane(previous);
			previous.setVisible(wasVisible);
		}
	}

	private static JPanel panel(LayoutManager layout) {
		JPanel panel = new JPanel(layout);
		panel.setBackground(SURFACE);
		return panel;
	}

	private static JLabel label(String text) {
		JLabel label = new JLabel();
		label.putClientProperty("html.disable", true);
		label.setText(text);
		label.setForeground(TEXT);
		return label;
	}

	private static JButton action(String text, boolean primary) {
		JButton button = new JButton(text);
		Color normal = primary ? new Color(0xE5EADB) : new Color(0x263D34);
		Color hovered = primary ? new Color(0xF7F5E7) : new Color(0x36564A);
		Color line = primary ? normal : new Color(0x577066);
		button.setUI(new BasicButtonUI());
		button.setOpaque(true);
		button.setRolloverEnabled(true);
		button.setForeground(primary ? new Color(0x182319) : TEXT);
		button.setBackground(normal);
		button.setMargin(new java.awt.Insets(0, 0, 0, 0));
		button.addChangeListener(e -> {
			button.setBackground(!button.isEnabled() ? SURFACE : button.getModel().isRollover() ? hovered : normal);
			button.setBorder(BorderFactory.createCompoundBorder(
					BorderFactory.createLineBorder(button.hasFocus() ? new Color(0xFFE0AB) : line),
					BorderFactory.createEmptyBorder(7, 10, 7, 10)));
		});
		button.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(line),
				BorderFactory.createEmptyBorder(7, 10, 7, 10)));
		if (primary) button.setFont(button.getFont().deriveFont(Font.BOLD));
		return button;
	}

	private void showFolder(Path folder) {
		directory.setText(folder.toString());
		directory.setCaretPosition(directory.getText().length());
		directory.setToolTipText(folder.toString());
	}

	private void openDownloadFolder() {
		Path folder = watchedFolder;
		if (folder == null) return;
		openFolder.setEnabled(false);
		new SwingWorker<Void, Void>() {
			@Override protected Void doInBackground() throws IOException {
				if (!Files.isDirectory(folder)) throw new IOException("保存先フォルダーが見つかりません。");
				if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
					throw new IOException("この環境ではフォルダーを直接開けません。");
				}
				Desktop.getDesktop().open(folder.toFile());
				return null;
			}

			@Override protected void done() {
				openFolder.setEnabled(true);
				try {
					get();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				} catch (ExecutionException e) {
					if (isShowing()) JOptionPane.showMessageDialog(ManualDownloadWindow.this,
							new Object[] {e.getCause().getLocalizedMessage(), folder.toString()},
							"保存先を開けませんでした", JOptionPane.ERROR_MESSAGE);
				}
			}
		}.execute();
	}

	@Override
	public void dispose() {
		watchedFolder = null;
		folderChanged.release();
		super.dispose();
	}

	// File verification and import run on the installer thread so cancel never leaves an import running.
	boolean awaitDownloads(Scanner scanner) throws InterruptedException, ExecutionException {
		try {
			Path folder;
			while ((folder = watchedFolder) != null) {
				final Path scannedFolder = folder;
				try {
					Set<ManualDownload> installed = scanner.scan(folder);
					verified = installed;
					if (installed.containsAll(downloads)) return watchedFolder != null || reviewErrors;
					EventQueue.invokeLater(() -> showVerified(scannedFolder, installed));
				} catch (IOException e) {
					EventQueue.invokeLater(() -> {
						if (scannedFolder.equals(watchedFolder)) {
							status.setText("フォルダーを読み取れません。設定から検索先を確認してください。");
							status.setToolTipText(e.getLocalizedMessage());
						}
					});
				}
				folderChanged.tryAcquire(1, TimeUnit.SECONDS);
			}
			return reviewErrors;
		} finally {
			parentWindow.afterSettingsClose(() -> {
				dispose();
				return null;
			});
		}
	}

	private void showVerified(Path folder, Set<ManualDownload> installed) {
		if (!folder.equals(watchedFolder)) return;
		int verifiedCount = 0;
		for (int i = 0; i < downloads.size(); i++) {
			if (installed.contains(downloads.get(i))) {
				verifiedCount++;
				JPanel action = rowActions.get(i);
				((CardLayout) action.getLayout()).show(action, "verified");
			}
		}
		updateRemaining(downloads.size() - verifiedCount);
		status.setText(RESUME_NOTE);
		status.setToolTipText(null);
	}

	private void updateRemaining(int count) {
		remaining.setText("残り " + count + " 件 ・ " + downloads.size() + " 件中 " + (downloads.size() - count) + " 件を確認済み");
		parentWindow.showManualDownloadProgress(count, downloads.size());
	}
}
