# Shinai Launcher

Shinai Launcher は Prism Launcher / MultiMC の起動前コマンドで動作する packwiz インストーラーです。起動すると、packwiz が通常どおりチェックと自動ダウンロードを開始します。

### 手動ダウンロードが必要なとき

1. 自動取得できなかった MOD があると、不足しているファイルが一覧に表示されます。各行の **配布ページを開く**、または一覧の一括ボタンで、必要なページを既定のブラウザに開けます。
2. ブラウザで指定バージョンの JAR をダウンロードします。通常は OS のダウンロードフォルダーを自動で監視します。別の場所に保存する場合は **設定 → フォルダーを指定 → 参照…** で選択します。検索先は次回も使用します。
3. packwiz がハッシュを照合し、一致した JAR をインスタンスにコピーします。`mod (1).jar` のように名前が変わっても検出します。必要なファイルがそろうと一覧が閉じ、同じ packwiz の処理が続きます。

起動時にファイルがすべてそろっていれば、不足 MOD の画面は省略されます。任意 MOD はインストーラーの **設定** から選択できます。

### 実行方法

配布 JAR は `build/dist/packwiz-installer.jar` です。Prism Launcher / MultiMC では、この JAR をインスタンスの Minecraft フォルダーに置き、起動前コマンドを設定します。以下の URL は自分のパックの URL に置き換えてください。[packwiz の起動前コマンド設定手順](https://packwiz.infra.link/tutorials/installing/packwiz-installer/)

```text
"$INST_JAVA" -jar packwiz-installer.jar https://example.org/modpack/pack.toml
```

既存の [packwiz-installer-bootstrap](https://github.com/packwiz/packwiz-installer-bootstrap) を使用する場合も、`--bootstrap-no-update --bootstrap-main-jar packwiz-installer.jar` でこの JAR を指定できます。単独で起動してパックのインストールを確認することもできます。

```sh
java -jar packwiz-installer.jar --pack-folder "path/to/.minecraft" "path/to/pack.toml"
```

引数なしで直接起動すると、JAR と同じフォルダーの `pack.toml` を読み込み、そのフォルダーにインストールします。`--no-gui` では検索先を一度チェックし、未取得のファイルがあれば URL を出力して終了します。ゲーム本体は呼び出し元のランチャーが起動します。

### パック名と背景画像

配布するパックの `pack.toml` と同じ場所に `packwiz-launcher.json` を配置します。不足 MOD の画面に表示するタイトル、説明、背景画像を指定できます。

```json
{
  "title": "Shinai Launcher",
  "subtitle": "森と渓谷を探索する、Forge の冒険パック。",
  "background": "assets/evergreen-valley.jpg"
}
```

各項目は省略できます。タイトルの既定値は `pack.toml` のパック名です。背景はパック内の PNG / JPEG の相対パスで指定します（最大 8 MiB、838 万 8608 画素）。ウィンドウ名は `--title` で上書きできます。

[HTML のデザイン案](docs/launcher-preview.html)では、サンプルのパックで JAR 検出の流れを試せます。
