package link.infra.packwiz.installer.ui.gui

import java.awt.Component;
import javax.swing.JOptionPane

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
            JOptionPane.showMessageDialog(
                parent,
                "About",
            )
        }

    }
}