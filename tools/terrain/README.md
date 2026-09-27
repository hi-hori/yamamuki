# 内蔵陰影地形

山頂パックとは独立した `app/src/main/assets/offline/terrain.zip` を両版で使用する。
約1万枚の512px WebP画像（品質80）、水面・河川ベクタータイルと元のGeoJSON、出典・ライセンス・生成情報を収録し、約270MB。
国土地理院の標高タイルから河川なしの陰影を作り、OSM河川は別レイヤーで描画する。
海は濃い青、川は明るい青。河川は従来の0.7倍（512pxタイル上で2.8px）に相当する線幅で描画し、地形と独立して表示を切り替えられる。北海道の大部分の河川は未収録。
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
標高素材を `build/offline-data/sources` に取得・キャッシュして全画像と河川ベクタータイルを再生成する。
`--base`、`--cache`、`--output`、`--quality` で変更可能。素材と取得情報JSONを一緒に保存する。
素材不足・破損時に `--offline` は失敗する。既存パックの画像は読み込み表示専用で、再描画の入力には使わない。
収録範囲・河川データの更新は入力パックの更新が必要。このツールはその範囲内の陰影・河川タイル再生成を担当する。

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

河川は `rivers/{z}/{x}/{y}.riv` に分割する。RIV1はリトルエンディアンの
マジック4バイト、uint32の線数、各線のuint32頂点数とuint16 x/yの列。
座標範囲はタイル内0〜4096。z10/11/12でそれぞれ512px換算2px/1px/0.3pxの
許容差で簡略化してから境界で切り、
境界の交点を一致させる。ZIP内ではDEFLATE圧縮する。
元GeoJSON自体が簡略化済みのため、ベクター表示でも元の測量精度は上がらない。
表示範囲のタイルを読み、観測地点基準の線をバックグラウンドで作成して再利用する。
広域（50km超）は接続した同名区間の総延長20km以上、中域（16km超）は5km以上、
近距離は全河川を表示する。端点が一致する同名区間をまとめるため、OSMの区間分割で
大きな川が一律に消えるのを防ぐ。離れた同名河川はまとめず、無名区間は個別判定する。
これは表示優先度の推定であり、一級・二級などの法的な河川区分ではない。
タイル内の位置ごとに線をまとめ、移動・回転後も画面外の描画単位を除外する。

河川の優先度・簡略化のみ変更する場合は陰影画像を作り直さず更新できる。
画像のバイト列と生成時のハッシュは保持し、河川ビルダーのハッシュのみ更新する。

```powershell
build/terrain-venv/Scripts/python tools/terrain/river_vectors.py --output build/rebuilt-rivers/terrain.zip
```

manifestに元パックの来歴、画像・河川ビルダーと素材のハッシュを記録する。

## 湖・河川の水面ポリゴン

DEMの有効な標高値は水面も含むため、標高の欠損だけでは湖岸を表せない。
湖・池・貯水池および幅のある河川の水面はOSMのポリゴンを独立して重ねる。
海と湖は「地形を表示」または「河川を表示」がONなら表示する。河川水面は「河川を表示」に連動する。
島のinnerリングを保持し、水面を河川中心線より上に描いて湖内の中心線を覆う。
重複する同種の面はタイル生成時に統合し、端末ではタイル・種別ごとにまとめて描く。

```powershell
build/terrain-venv/Scripts/python tools/terrain/water_polygons.py --output build/rebuilt-water/terrain.zip
# 保存した入力だけで再生成（取得不足時は失敗）
build/terrain-venv/Scripts/python tools/terrain/water_polygons.py --offline --output build/rebuilt-water/terrain.zip
```

日本の抽出範囲にあるnatural=water、waterway=riverbank、landuse=reservoirを対象とする。
Overpassのタグ・形状一覧に加え、リレーションは構成要素のgeometryを含むbodyも取得する。
閉じていない輪郭など、復元できないOSMオブジェクトはwater-skipped.jsonに記録する。
入力・取得時刻・ハッシュはwater-source-lock.json、加工後の全形状はwater.geojson.gzに保存する。
後者とODbL本文を設定画面から書き出せる。湖岸はデータ取得時の形状で、水位変動には追従しない。

WAT1は4バイトのマジック、uint32の面数、各面のuint32種別（0=湖など、1=河川）、
uint32リング数、各リングのuint32頂点数とuint16 x/y列（0〜4096）。すべてリトルエンディアン。
先頭リングが外周、残りが島などの穴で、リングの末尾に始点を重複保存しない。
地形・河川だけの再生成では既存の水面タイルを保持する。水面を更新するときは上記コマンドを使う。


## 海のポリゴン表示

海は陰影画像と同じ標高欠損マスクから輪郭を抽出したWAT1ポリゴンで描く。
島の穴を保持し、隣接DEMを使って海岸線を補間してからタイル分割する。
ContourPyのOuterOffset形式で外周と穴を対応付ける（https://contourpy.readthedocs.io/en/v1.3.3/user_guide/calculate/fill_type.html）。
地形OFF時は陰影画像を読み込まず、陸は方位盤の背景色とする。海岸専用PNGは同梱しない。
湖は水面ポリゴンを重ねる。両設定OFFなら海・湖・河川も表示しない。

```powershell
build/terrain-venv/Scripts/python tools/terrain/sea_tiles.py --offline --output build/rebuilt-sea/terrain.zip
```

先に陰影パックを生成して、そのときのDEMキャッシュを指定する。
地形素材を更新した場合は海ポリゴンも再生成する。元の陰影・水面・河川データは保持する。
