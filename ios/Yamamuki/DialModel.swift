import CoreLocation
import Foundation
import Observation
import YamamukiCore

struct GeoPoint: Equatable {
    let latitude: Double
    let longitude: Double
    /// 標高(海抜)。求められないときは nil。
    let mslAltitudeM: Double?
}

/// 現在地と表示範囲に応じて山データを取得し、方位盤に出す山の一覧を保つ(Android 版の DialViewModel に相当)。
@Observable
final class DialModel {
    private(set) var location: GeoPoint?
    private(set) var gpsLocation: GeoPoint?
    private(set) var observerLocation: GeoPoint?
    private(set) var exploring = false
    private(set) var lockedHeading: Double?
    var displayHeading: Double { lockedHeading ?? heading ?? 0 }

    /// 現在地から見た山。最低標高で絞り込み、表示の優先順(標高の高い順)に並べたもの。現在地が変わるたびに計算し直す。
    private(set) var mountains: [NearbyMountain] = []
    /// 現在地がほぼ山頂([summitRadiusKm] 以内)のとき、その山。
    /// 最低標高の絞り込みとは関係なく探し、[mountains] からは除く(現在地の位置に別のアイコンで出す)。
    private(set) var summit: NearbyMountain?
    /// 端末を向けている方位(真北基準)。センサーの値が届くまでは nil。
    private(set) var heading: Double?
    /// 現在地から画面上端までの距離。
    private(set) var rangeKm: Double
    private(set) var terrain: TerrainFrame?
    private(set) var terrainLoading = false
    private(set) var terrainError: String?
    private(set) var loading = false
    private(set) var errorMessage: String?
    private(set) var dataInfo: BundledPeakInfo?
    private(set) var settings: Settings
    private(set) var authorization: CLAuthorizationStatus = .notDetermined

    var hasLocationPermission: Bool {
        authorization == .authorizedWhenInUse || authorization == .authorizedAlways
    }

    @ObservationIgnored private let terrainStore = TerrainStore()
    @ObservationIgnored private var terrainTask: Task<Void, Never>?
    @ObservationIgnored private var terrainCenter: GeoPoint?
    @ObservationIgnored private var terrainObserver: GeoPoint?
    @ObservationIgnored private var terrainRange = 0.0
    @ObservationIgnored private var terrainRequestID = UUID()
    private let data = BundledPeakStore()
    private let settingsStore = SettingsStore()
    private let locationService = LocationService()
    @ObservationIgnored private var peaks: [Mountain] = []
    @ObservationIgnored private var fetchedCenter: GeoPoint?
    @ObservationIgnored private var fetchedRadiusKm = 0.0
    @ObservationIgnored private var fetchTask: Task<Void, Never>?
    private static let refetchDistanceKm = 0.2

    init() {
        let saved = SettingsStore().load()
        settings = saved
        rangeKm = Double(saved.initialRangeKm)
        authorization = locationService.authorization

        locationService.onLocation = { [weak self] in self?.onLocation($0) }
        locationService.onHeading = { [weak self] in self?.heading = $0 }
        locationService.onAuthorizationChange = { [weak self] status in
            guard let self else { return }
            authorization = status
            if hasLocationPermission { locationService.start() }
        }
    }

    /// 画面が前面に出たとき。山頂データは通信せず内蔵版を読む。
    func start() {
        if authorization == .notDetermined {
            locationService.requestAuthorization()
        }
        locationService.start()
        if dataInfo == nil { refreshDataInfo() }
    }

    func stop() {
        locationService.stop()
    }

    func requestLocationPermission() {
        locationService.requestAuthorization()
    }

