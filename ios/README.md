# yamamuki (iOS 版)

Android 版とほぼ同じ機能の iOS アプリ（Swift + SwiftUI、iOS 17 以上）。

構成、開発環境、ビルドと実行の手順、Android 版との違いは、リポジトリ直下の [README](../README.md) にまとめている。

```bash
cd ios
python3 prepare-terrain.py        # Git LFS取得後、陰影地形を準備
python3 prepare-peaks.py          # Androidと共通のパックから内蔵リソースを準備
(cd YamamukiCore && swift test)   # core の単体テスト
xcodegen generate                 # Yamamuki.xcodeproj を生成
open Yamamuki.xcodeproj
```

山頂データは初回から通信なしで表示する。日本の名前付き山頂約1万4千件を収録し、
手動移動後も内蔵版を参照する。陰影地形・河川も別パックで内蔵する。
設定画面の「山頂データとライセンスを保存・共有」から、Androidと同一のZIPを「ファイルに保存」できる。

`prepare-peaks.py` はパックのSHA-256、件数、座標・IDを検証し、
`Generated/Peaks` にJSONと出典・ライセンス、配布用ZIPを生成する。Python標準ライブラリのみを使う。
山頂パックの更新後は再実行してからビルドする。生成物はGit管理外。
CIでもデータ準備、Swiftのテスト、シミュレータービルドの順に実行する。

```bash
python3 -m unittest discover -s . -p 'test_prepare_peaks.py'
```

確認項目: 機内モードで初回起動、GPS位置の山名表示、手動移動・回転・現在地リセット、
標高フィルター、設定からZIPを保存してCSV・ライセンスが取り出せること。

地形パックの更新後は `python3 prepare-terrain.py` を再実行する。
`Generated/Terrain` はGit管理外。地形画像と河川データの保存用ZIPを含む。
macOSでは `swift verify-terrain.swift Generated/Terrain` で全画像のImageIO復号を確認できる。
追加確認項目: 地形の表示切り替え、16km・50km境界の解像度切り替え、
手動移動・回転時の山頂と地形の位置合わせ、河川データとライセンスの保存。
