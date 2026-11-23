package link.infra.packwiz.installer.ui.data

import link.infra.packwiz.installer.metadata.hash.Hash
import java.nio.file.Path

class ManualDownload internal constructor(
	val name: String,
	val filename: String,
	val url: String,
	val destination: Path,
	internal val hash: Hash<*>
)
