package link.infra.packwiz.installer.ui.gui

import java.awt.Component

internal fun openWebPage(parent: Component, url: String) =
	BrowserPages.open(parent, listOf(url)) {}
