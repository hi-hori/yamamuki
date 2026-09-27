import Foundation
import YamamukiCore

/// 設定画面で変えられる値。
struct Settings: Codable, Equatable {
    var showRivers = true
    var showTerrain = true
    /// この標高(m)以上の山だけ方位盤に出す。0 なら絞り込まない。
    var minElevationM = 0
    /// 方位盤を表示している間は画面を消さない。
    var keepScreenOn = false
    /// 方位盤に一度に出す山の上限。
    var maxPeaks = 40
    /// 方位盤の文字の大きさ(標準 = 1.0 に対する倍率)。
    var textScale = 1.0
    /// 起動時の表示範囲(現在地から画面上端までの距離)。
    var initialRangeKm = Int(DialGeometry.defaultRangeKm)


    static let textScales = [0.85, 1.0, 1.2, 1.4]
    static let initialRangesKm = [5, 10, 15, 20, 30, 50]
    static let maxPeaksRange = 10...100

    init() {}

    /// 項目を足しても前の保存内容を読めるよう、無い項目は既定値にする。
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let d = Settings()
        showRivers = try c.decodeIfPresent(Bool.self, forKey: .showRivers) ?? d.showRivers
        showTerrain = try c.decodeIfPresent(Bool.self, forKey: .showTerrain) ?? d.showTerrain
        minElevationM = try c.decodeIfPresent(Int.self, forKey: .minElevationM) ?? d.minElevationM
        keepScreenOn = try c.decodeIfPresent(Bool.self, forKey: .keepScreenOn) ?? d.keepScreenOn
        maxPeaks = try c.decodeIfPresent(Int.self, forKey: .maxPeaks) ?? d.maxPeaks
        textScale = try c.decodeIfPresent(Double.self, forKey: .textScale) ?? d.textScale
        initialRangeKm = try c.decodeIfPresent(Int.self, forKey: .initialRangeKm) ?? d.initialRangeKm
    }
}

/// [Settings] を端末内(UserDefaults)に保存する。
struct SettingsStore {
    private let defaults = UserDefaults.standard
    private let key = "settings"

    func load() -> Settings {
        guard let data = defaults.data(forKey: key),
              let settings = try? JSONDecoder().decode(Settings.self, from: data)
        else { return Settings() }
        return settings
    }

    func save(_ settings: Settings) {
        if let data = try? JSONEncoder().encode(settings) { defaults.set(data, forKey: key) }
    }
}
