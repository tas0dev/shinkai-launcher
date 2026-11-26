package link.infra.packwiz.installer.ui.gui

import java.awt.Component;
import java.awt.Frame
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.WindowConstants

class AboutDialog{
    private var visible: Boolean = false;

    fun setVisible(visible: Boolean, parent: Component) {
        this.visible = visible;
        if (visible) {
            AboutDialogObject.showAbout(parent);
        }
    }

    object AboutDialogObject {
        @JvmStatic
        fun showAbout(parent: Component) {
            val owner = parent as? Frame ?: javax.swing.SwingUtilities.getWindowAncestor(parent) as? Frame
            val dialog = AboutDialogView(owner ?: throw IllegalArgumentException("Parent component must be a Frame or have a Frame ancestor"))
            dialog.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
            dialog.pack()
            dialog.setLocationRelativeTo(owner)
            dialog.isVisible = true
        }

    }
}

class AboutDialogView(owner: Frame) : JDialog(owner, "About", true) {
    val version = AboutDialog::class.java.`package`.implementationVersion ?: "開発版"

    init {
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        add(JLabel("Shinkai Launcher"))
        add(JLabel("Version: $version\n"))

        add(JLabel("Copyright (c) 2021 comp500\n" +
                         "Copyright (c) 2026 tas0dev"))
        add(JButton("Open Source Licenses").apply {
            addActionListener {
                val licenseDialog = OpenSourceLicense()
//                licenseDialog.setVisible(true, this@AboutDialogView)
            }
        })
    }
}