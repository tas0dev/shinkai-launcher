package link.infra.packwiz.installer.ui.gui

import java.awt.Component;
import javax.swing.JOptionPane

class AboutDialog {
    object AboutDialogObject {
        @JvmStatic
        fun show(parent: Component) {
            JOptionPane.showMessageDialog(
                parent,
                "About",
            )
        }

    }
}