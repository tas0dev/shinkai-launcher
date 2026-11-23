package link.infra.packwiz.installer.ui.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.LayoutManager;
import java.awt.Window;
import java.awt.event.KeyEvent;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;

/** The common paper background and action strip used by the launcher's dialogs. */
abstract class LauncherDialog extends JDialog {
	static final Color PAPER = new Color(0xFAFBF7);
	static final Color INK = new Color(0x25332F);
	static final Color MUTED = new Color(0x54625B);
	static final Color GREEN = new Color(0x365D49);
	static final Color LINE = new Color(0xC4CEC5);
	final JPanel body = panel(new BorderLayout(0, 12));
	final JPanel actions = new JPanel(new FlowLayout(FlowLayout.TRAILING, 8, 0));

	LauncherDialog(Window owner, String title) {
		super(owner, title, ModalityType.APPLICATION_MODAL);
		body.setBorder(BorderFactory.createEmptyBorder(18, 20, 16, 20));
		add(body, BorderLayout.CENTER);
		actions.setBackground(new Color(0xEDF1EA));
		actions.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(1, 0, 0, 0, LINE),
				BorderFactory.createEmptyBorder(10, 8, 10, 8)));
		add(actions, BorderLayout.SOUTH);
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);
		getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
				JComponent.WHEN_IN_FOCUSED_WINDOW);
	}

	static JPanel panel(LayoutManager layout) {
		JPanel result = new JPanel(layout);
		result.setBackground(PAPER);
		return result;
	}

	static JLabel label(String text) {
		JLabel label = new JLabel();
		label.putClientProperty("html.disable", true);
		label.setForeground(INK);
		label.setText(text);
		return label;
	}

	static JLabel heading(String text) {
		JLabel label = label(text);
		label.setFont(label.getFont().deriveFont(Font.BOLD, label.getFont().getSize2D() + 2));
		return label;
	}

	static JButton primary(String text) {
		JButton button = new JButton(text);
		button.setForeground(Color.WHITE);
		button.setBackground(GREEN);
		button.setContentAreaFilled(false);
		button.setOpaque(true);
		button.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createLineBorder(GREEN), BorderFactory.createEmptyBorder(6, 12, 6, 12)));
		return button;
	}
}
