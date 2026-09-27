# yamamuki

スマホを向けた方向に見える山を、山名付きで表示する Android / iOS アプリ。山をタップすると標高などの詳細が見られる。

- Android 版: Kotlin + Jetpack Compose
- iOS 版: Swift + SwiftUI（Android 版とほぼ同じ機能）
- Android・iOSとも日本の山頂約1万4千件を内蔵し、初回から通信なしで使える。山頂の配布パックは約320KB。加えて約240MBの陰影地形パックを内蔵する。

## 構成

- `core/` Android に依存しないデータ取得ロジック（Overpass API の問い合わせ・解析、距離と方位の計算、キャッシュ方針）。単体でテストできる。
- `app/` Android アプリ。内蔵山頂データの読み込み、設定、画面。
- `ios/YamamukiCore/` iOS に依存しないロジックの Swift パッケージ。`core/` を Swift に移植したもので、単体でテストできる。内蔵山頂の解析・範囲抽出もここで検証する。
- `ios/Yamamuki/` iOS アプリ。方位盤の描画（`DialCanvasView`）、画面（`DialView`）、設定（`SettingsView`）、現在地と方位の取得（`LocationService`）。
- `ios/project.yml` Xcode プロジェクトの設定（[XcodeGen](https://github.com/yonaskolb/XcodeGen) 用）。`ios/Yamamuki.xcodeproj` はここから生成し、git には入れない。

## 開発環境

### Android 版

| 必要なもの | バージョン |
|---|---|
| JDK | 17 以上（21 で動作確認） |
| Android SDK Platform | 36（`compileSdk` / `targetSdk`） |
| Android SDK Build-Tools | 36.0.0（`buildToolsVersion` で固定） |
| Gradle | 8.14.3（`gradlew` が自動でダウンロードする） |
| 実行する端末 | Android 8.0 (API 26) 以上。現在地の標高表示は Android 14 以上 |

- Android SDK の場所は、環境変数 `ANDROID_HOME` か、リポジトリ直下の `local.properties`（git 管理外）で指定する。
  ```properties
  sdk.dir=C\:\\Users\\<ユーザー名>\\AppData\\Local\\Android\\Sdk
  ```
- SDK のライセンスに未同意だとビルドが止まる。`sdkmanager --licenses` で同意しておく。
- Visual Studio に付属する SDK（`C:\Program Files (x86)\Android\android-sdk`）は書き込みできないため、ビルドのたびに「Probably the SDK is read-only」と出るが、ビルドには影響しない。足りないパッケージを Gradle が自動で入れられないので、必要なものは管理者権限の `sdkmanager` で入れる。

### iOS 版

| 必要なもの | バージョン |
|---|---|
| Mac + Xcode | Xcode 16 以上（16.4 で動作確認） |
| XcodeGen | `brew install xcodegen` で入れる |
| 実行する端末 | iOS 17 以上の iPhone（縦向き固定） |

- iOS アプリのビルドと iPhone への転送には Mac が必要。Mac が無くても、ビルドが通るかは GitHub Actions（`.github/workflows/ios.yml`）で確認できる。
- シミュレーターでも起動できるが、方位センサーが無いので方位盤は回らない。現在地はシミュレーターのメニュー（Features > Location）で指定する。

## ビルドと実行

### Android 版

```bash
# core の単体テスト
./gradlew -p core test

# デバッグ用 APK のビルド（app/build/outputs/apk/debug/app-debug.apk）
./gradlew :app:assembleDebug

# USB でつないだ端末にインストール
./gradlew :app:installDebug
```

- 端末側で「開発者向けオプション」の「USB デバッグ」を有効にし、つないだときに出る「USB デバッグを許可しますか？」で許可する。
- つながっているかは `adb devices` で確認する（`device` と出れば OK、`unauthorized` なら端末で許可する）。PowerShell で adb をフルパスで実行するときは、先頭に `&` を付ける。
  ```powershell
  & "C:\Program Files (x86)\Android\android-sdk\platform-tools\adb.exe" devices
  ```
- 内蔵山頂パックの破損はビルド時にSHA-256で検出する。端末で読み込みに失敗した場合は画面上部に表示する。
- 開発版はアプリ ID が `io.github.shohei0205.yamamuki.debug`、名前が「山むき(開発版)」になり、配布版と同じ端末に並べて入れられる。

### iOS 版

```bash
cd ios
python3 prepare-terrain.py  # 地形画像を検証してiOS用リソースを生成
python3 prepare-peaks.py  # 共通パックを検証し、iOS用リソースを生成

# core の単体テスト
(cd YamamukiCore && swift test)

# Xcode プロジェクトを生成して開く（project.yml を変えたら生成し直す）
xcodegen generate
open Yamamuki.xcodeproj
```

- Xcode で `Yamamuki` ターゲットの「Signing & Capabilities」の Team に自分の Apple ID を選ぶ。無料の Apple ID でも、自分の iPhone に 7 日間有効な開発用署名で入れられる（期限が切れたら Xcode から入れ直す）。
- iPhone を USB でつなぎ、Xcode 上部の実行先に選んで Run（⌘R）する。初回は iPhone の「設定 > プライバシーとセキュリティ > デベロッパモード」をオンにし、「設定 > 一般 > VPN とデバイス管理」で開発元を信頼する。
- コマンドラインでビルドだけ確認するときは、CI と同じ次のコマンドを使う。
  ```bash
  xcodebuild build -project Yamamuki.xcodeproj -scheme Yamamuki \
    -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO
  ```
- 内蔵データの読み込みに失敗した場合は画面上部に表示する。

## Android 版と iOS 版の違い

方位盤と手動操作、収録する山頂は共通。両版とも通信なしで内蔵データを読み込む。

| 項目 | Android 版 | iOS 版 |
|---|---|---|
| 方位 | 回転ベクトルセンサーから計算し、偏角を足して真北に直す | Core Location の heading。iOS が偏角を補正した真方位を返す |
| 現在地の標高 | GPS の楕円体高を Android 14 以降のジオイドモデルで海抜に直す | Core Location の altitude（海抜）をそのまま使う |
| 山データ | 日本の名前付き山頂を内蔵 | 同じ山頂をJSONとして内蔵、配布用ZIPも同梱 |
| 設定の保存 | SharedPreferences | UserDefaults |
| 山の詳細 | ダイアログ | 下から出るシート |
| 位置情報を断ったとき | 許可を求め直す | 設定アプリを開くボタンを出す（iOS はアプリから再度聞けない） |

## 方位盤の画面

- 端末を向けている方位を上にした平面図に、山をアイコンと山名で描く。現在地は画面下部の双眼鏡のアイコンで、山頂にいるときは代わりに旗の立った山頂アイコンと山名を出す。アイコンは標高で描き分け、1000m 未満(標高不明を含む)は黄緑の丘、2000m 未満は緑で縁取った黄色の ▲、2000m 以上は雪をかぶった茶色の ▲(どれも同じ太さの縁取り)。距離は等倍で、灰色の同心円が距離の目安。
- 画面上部は方位の目盛り(幅 60°)と「北東 45°　標高 312m」のような表示。センサーは磁北基準なので、現在地の偏角を足して真北に直している。標高は GPS の高さ(楕円体高)を Android 14 以降のジオイドモデルで海抜に直したもので、求められないときは出さない。
- 端末を立てて構えたときは背面の向き、水平に持ったときは上端の向きを方位とする(途中の傾きでも連続)。
- ピンチで表示範囲(画面上端までの距離)を 2〜80km で変えられる。範囲に応じて山データを 20 / 50 / 120km の半径で取得する。
- 山が重なるときは標高の高い山を優先して表示する(上限は設定で変えられる)。
- 山をタップすると、山名・標高・緯度経度・現在地からの距離を表示する。
- 左下の歯車ボタンで設定画面を開く。

## 設定

両版とも「内蔵データとライセンス」から収録件数・日時を確認し、全件の山頂CSVとライセンスをZIPで保存できる。

| 項目 | 内容 | 既定 |
|---|---|---|
| 最低標高 | この標高以上の山だけ表示する（0〜3,000m）。絞り込み中は標高不明の山を出さない | 0m（すべて） |
| 表示する山の上限 | 一度に表示する山の数（10〜100） | 40 |
| 文字の大きさ | 小・標準・大・特大 | 標準 |
| 起動時の表示範囲 | 5〜50km | 15km |
| 画面を常に点灯 | 方位盤の表示中は画面を消さない | オフ |

設定は端末内（Android は SharedPreferences、iOS は UserDefaults）に保存する。

## ライセンス

ソースコードは [MIT License](LICENSE) で公開する。

アプリが表示する山データは OpenStreetMap のもので、コードとは別に [ODbL](https://opendatacommons.org/licenses/odbl/) の条件で利用している。

山データ © OpenStreetMap contributors (ODbL)

## 方位盤の手動操作（Android・iOS）

- 一本指でドラッグすると表示位置を移動し、GPS追従と地図の自動回転を停止する。
- 双眼鏡と同心円は一緒に移動する。距離は移動開始時の双眼鏡の位置を基準にし、双眼鏡の向きは引き続きコンパスに追従する。
- 手動移動後は二本指の中間点を基準に回転・拡縮・移動できる。
- 上部の方角表示を左右にスワイプすると、画面幅あたり60度の割合で方角を変更する。
- 「現在地に戻る」で最新のGPS位置とコンパスへの自動追従に戻る。
- 両版とも移動先も内蔵データを参照する。

## 内蔵山頂データ

山頂と別パックで陰影地形・河川を内蔵する。山頂データはアプリ更新時に更新される。
日本の名前付きOSM山頂・火山ノードを収録し、国外・無名・未登録の山は含まない。
AndroidのINTERNET権限は使用しない。iOSも山データ取得通信を行わない。
[素材の取得・オフライン再ビルド・ライセンス](tools/bundled_peaks/README.md) を参照。

## 陰影地形の表示

Android・iOSとも512px WebPの陰影地形をオフライン表示する。設定の「地形を表示」で切り替えられる。
海は濃い青、川は明るい青。北海道の大部分の河川は未収録。
現在位置移動・二本指回転・方角スワイプに追従し、山頂と同じ地点を基準に描画する。

地形は国土地理院の標高タイルを加工したもの。河川は © OpenStreetMap contributors (ODbL)。
設定から河川データと出典・ライセンスを保存できる。
[Git LFSによる取得、再ビルド、出典と表示精度](tools/terrain/README.md)を参照。
