package link.infra.packwiz.installer.ui.gui;

import java.awt.Component;
import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingWorker;

final class BrowserPages {
	@FunctionalInterface
	interface Browser {
		void browse(URI uri) throws IOException, InterruptedException;
	}

	private BrowserPages() {}

	static void open(Component owner, Collection<String> urls, Runnable finished) {
		new SwingWorker<List<String>, Void>() {
			@Override
			protected List<String> doInBackground() throws Exception {
				return openEach(urls, BrowserPages::browse);
			}

			@Override
			protected void done() {
				try {
					List<String> failures = get();
					if (owner.isShowing() && !failures.isEmpty()) {
						JTextArea addresses = new JTextArea(String.join("\n\n", failures),
								Math.min(9, failures.size() * 3), 44);
						addresses.setEditable(false);
						addresses.setLineWrap(true);
						addresses.setCaretPosition(0);
						JOptionPane.showMessageDialog(owner, new Object[]{
								"以下のページを開けませんでした。アドレスをコピーするか、一覧からもう一度開いてください。",
								new JScrollPane(addresses)
						}, "配布ページを開く", JOptionPane.ERROR_MESSAGE);
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				} catch (ExecutionException e) {
					if (owner.isShowing()) JOptionPane.showMessageDialog(owner,
							"ブラウザを開けませんでした。\n" + e.getCause().getLocalizedMessage(),
							"配布ページを開く", JOptionPane.ERROR_MESSAGE);
				} finally {
					finished.run();
				}
			}
		}.execute();
	}

	// Launch each distinct web address once per request. A broken link must not block the remaining mods.
	static List<String> openEach(Collection<String> urls, Browser browser) throws InterruptedException {
		List<String> failures = new ArrayList<>();
		Set<URI> seen = new HashSet<>();
		for (String url : new LinkedHashSet<>(urls)) {
			try {
				URI uri = new URI(url);
				if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
						|| uri.getHost() == null) {
					throw new IOException("配布ページのアドレスが無効です。");
				}
				if (seen.add(uri)) browser.browse(uri);
			} catch (IOException | URISyntaxException | RuntimeException e) {
				failures.add(url + "\n" + e.getLocalizedMessage());
			}
		}
		return failures;
	}

	private static void browse(URI uri) throws IOException, InterruptedException {
		String os = System.getProperty("os.name", "");
		boolean xdg = os.equals("Linux") || os.endsWith("BSD") || os.equals("SunOS");
		try {
			if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
				Desktop.getDesktop().browse(uri);
				return;
			}
		} catch (IOException | UnsupportedOperationException e) {
			if (!xdg) throw e;
		}
		if (!xdg) throw new IOException("既定のブラウザが見つかりません。");
		Process process = new ProcessBuilder("xdg-open", uri.toASCIIString()).inheritIO().start();
		// Some desktop integrations keep xdg-open alive with the browser after a successful launch.
		if (process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() != 0) {
			throw new IOException("xdg-open で配布ページを開けませんでした。");
		}
	}
}
