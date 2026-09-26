# yamamuki

スマホを向けた方向に見える山を、山名と標高付きで表示する Android アプリ。

- Kotlin + Jetpack Compose
- 山データは OpenStreetMap の Overpass API から取得し、端末内 (Room) にキャッシュしてオフラインでも使えるようにする

## 構成

- `core/` Android に依存しないデータ取得ロジック（Overpass API の問い合わせ・解析、距離と方位の計算、キャッシュ方針）。単体でテストできる。
  - `./gradlew -p core test`
- `app/` Android アプリ。Room によるキャッシュ実装と画面。
  - `./gradlew :app:assembleDebug`

## キャッシュの仕組み

緯度経度 0.5° 四方のタイル単位で「取得済みか・いつ取得したか」を記録する。現在地の周辺で未取得または 30 日より古いタイルだけを Overpass に問い合わせ、通信できないときはキャッシュ済みのデータで表示する。

山データ © OpenStreetMap contributors (ODbL)
