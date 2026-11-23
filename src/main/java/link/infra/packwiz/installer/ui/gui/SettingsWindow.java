package link.infra.packwiz.installer.ui.gui;

import link.infra.packwiz.installer.ui.DownloadDirectories;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Window;
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
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;

final class SettingsWindow extends LauncherDialog {
	private final Path initialFolder = DownloadDirectories.INSTANCE.customDirectory();
	private final Consumer<Path> folderChanged;
	private final JRadioButton systemFolder = new JRadioButton("システムのダウンロードフォルダー");
	private final JRadioButton customFolder = new JRadioButton("フォルダーを指定");
	private final JTextField pathField = new JTextField(27);
	private final JButton browse = new JButton("参照…");

	SettingsWindow(Window owner, JButton optionalMods, Consumer<Path> folderChanged) {
		super(owner, "設定");
		this.folderChanged = folderChanged;
		body.add(heading("設定"), BorderLayout.NORTH);

		JPanel fields = panel(new BorderLayout(0, 12));
		JPanel location = panel(new BorderLayout(0, 6));
		JLabel folderTitle = heading("手動取得した JAR の検索先");
		folderTitle.setLabelFor(pathField);
		location.add(folderTitle, BorderLayout.NORTH);
		JPanel choices = panel(null);
		choices.setLayout(new BoxLayout(choices, BoxLayout.Y_AXIS));
		ButtonGroup group = new ButtonGroup();
		group.add(systemFolder);
		group.add(customFolder);
		systemFolder.setOpaque(false);
		customFolder.setOpaque(false);
		choices.add(systemFolder);
		choices.add(Box.createVerticalStrut(2));
		choices.add(customFolder);
		location.add(choices, BorderLayout.CENTER);

		JPanel path = panel(new BorderLayout(6, 0));
		path.setBorder(BorderFactory.createEmptyBorder(0, 22, 0, 0));
		pathField.getAccessibleContext().setAccessibleName("検索するフォルダーのパス");
		path.add(pathField, BorderLayout.CENTER);
		path.add(browse, BorderLayout.EAST);
		location.add(path, BorderLayout.SOUTH);
		fields.add(location, BorderLayout.NORTH);
		if (optionalMods != null) {
			JPanel optional = panel(new BorderLayout(0, 6));
			optional.setBorder(BorderFactory.createCompoundBorder(
					BorderFactory.createMatteBorder(1, 0, 0, 0, LINE),
					BorderFactory.createEmptyBorder(10, 0, 0, 0)));
			optional.add(heading("任意 MOD"), BorderLayout.NORTH);
			JPanel row = panel(new FlowLayout(FlowLayout.LEADING, 0, 0));
			row.add(optionalMods);
			optional.add(row, BorderLayout.CENTER);
			fields.add(optional, BorderLayout.CENTER);
		}
		body.add(fields, BorderLayout.CENTER);

		JButton cancel = new JButton("キャンセル");
		cancel.addActionListener(e -> dispose());
		actions.add(cancel);
		JButton save = primary("保存");
		save.addActionListener(e -> saveAndClose());
		actions.add(save);
		getRootPane().setDefaultButton(save);

		pathField.setText(initialFolder == null ? "" : initialFolder.toString());
		group.setSelected(initialFolder == null ? systemFolder.getModel() : customFolder.getModel(), true);
		systemFolder.addActionListener(e -> updatePathControls());
		customFolder.addActionListener(e -> updatePathControls());
		browse.addActionListener(e -> chooseFolder());
		updatePathControls();
		pack();
		setMinimumSize(getSize());
		setLocationRelativeTo(owner);
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
