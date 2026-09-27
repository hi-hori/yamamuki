import SwiftUI
import YamamukiCore

/// 最低標高スライダーの上限と刻み。
private let maxMinElevationM = 3000
private let minElevationStepM = 100
private let maxPeaksStep = 10

/// 設定画面。方位盤の左下の設定ボタンで開く。
struct SettingsView: View {
    let model: DialModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let settings = model.settings
        NavigationStack {
            Form {
                Section("表示する山") {
                    StepSlider(
                        value: settings.minElevationM,
                        range: 0...maxMinElevationM,
                        step: minElevationStepM,
                        label: minElevationLabel,
                        description: "0 m ですべての山を表示します。絞り込み中は標高不明の山を表示しません。"
                    ) { m in model.updateSettings { $0.minElevationM = m } }
                    StepSlider(
                        value: settings.maxPeaks,
                        range: Settings.maxPeaksRange,
                        step: maxPeaksStep,
                        label: { "一度に表示する山 最大 \($0) 件" },
                        description: "多いと画面が混み合い、少ないと高い山だけになります。重なる山は標高の低いほうを省きます。"
                    ) { n in model.updateSettings { $0.maxPeaks = n } }
                }

                Section("表示") {
                    Choice(
                        title: "文字の大きさ",
                        options: Settings.textScales,
                        selected: settings.textScale,
                        label: textScaleLabel
                    ) { v in model.updateSettings { $0.textScale = v } }
                    Choice(
                        title: "起動時の表示範囲（km）",
                        options: Settings.initialRangesKm,
                        selected: settings.initialRangeKm,
                        // 6 つ並ぶと「10km」が収まらないので、単位は見出しに出す。
                        label: { "\($0)" },
                        description: "現在地から画面上端までの距離。起動後はピンチで変えられます。"
                    ) { v in model.updateSettings { $0.initialRangeKm = v } }
                }

                Section("画面") {
                    SwitchRow(
                        title: "画面を常に点灯",
                        description: "方位盤を表示している間は画面を消しません。電池の減りが早くなります。",
                        isOn: settings.keepScreenOn
                    ) { v in model.updateSettings { $0.keepScreenOn = v } }
                }

                Section("内蔵データとライセンス") {
                    if let info = model.dataInfo {
                        Text("山頂 \(info.count) 件・パック \(byteSizeText(info.bytes))")
                        Text("山頂データ \(info.osmDate)・素材取得 \(info.inputDate)").font(.footnote)
                    }
                    Text("日本の名前付き山頂を内蔵しています。通信なしで利用でき、データはアプリ更新時に更新されます。未登録・無名・国外の山は含みません。")
                    Text("山頂 © OpenStreetMap contributors — ODbL 1.0")
                    Link("OpenStreetMap の著作権とライセンス", destination: URL(string: "https://www.openstreetmap.org/copyright")!)
                    Link("ODbL 1.0", destination: URL(string: "https://opendatacommons.org/licenses/odbl/1-0/")!)
                    Text("抽出・整形した山頂データもODbL 1.0で提供します。全件の山頂CSVとライセンス本文を保存・再利用できます。")
                    if let url = Bundle.main.url(forResource: "peaks", withExtension: "zip", subdirectory: "Peaks") {
                        ShareLink("山頂データとライセンスを保存・共有", item: url)
                    }
                    if let error = model.errorMessage { Text(error).foregroundStyle(.red) }
                }

                Section("このアプリについて") {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("バージョン \(appVersion)")
                        Text("山データ © OpenStreetMap contributors (ODbL)").font(.footnote).foregroundStyle(.secondary)
                    }
                }
            }
            .navigationTitle("設定")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("閉じる") { dismiss() }
                }
            }
        }
    }

    /// project.yml の MARKETING_VERSION / CURRENT_PROJECT_VERSION。
    private var appVersion: String {
        let info = Bundle.main.infoDictionary
        let version = info?["CFBundleShortVersionString"] as? String ?? "?"
        let build = info?["CFBundleVersion"] as? String ?? "?"
        return "\(version) (\(build))"
    }
}

private func minElevationLabel(_ m: Int) -> String {
    m == 0 ? "すべての山を表示" : "標高 \(groupedInteger(m)) m 以上の山だけ表示"
}

private func textScaleLabel(_ scale: Double) -> String {
    switch scale {
    case 0.85: return "小"
    case 1.0: return "標準"
    case 1.2: return "大"
    case 1.4: return "特大"
    default: return "×\(scale)"
    }
}

/// [step] 刻みのスライダー。ドラッグ中は画面内だけで値を動かし、指を離したときに保存する。見出しは [label] で作る。
private struct StepSlider: View {
    let value: Int
    let range: ClosedRange<Int>
    let step: Int
    let label: (Int) -> String
    let description: String
    let onChange: (Int) -> Void

    @State private var dragging: Double?

    var body: some View {
        let current = dragging ?? Double(value)
        VStack(alignment: .leading, spacing: 4) {
            Text(label(Int(current.rounded())))
            Slider(
                value: Binding(get: { current }, set: { dragging = $0 }),
                in: Double(range.lowerBound)...Double(range.upperBound),
                step: Double(step)
            ) { editing in
                if !editing, let d = dragging {
                    onChange(Int(d.rounded()))
                    dragging = nil
                }
            }
            Text(description).font(.footnote).foregroundStyle(.secondary)
        }
    }
}

private struct Choice<T: Hashable>: View {
    let title: String
    let options: [T]
    let selected: T
    let label: (T) -> String
    var description: String?
    let onSelect: (T) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
            Picker(title, selection: Binding(get: { selected }, set: onSelect)) {
                ForEach(options, id: \.self) { option in
                    Text(label(option)).tag(option)
                }
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            if let description {
                Text(description).font(.footnote).foregroundStyle(.secondary)
            }
        }
    }
}

private struct SwitchRow: View {
    let title: String
    let description: String
    let isOn: Bool
    let onChange: (Bool) -> Void

    var body: some View {
        Toggle(isOn: Binding(get: { isOn }, set: onChange)) {
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                Text(description).font(.footnote).foregroundStyle(.secondary)
            }
        }
    }
}
