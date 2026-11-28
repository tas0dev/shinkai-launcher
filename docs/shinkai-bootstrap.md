# Shinkai Bootstrap

`shinkai-bootstrap.jar` は Java 17 以降で動作します。Prism のインスタンスの `minecraft` フォルダに置き、起動前のコマンドを以下にします。

```sh
"$INST_JAVA" -jar shinkai-bootstrap.jar -s client https://mc.yunagi.uk/pack.toml
```

初回オンライン起動で GitHub Releases からランチャーを取得します。オフラインで初回起動する場合は、同じリリースの `launcher.jar` と `shinkai-launcher.update` を bootstrap と同じフォルダに置きます。その後の更新が失敗した場合は検証済みのランチャーが起動します。ランチャーを自動更新してから、通常の Shinkai Launcher が MOD パックを処理します。

## リリースの作り方

Java 17 以降で Gradle を実行します。`shinkaiSigningPublicKey` は MOD パック用の公開鍵、`shinkaiLauncherSigningPrivateKey` はランチャー配布専用の PKCS#8 DER 秘密鍵です。秘密鍵はリポジトリ外で管理します。公開鍵は bootstrap に同梱されています。

```powershell
.\gradlew.bat testShinkaiBootstrap signShinkaiLauncher `
  "-PshinkaiSigningPublicKey=C:\path\to\pack-ed25519.spki.der" `
  "-PshinkaiLauncherSigningPrivateKey=C:\path\to\launcher-ed25519.pk8.der" `
  "-PshinkaiReleaseSequence=1"
```

`build/shinkai-release/launcher.jar` と `build/shinkai-release/shinkai-launcher.update` を、[tas0dev/shinkai-launcher](https://github.com/tas0dev/shinkai-launcher/releases) の公開リリース `shinkai-v1` に添付し、最新版に設定します。初回配布用に `shinkai-bootstrap.jar` も添付できます。以後は `shinkai-v2`、`shinkai-v3` のように番号を増やし、同じ番号で内容を差し替えないでください。bootstrap はこのリポジトリの `releases/latest` を確認します。

キャッシュにあるランチャーの署名と SHA-256 を起動ごとに検証します。インストール済みの番号より古いリリースを拒否します。bootstrap 自体や同梱公開鍵を差し替える場合は、利用者側での bootstrap の入れ替えが必要です。
