# 山頂データの再ビルド

Python 3.12（標準ライブラリのみ）を使い、リポジトリ直下で実行する。

```powershell
python tools/bundled_peaks/build_peaks.py
# 取得済み素材で再現する（通信しない）
python tools/bundled_peaks/build_peaks.py --offline
# 新しい素材を取得して更新する場合は、別のキャッシュを指定する
python tools/bundled_peaks/build_peaks.py --cache build/peaks-inputs-new
```

既定キャッシュは `build/peaks-inputs`。以前の地形ビルダーの素材も、
`--cache build/offline-data/sources --offline` で利用できる。
再現に必要な `osm/*.json`（応答と取得情報の両方）を保存しておく。
キャッシュ済み素材はSHA-256を検証し、不足・破損時はオフラインビルドを失敗させる。
同じPython環境、素材、ビルダー、NOTICEとライセンス本文で同じZIPを再生成できる。

出力は `app/src/main/assets/offline/peaks.zip` と `peaks.sha256`。
ZIPには山頂CSV（gzip）、manifest、出典とODbL本文だけを収録する。
manifestにOSM日時、クエリ、取得元・取得日時・ハッシュ、ビルダーのハッシュを記録する。
既存パックの山頂CSVを引き継ぎ、地形・河川は含めない。
通常のAndroidビルドではSHA-256を検証して同梱し、データ取得は行わない。

山頂データは © OpenStreetMap contributors、ODbL 1.0。
抽出・整形後もODbLで再配布し、アプリ設定から全件CSVとライセンスを保存できる。
アプリ本体と生成ツールはリポジトリのMITライセンスに従う。
日本の名前付き `natural=peak/volcano` ノードを収録し、未登録・無名・国外の山は含めない。

iOSへの反映は `python ios/prepare-peaks.py` を実行してからXcodeGenでプロジェクトを生成する。Androidと同じパックが入力になる。
