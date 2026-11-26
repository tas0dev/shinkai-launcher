package link.infra.packwiz.installer.ui.gui;

import link.infra.packwiz.installer.ui.DownloadDirectories;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.border.Border;

final class SettingsWindow extends JDialog {
	private final Path initialFolder = DownloadDirectories.INSTANCE.customDirectory();
	private final Consumer<Path> folderChanged;
	private final JRadioButton systemFolder = new JRadioButton("システムのダウンロードフォルダー");
	private final JRadioButton customFolder = new JRadioButton("フォルダーを指定");
	private final JTextField pathField = new JTextField(27);
	private final JButton browse = new JButton("参照…");

	SettingsWindow(Window owner, JButton optionalMods, Consumer<Path> folderChanged) {
		super(owner, "設定", ModalityType.APPLICATION_MODAL);
		this.folderChanged = folderChanged;
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);
		setResizable(false);
		getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
				JComponent.WHEN_IN_FOCUSED_WINDOW);

		JPanel content = new JPanel(new BorderLayout(0, 12));
		content.setBorder(BorderFactory.createEmptyBorder(12, 12, 10, 12));
		setContentPane(content);
		JPanel fields = new JPanel(new BorderLayout(0, 8));
		JPanel location = new JPanel(new BorderLayout(0, 5));
		location.setBorder(groupBorder("手動取得した JAR の検索先"));
		JPanel choices = new JPanel();
		choices.setLayout(new BoxLayout(choices, BoxLayout.Y_AXIS));
		ButtonGroup group = new ButtonGroup();
		group.add(systemFolder);
		group.add(customFolder);
		choices.add(systemFolder);
		choices.add(Box.createVerticalStrut(2));
		choices.add(customFolder);
		location.add(choices, BorderLayout.CENTER);

		JPanel path = new JPanel(new BorderLayout(6, 0));
		path.setBorder(BorderFactory.createEmptyBorder(0, 20, 0, 0));
		pathField.getAccessibleContext().setAccessibleName("検索するフォルダーのパス");
		path.add(pathField, BorderLayout.CENTER);
		path.add(browse, BorderLayout.EAST);
		location.add(path, BorderLayout.SOUTH);
		fields.add(location, BorderLayout.NORTH);
		if (optionalMods != null) {
			JPanel optional = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
			optional.setBorder(groupBorder("任意 MOD"));
			optional.add(optionalMods);
			fields.add(optional, BorderLayout.CENTER);
		}
		content.add(fields, BorderLayout.CENTER);

		JPanel actions = new JPanel(new FlowLayout(FlowLayout.TRAILING, 6, 0));
		JButton save = new JButton("OK");
		save.addActionListener(e -> saveAndClose());
		actions.add(save);
		JButton cancel = new JButton("キャンセル");
		cancel.addActionListener(e -> dispose());
		actions.add(cancel);
		int width = Math.max(save.getPreferredSize().width, cancel.getPreferredSize().width);
		Dimension size = new Dimension(width, cancel.getPreferredSize().height);
		save.setPreferredSize(size);
		cancel.setPreferredSize(size);
		content.add(actions, BorderLayout.SOUTH);
		getRootPane().setDefaultButton(save);

		pathField.setText(initialFolder == null ? "" : initialFolder.toString());
		group.setSelected(initialFolder == null ? systemFolder.getModel() : customFolder.getModel(), true);
		systemFolder.addActionListener(e -> updatePathControls());
		customFolder.addActionListener(e -> updatePathControls());
		browse.addActionListener(e -> chooseFolder());
		updatePathControls();
		pack();
		setLocationRelativeTo(owner);
	}

	private static Border groupBorder(String title) {
		return BorderFactory.createCompoundBorder(BorderFactory.createTitledBorder(title),
				BorderFactory.createEmptyBorder(4, 8, 8, 8));
	}

	private void updatePathControls() {
		pathField.setEnabled(customFolder.isSelected());
		browse.setEnabled(customFolder.isSelected());
	}

	private void chooseFolder() {
		JFileChooser chooser = new JFileChooser(pathField.getText().trim());
		chooser.setDialogTitle("検索するフォルダーを選択");
		chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		chooser.setAcceptAllFileFilterUsed(false);
		if (chooser.showDialog(this, "選択") == JFileChooser.APPROVE_OPTION) {
			pathField.setText(chooser.getSelectedFile().getAbsolutePath());
		}
	}

	boolean saveAndClose() {
		Path selected = null;
		if (customFolder.isSelected()) {
			try {
				selected = Paths.get(pathField.getText().trim()).normalize();
				if (!selected.isAbsolute()) throw new InvalidPathException(selected.toString(), "相対パス");
			} catch (InvalidPathException e) {
				JOptionPane.showMessageDialog(this, "フォルダーの絶対パスを入力するか、「参照…」から選択してください。",
						"検索先の設定", JOptionPane.WARNING_MESSAGE);
				pathField.requestFocusInWindow();
				return false;
			}
		}
		if (!Objects.equals(selected, initialFolder)) {
			try {
				DownloadDirectories.INSTANCE.setPreferredDirectory(selected);
			} catch (Exception e) {
				JOptionPane.showMessageDialog(this, "検索先を保存できませんでした。\n" + e.getLocalizedMessage(),
						"検索先の設定", JOptionPane.ERROR_MESSAGE);
				return false;
			}
			folderChanged.accept(selected);
		}
		dispose();
		return true;
	}
}