    private func onLocation(_ loc: CLLocation) {
        // 高さを持たない位置(verticalAccuracy が負)では、標高の表示が消えないよう直前の標高を引き継ぐ。
        let msl = loc.verticalAccuracy >= 0 ? loc.altitude : gpsLocation?.mslAltitudeM
        let point = GeoPoint(latitude: loc.coordinate.latitude, longitude: loc.coordinate.longitude, mslAltitudeM: msl)
        gpsLocation = point
        guard !exploring else { return }
        location = point
        observerLocation = point
        updatePeaks(at: point)
        loadTerrainIfNeeded()
        if let center = fetchedCenter,
           GeoMath.distanceKm(center.latitude, center.longitude, point.latitude, point.longitude) <= Self.refetchDistanceKm {
            return
        }
        fetch()
    }

    /// ピンチの倍率(前回からの変化分)。
    func onZoom(_ zoom: Double) {
        rangeKm = DialGeometry.zoomedRange(rangeKm, zoom: zoom)
        loadTerrainIfNeeded()
        if DialGeometry.fetchRadiusKm(rangeKm) > fetchedRadiusKm { fetch() }
    }

    func onPan(dx: Double, dy: Double, chartHeight: Double) {
        guard let here = location else { return }
        let next = PanGeometry.drag(MapCenter(here.latitude, here.longitude), dx: dx, dy: dy,
            scale: chartHeight / rangeKm, heading: displayHeading)
        guard next != MapCenter(here.latitude, here.longitude) else { return }
        beginExploring()
        location = GeoPoint(latitude: next.latitude, longitude: next.longitude, mslAltitudeM: nil)
        loadTerrainIfNeeded()
        fetchForViewport()
    }

    private func beginExploring() {
        observerLocation = observerLocation ?? location
        lockedHeading = displayHeading
        exploring = true
    }

    func onHeadingSwipe(dx: Double, width: Double) {
        guard location != nil, dx.isFinite, dx != 0, width.isFinite, width > 0 else { return }
        beginExploring()
        lockedHeading = DialGeometry.swipedHeading(displayHeading, dx: dx, width: width)
    }

    func onTransform(zoom: Double, rotation: Double, previous: PlanOffset, midpoint: PlanOffset, chartHeight: Double) {
        guard exploring, let oldHeading = lockedHeading else { onZoom(zoom); return }
        guard let observer = observerLocation, let viewport = location,
              rotation.isFinite, chartHeight.isFinite, chartHeight > 0 else { return }
        let range = DialGeometry.zoomedRange(rangeKm, zoom: zoom)
        let nextHeading = Heading.normalize(oldHeading - rotation)
        let next = PanGeometry.transformViewport(MapCenter(observer.latitude, observer.longitude),
            viewport: MapCenter(viewport.latitude, viewport.longitude), previous: previous, midpoint: midpoint,
            oldScale: chartHeight / rangeKm, newScale: chartHeight / range,
            oldHeading: oldHeading, newHeading: nextHeading)
        location = GeoPoint(latitude: next.latitude, longitude: next.longitude, mslAltitudeM: nil)
        rangeKm = range
        lockedHeading = nextHeading
        loadTerrainIfNeeded()
        fetchForViewport()
    }

    func resetCenter() {
        guard let here = gpsLocation else { return }
        location = here
        observerLocation = here
        exploring = false
        lockedHeading = nil
        updatePeaks(at: here)
        loadTerrainIfNeeded(force: true)
        fetch()
    }

    private func fetchForViewport() {
        guard let here = location else { return }
        if let center = fetchedCenter,
           GeoMath.distanceKm(center.latitude, center.longitude, here.latitude, here.longitude) <= Self.refetchDistanceKm,
           DialGeometry.fetchRadiusKm(rangeKm) <= fetchedRadiusKm { return }
        fetch()
    }

    func retry() { refreshDataInfo(); fetch(); loadTerrainIfNeeded(force: true) }

    private func refreshDataInfo() {
        Task { @MainActor in
            do { dataInfo = try await data.info(); errorMessage = nil }
            catch { errorMessage = "内蔵データを読み込めません: \(error.localizedDescription)" }
        }
    }

