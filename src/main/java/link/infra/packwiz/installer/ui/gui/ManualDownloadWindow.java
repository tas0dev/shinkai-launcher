package link.infra.packwiz.installer.ui.gui;

import link.infra.packwiz.installer.ui.data.ManualDownload;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.GridLayout;
import java.io.IOException;
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
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;

final class ManualDownloadWindow extends LauncherDialog {
	@FunctionalInterface
	interface Scanner {
		Set<ManualDownload> scan(Path folder) throws IOException;
	}

	private final InstallWindow parentWindow;
	private final List<ManualDownload> downloads;
	private final List<JButton> pageButtons = new ArrayList<>();
	private final JButton openAll = primary("未取得の配布ページをすべて開く");
	private final JLabel status = label("ダウンロードフォルダーを確認しています…");
	private final JTextField directory = new JTextField(22);
	private final Semaphore folderChanged = new Semaphore(0);
	private volatile Set<ManualDownload> verified = Collections.emptySet();
	private volatile Path watchedFolder;
	private volatile boolean reviewErrors;

	ManualDownloadWindow(InstallWindow parentWindow, List<ManualDownload> downloads, Path initialFolder, Path systemFolder) {
		super(parentWindow, "不足している MOD");
		this.parentWindow = parentWindow;
		this.downloads = new ArrayList<>(downloads);
		watchedFolder = initialFolder;
		add(parentWindow.createRecoveryCover(), BorderLayout.NORTH);

		JPanel instructions = panel(new BorderLayout(0, 6));
		instructions.add(heading("手動ダウンロードが必要です"), BorderLayout.NORTH);
		instructions.add(label("各配布ページから指定のファイルを保存してください。すべて確認できるとインストールを再開します。"),
				BorderLayout.CENTER);
		JPanel destination = panel(new BorderLayout(8, 0));
		JLabel folderLabel = label("検索先");
		folderLabel.setLabelFor(directory);
		destination.add(folderLabel, BorderLayout.WEST);
		directory.setEditable(false);
		directory.setText(initialFolder.toString());
		destination.add(directory, BorderLayout.CENTER);
		JButton settings = new JButton("設定…");
		settings.addActionListener(e -> parentWindow.showSettings(this, customFolder -> {
			Path folder = customFolder == null ? systemFolder : customFolder;
			directory.setText(folder.toString());
			status.setText("ダウンロードフォルダーを確認しています…");
			status.setToolTipText(null);
			watchedFolder = folder;
			folderChanged.release();
		}));
		destination.add(settings, BorderLayout.EAST);
		instructions.add(destination, BorderLayout.SOUTH);
		body.add(instructions, BorderLayout.NORTH);

		JPanel rows = panel(new GridLayout(0, 1));
		for (ManualDownload download : this.downloads) {
			JPanel row = panel(new BorderLayout(10, 0));
			row.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, LINE),
					BorderFactory.createEmptyBorder(8, 10, 8, 10)));
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
			JButton page = new JButton("配布ページを開く");
			page.setToolTipText(download.getUrl());
			page.getAccessibleContext().setAccessibleDescription(download.getName());
			page.addActionListener(e -> BrowserPages.open(this, Collections.singleton(download.getUrl()), () -> {}));
			pageButtons.add(page);
			row.add(page, BorderLayout.EAST);
			rows.add(row);
		}
		JPanel list = panel(new BorderLayout());
		list.add(rows, BorderLayout.NORTH);
		JScrollPane scroll = new JScrollPane(list);
		int visibleRows = Math.max(2, Math.min(6, downloads.size()));
		int rowHeight = rows.getComponentCount() == 0 ? 54 : rows.getComponent(0).getPreferredSize().height;
		scroll.setPreferredSize(new Dimension(590, rowHeight * visibleRows + 2));
		scroll.setBorder(BorderFactory.createLineBorder(LINE));
		scroll.getVerticalScrollBar().setUnitIncrement(rowHeight);
		body.add(scroll, BorderLayout.CENTER);
		body.add(status, BorderLayout.SOUTH);

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
		actions.add(openAll);
		JButton errors = new JButton("エラーの詳細…");
		errors.addActionListener(e -> {
			reviewErrors = true;
			dispose();
		});
		actions.add(errors);
		JButton cancel = new JButton("起動をキャンセル");
		cancel.addActionListener(e -> dispose());
		actions.add(cancel);
		pack();
		setLocationRelativeTo(parentWindow);
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
		int verified = 0;
		for (int i = 0; i < downloads.size(); i++) {
			if (installed.contains(downloads.get(i))) {
				verified++;
				pageButtons.get(i).setText("確認済み");
				pageButtons.get(i).setEnabled(false);
			}
		}
		status.setText(verified + " / " + downloads.size() + " 件を確認済み。ダウンロードを待っています…");
		status.setToolTipText(null);
	}
}
