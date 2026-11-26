package link.infra.packwiz.installer.ui.gui

import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dialog
import java.awt.Dimension
import java.awt.Font
import java.awt.Window
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTextArea
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.border.EmptyBorder

class OpenSourceLicense {
    fun setVisible(visible: Boolean, parent: Component) {
        if (!visible) return

        val owner = parent as? Window ?: SwingUtilities.getWindowAncestor(parent)
        val dialog = JDialog(owner, "Open Source Licenses", Dialog.ModalityType.APPLICATION_MODAL)
        dialog.defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE

        val tabs = JTabbedPane()
        tabs.addTab("依存ライブラリ", document("/META-INF/DEPENDENCY-LICENSES.md", wrap = false))
        tabs.addTab("ライセンス本文", document("/META-INF/LICENSES.md"))
        tabs.addTab("DotGothic16", document("/link/infra/packwiz/installer/ui/DotGothic16-OFL.txt"))
        tabs.addTab("Noto Sans JP", document("/link/infra/packwiz/installer/ui/NotoSansJP-OFL.txt"))
        tabs.addTab("Pixelarticons", document("/link/infra/packwiz/installer/ui/pixelarticons/LICENSE.txt"))

        val close = JButton("閉じる").apply { addActionListener { dialog.dispose() } }
        dialog.contentPane = JPanel(BorderLayout(0, 8)).apply {
            border = EmptyBorder(12, 12, 12, 12)
            add(tabs, BorderLayout.CENTER)
            add(JPanel().apply { add(close) }, BorderLayout.SOUTH)
        }
        dialog.rootPane.defaultButton = close
        dialog.rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close")
        dialog.rootPane.actionMap.put("close", object : AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent) = dialog.dispose()
        })
        dialog.pack()
        dialog.setLocationRelativeTo(owner)
        dialog.isVisible = true
    }

    private fun document(path: String, wrap: Boolean = true): JScrollPane {
        val content = javaClass.getResourceAsStream(path)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        val text = JTextArea(content ?: "ライセンス情報を読み込めませんでした。").apply {
            isEditable = false
            lineWrap = wrap
            wrapStyleWord = wrap
            border = EmptyBorder(8, 8, 8, 8)
            if (!wrap) font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
            caretPosition = 0
        }
        return JScrollPane(text).apply { preferredSize = Dimension(610, 340) }
    }
}
