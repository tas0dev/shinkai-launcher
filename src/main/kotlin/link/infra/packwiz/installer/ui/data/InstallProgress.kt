package link.infra.packwiz.installer.ui.data

data class InstallProgress(
		val message: String,
		val hasProgress: Boolean = false,
		val progress: Int = 0,
		val progressTotal: Int = 0,
		val label: String = "起動準備中"
) {
	constructor(message: String, progress: Int, progressTotal: Int, label: String = "ファイルを処理中") :
		this(message, true, progress, progressTotal, label)

	constructor(message: String, label: String) : this(message, false, label = label)
}