    func updateSettings(_ transform: (inout Settings) -> Void) {
        let before = settings
        var after = settings
        transform(&after)
        settings = after
        settingsStore.save(after)

        if after.minElevationM != before.minElevationM, let here = observerLocation {
            updatePeaks(at: here)
        }
        // 起動時の範囲を変えたら、試しやすいよう今の表示にもすぐ反映する。
        if after.initialRangeKm != before.initialRangeKm {
            rangeKm = Double(after.initialRangeKm)
            if DialGeometry.fetchRadiusKm(rangeKm) > fetchedRadiusKm { fetch() }
        }
        if before.showTerrain != after.showTerrain || before.initialRangeKm != after.initialRangeKm {
            loadTerrainIfNeeded(force: true)
        }
    }

    private func loadTerrainIfNeeded(force: Bool = false) {
        guard settings.showTerrain else {
            terrainTask?.cancel()
            terrainRequestID = UUID()
            terrain = nil; terrainCenter = nil; terrainRange = 0
            terrainLoading = false; terrainError = nil
            return
        }
        guard let here = location else { return }
        guard let observer = observerLocation else { return }
        let range = rangeKm
        if !force, let center = terrainCenter, let previousObserver = terrainObserver,
           GeoMath.distanceKm(previousObserver.latitude, previousObserver.longitude, observer.latitude, observer.longitude) <= 0.2,
           GeoMath.distanceKm(center.latitude,center.longitude,here.latitude,here.longitude) <= 0.2,
           TerrainGeometry.zoom(range) == TerrainGeometry.zoom(terrainRange), range <= terrainRange * 1.2 { return }
        let refining = terrainRange > 0 && TerrainGeometry.zoom(range) > TerrainGeometry.zoom(terrainRange)
        terrainTask?.cancel()
        let requestID = UUID()
        terrainRequestID = requestID
        terrainCenter = here; terrainRange = range; terrainObserver = observer
        terrainLoading = true; terrainError = nil
        terrainTask = Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                if !refining { try await Task.sleep(nanoseconds: 80_000_000) }
                let frame = try await terrainStore.render(latitude: observer.latitude, longitude: observer.longitude,
                    viewportLatitude: here.latitude, viewportLongitude: here.longitude, rangeKm: range)
                guard !Task.isCancelled, terrainRequestID == requestID else { return }
                terrain = frame
                terrainLoading = false
            } catch {
                guard !Task.isCancelled, terrainRequestID == requestID else { return }
                terrainCenter = nil; terrainRange = 0
                terrainLoading = false
                terrainError = error.localizedDescription
            }
        }
    }

    private func fetch() {
        guard let here = location else { return }
        let radius = DialGeometry.fetchRadiusKm(rangeKm)
        fetchTask?.cancel()
        fetchTask = Task { @MainActor [weak self] in
            guard let self else { return }
            loading = true
            errorMessage = nil
            do {
                if exploring { try await Task.sleep(nanoseconds: 80_000_000) }
                let result = try await data.nearby(latitude: here.latitude, longitude: here.longitude, radiusKm: radius)
                guard !Task.isCancelled else { return }
                peaks = result
                fetchedCenter = here
                fetchedRadiusKm = radius
                updatePeaks(at: observerLocation ?? here)
                loading = false
            } catch {
                guard !Task.isCancelled else { return }
                errorMessage = "内蔵データを読み込めません: \(error.localizedDescription)"
                loading = false
            }
        }
    }

    /// [p] から見た山の一覧と、山頂にいるならその山を入れ直す。
    private func updatePeaks(at p: GeoPoint) {
        let all = peaks.map { $0.seen(fromLatitude: p.latitude, longitude: p.longitude) }
        let top = summitAt(all)
        let minElevation = settings.minElevationM
        mountains = all
            .filter { $0.mountain.osmId != top?.mountain.osmId && $0.mountain.meetsMinElevation(minElevation) }
            .sorted(by: displayPriority)
        summit = top
    }
}
