# yamamuki (iOS 版)

Android 版とほぼ同じ機能の iOS アプリ（Swift + SwiftUI、iOS 17 以上）。

構成、開発環境、ビルドと実行の手順、Android 版との違いは、リポジトリ直下の [README](../README.md) にまとめている。

## Windows からテストとビルドを実行する

GitHub Actions の macOS ランナーを使う。Windows に Xcode をインストールする必要はない。

1. iOS の変更を `main`、または `codex/`・`claude/` で始まるブランチへ push する（プルリクエストでも実行）。
2. [Actions の iOS ワークフロー](https://github.com/shohei0205/yamamuki/actions/workflows/ios.yml) を開く。
3. 対象の実行でテストとビルドの結果を確認する。ログは Artifacts の `ios-build-log` からダウンロードできる。

`workflow_dispatch` を含む設定がデフォルトブランチへ入った後は、同じ画面の **Run workflow** からブランチを選んで手動実行もできる。ログの保存期間は14日。

Actions では YamamukiCore の単体テストと、署名なしのシミュレーター向けビルドを実行する。SwiftUI 画面を含むアプリ全体のコンパイルを確認し、ログだけを保存する。アプリのバイナリは配布しない。シミュレーター上での起動・画面操作のテストは行わない。

## Mac でビルド・実行する

1. Xcode 16 以上をインストールして一度起動し、初回セットアップと iOS プラットフォームの導入を完了する。
2. Xcode の Settings > Locations > Command Line Tools でインストールした Xcode を選ぶ。
3. Homebrew が利用できるターミナルで、リポジトリのルートから実行する。

```bash
brew install xcodegen
bash ios/build-simulator.sh
open ios/Yamamuki.xcodeproj
```

スクリプトは Mac 上で環境確認、core の単体テスト、プロジェクト生成、シミュレーター用ビルドをまとめて実行する。Actions も同じスクリプトを使う。出力は `ios/build/Build/Products/Debug-iphonesimulator/Yamamuki.app`。

Xcode で実行先に iPhone シミュレーターを選び、Run（⌘R）で起動する。

iPhone 実機へ入れる場合は Xcode の Signing & Capabilities で Team を設定し、接続した iPhone を選んで実行する。方位センサーと GPS の精度は実機で確認する。
