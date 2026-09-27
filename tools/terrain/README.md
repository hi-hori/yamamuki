# 内蔵陰影地形

山頂パックとは独立した `app/src/main/assets/offline/terrain.zip` を両版で使用する。
約1万枚の512px WebP画像（品質80）、合成元の河川GeoJSON、出典・ライセンス・生成情報を収録し、約240MB。
国土地理院の標高タイルから陰影を作り、OSM河川を合成する。
海は濃い青、川は明るい青。北海道の大部分の河川は未収録。
全縮尺で512px版を使用し、256px版・等高線ポリゴンは同梱しない。

## 取得・再ビルド

リポジトリ直下でGit LFSの実データを取得する。

```powershell
git lfs pull
python -m venv build/terrain-venv
build/terrain-venv/Scripts/python -m pip install -r tools/terrain/requirements.txt
build/terrain-venv/Scripts/python tools/terrain/build_shaded.py --output build/rebuilt-terrain/terrain.zip
# 取得済みの標高素材を使う（通信なし）
build/terrain-venv/Scripts/python tools/terrain/build_shaded.py --offline --output build/rebuilt-terrain/terrain.zip
```

既定の入力は同梱の `terrain.zip`。そのタイル一覧、河川データ、取得履歴を引き継ぎ、
標高素材を `build/offline-data/sources` に取得・キャッシュして全画像を再生成する。
`--base`、`--cache`、`--output`、`--quality` で変更可能。素材と取得情報JSONを一緒に保存する。
素材不足・破損時に `--offline` は失敗する。既存パックの画像は読み込み表示専用で、再描画の入力には使わない。
収録範囲・河川データの更新は入力パックの更新が必要。このツールはその範囲内の陰影再生成を担当する。

検証後、出力ZIPと同名のSHA-256ファイルを `app/src/main/assets/offline/` にコピーする。
通常のAndroidビルドはハッシュ検証だけを行い、素材取得・画像生成は行わない。
iOSは `python ios/prepare-terrain.py` を実行してから `xcodegen generate` で同梱する。
CIではGit LFS取得、ハッシュ検証、iOSリソース準備、ImageIOでの画像検証を実行する。

```powershell
build/terrain-venv/Scripts/python -m unittest discover -s tools/terrain -p "test_*.py"
python -m unittest discover -s ios -p "test_prepare*.py"
```

## 表示と出典

z10〜12を表示範囲で切り替える（16km以下でz12、50km以下でz11）。
高解像度への切り替えは操作終了を待たず開始し、それ以外の連続操作は短時間まとめる。
地形の読み込みエラーが山頂表示を妨げないよう別々に読み込む。
Androidは小さな画像メッシュ、iOSはバックグラウンドで合成した画像を使い、
手動移動・回転も山頂と同じ観測地点の投影を使用する。

画質は元標高の精度に制限され、補間によって測定精度が上がるわけではない。
出典は同梱NOTICEに記載。設定画面から国土地理院の利用規約を参照でき、
河川GeoJSONとODbL本文は保存・再利用できる。山頂CSVの保存機能も維持する。

今回のパックは既存の陰影画像をバイト単位で引き継いだもの。
manifestに元パックのハッシュと生成時の来歴を保持し、新しいツールで生成し直した場合は
新しいビルダー・素材のハッシュを記録する。
