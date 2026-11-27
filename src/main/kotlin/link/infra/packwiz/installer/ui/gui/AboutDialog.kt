package link.infra.packwiz.installer.ui.gui

import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dialog
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Image
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.KeyStroke
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.WindowConstants
import javax.swing.border.EmptyBorder

object AboutDialog {
    @JvmStatic
    fun show(parent: Component) {
        val dialog = launcherDialog(parent, "このランチャーについて")
        val close = launcherDialogButton("閉じる") { dialog.dispose() }
        dialog.contentPane = aboutContent(close) { OpenSourceLicense.show(dialog) }
        dialog.isResizable = false
        dialog.open(close)
    }

    internal fun aboutContent(close: JButton, openLicenses: () -> Unit): JPanel {
        val version = javaClass.`package`.implementationVersion ?: "開発版"
        val identity = JPanel(BorderLayout(16, 0)).apply {
            LauncherIdentity.ICON?.let { image ->
                add(JLabel(ImageIcon(image.getScaledInstance(56, 56, Image.SCALE_REPLICATE))).apply {
                    verticalAlignment = SwingConstants.CENTER
                    horizontalAlignment = SwingConstants.CENTER
                }, BorderLayout.WEST)
            }
            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(JLabel(LauncherIdentity.NAME).apply {
                    font = font.deriveFont(Font.BOLD, 21f)
                })
                add(Box.createVerticalStrut(4))
                add(JLabel("バージョン $version"))
            }, BorderLayout.CENTER)
        }

        val credits = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(JLabel("Copyright (c) 2021 comp500"))
            add(Box.createVerticalStrut(3))
            add(JLabel("Copyright (c) 2026 tas0dev"))
        }
        val actions = JPanel(FlowLayout(FlowLayout.TRAILING, 8, 0)).apply {
            add(launcherDialogButton("オープンソースライセンス", action = openLicenses))
            add(close)
        }

        return JPanel(BorderLayout(0, 14)).apply {
            border = EmptyBorder(16, 16, 16, 16)
            add(identity, BorderLayout.NORTH)
            add(credits, BorderLayout.CENTER)
            add(JPanel(BorderLayout(0, 12)).apply {
                add(JSeparator(), BorderLayout.NORTH)
                add(actions, BorderLayout.EAST)
            }, BorderLayout.SOUTH)
        }
    }
}

internal fun launcherDialog(parent: Component, title: String): JDialog {
    val owner = parent as? Window ?: SwingUtilities.getWindowAncestor(parent)
    return JDialog(owner, title, Dialog.ModalityType.DOCUMENT_MODAL).apply {
        defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        setIconImage(LauncherIdentity.ICON)
    }
}

internal fun launcherDialogButton(label: String, action: () -> Unit) = JButton(label).apply {
    addActionListener { action() }
}

internal fun JDialog.open(close: JButton) {
    rootPane.defaultButton = close
    rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "closeDialog")
    rootPane.actionMap.put("closeDialog", object : AbstractAction() {
        override fun actionPerformed(event: ActionEvent) = dispose()
    })
    pack()
    val screen = (owner?.graphicsConfiguration ?: graphicsConfiguration)
    val insets = Toolkit.getDefaultToolkit().getScreenInsets(screen)
    val available = Dimension(screen.bounds.width - insets.left - insets.right,
        screen.bounds.height - insets.top - insets.bottom)
    size = Dimension(minOf(width, available.width), minOf(height, available.height))
    setLocationRelativeTo(owner)
    isVisible = true
}
