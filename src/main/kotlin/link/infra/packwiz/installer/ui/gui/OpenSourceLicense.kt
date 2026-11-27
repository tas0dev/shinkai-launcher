package link.infra.packwiz.installer.ui.gui

import link.infra.packwiz.installer.util.Log
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.io.IOException
import javax.swing.BorderFactory
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.ListSelectionModel
import javax.swing.border.EmptyBorder

object OpenSourceLicense {
    private data class LicenseDocument(val title: String, val path: String)

    private val markdownHeading = Regex("(?m)^#{1,6}[ \\t]*")
    private val markdownLink = Regex("\\[([^\\]\\r\\n]+)]\\(([^)\\r\\n]+)\\)")
    private val markdownBold = Regex("\\*\\*([^*\\r\\n]+)\\*\\*")
    private val markdownCode = Regex("`([^`\\r\\n]+)`")
    private val reportQuote = Regex("(?m)^> ")

    private val documents = listOf(
        LicenseDocument("同梱ライセンス", "/META-INF/LICENSES.md"),
        LicenseDocument("依存ライブラリ一覧", "/META-INF/DEPENDENCY-LICENSES.md"),
        LicenseDocument("DotGothic16", "/link/infra/packwiz/installer/ui/DotGothic16-OFL.txt"),
        LicenseDocument("Noto Sans JP", "/link/infra/packwiz/installer/ui/NotoSansJP-OFL.txt"),
        LicenseDocument("Pixelarticons", "/link/infra/packwiz/installer/ui/pixelarticons/LICENSE.txt"),
    )

    fun show(parent: Component) {
        val dialog = launcherDialog(parent, "オープンソースライセンス")
        val close = launcherDialogButton("閉じる") { dialog.dispose() }
        dialog.contentPane = licenseContent(close)
        dialog.open(close)
    }

    internal fun licenseContent(close: JButton): JPanel {
        val text = JTextArea().apply {
            name = "licenseText"
            accessibleContext.accessibleName = "ライセンス本文"
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            border = EmptyBorder(12, 14, 12, 14)
        }
        val reader = JScrollPane(text).apply {
            verticalScrollBar.unitIncrement = 16
            preferredSize = Dimension(490, 338)
        }

        val sources = JList(documents.map { it.title }.toTypedArray()).apply {
            name = "licenseSources"
            accessibleContext.accessibleName = "ライセンス一覧"
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            fixedCellHeight = 34
            cellRenderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>, value: Any?, index: Int, selected: Boolean, focused: Boolean,
                ): Component {
                    super.getListCellRendererComponent(list, value, index, selected, focused)
                    border = BorderFactory.createCompoundBorder(border, EmptyBorder(0, 10, 0, 8))
                    return this
                }
            }
            addListSelectionListener { event ->
                if (!event.valueIsAdjusting) {
                    documents.getOrNull(selectedIndex)?.let { document ->
                        text.text = readDocument(document)
                        text.caretPosition = 0
                        reader.verticalScrollBar.value = 0
                    }
                }
            }
            selectedIndex = 0
        }

        val navigation = JScrollPane(sources).apply {
            preferredSize = Dimension(168, 338)
        }
        val body = JPanel(BorderLayout(12, 0)).apply {
            add(navigation, BorderLayout.WEST)
            add(reader, BorderLayout.CENTER)
        }
        return JPanel(BorderLayout(0, 12)).apply {
            border = EmptyBorder(16, 16, 14, 16)
            add(JLabel("オープンソースライセンス").apply {
                font = font.deriveFont(Font.BOLD, 18f)
            }, BorderLayout.NORTH)
            add(body, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.TRAILING, 0, 0)).apply {
                add(close)
            }, BorderLayout.SOUTH)
        }
    }

    private fun readDocument(document: LicenseDocument): String {
        val content = try {
            javaClass.getResourceAsStream(document.path)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        } catch (e: IOException) {
            Log.warn("Could not read ${document.path}", e)
            null
        }
        if (content.isNullOrBlank()) return "「${document.title}」を読み込めませんでした。"
        if (!document.path.endsWith(".md")) return content
        return content.replace(markdownHeading, "")
            .replace(markdownLink) { match ->
                val (label, target) = match.destructured
                if (label == target) target else "$label ($target)"
            }
            .replace(markdownBold, "$1")
            .replace(markdownCode, "$1")
            .replace(reportQuote, "  ")
    }
}
