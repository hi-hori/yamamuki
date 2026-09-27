import Foundation
import CoreGraphics
import ImageIO
import UIKit
import YamamukiCore

/// Immutable image produced away from the main actor, reused while rotating/zooming.
struct TerrainFrame: @unchecked Sendable {
    let image: CGImage
    let latitude: Double
    let longitude: Double
    let centerX: Double
    let centerY: Double
    let radiusKm: Double
}

enum TerrainError: LocalizedError {
    case missingBundle, invalidTile, renderFailed
    var errorDescription: String? {
        switch self {
        case .missingBundle: return "内蔵地形がありません。アプリを再インストールしてください。"
        case .invalidTile: return "内蔵地形画像を読み込めません。"
        case .renderFailed: return "地形の描画に失敗しました。"
        }
    }
}

actor TerrainStore {
    private let root = Bundle.main.url(forResource: "Terrain", withExtension: nil)
    private let cache = NSCache<NSString, UIImage>()

    init() { cache.totalCostLimit = 48 * 1024 * 1024 }

    func render(latitude: Double, longitude: Double, viewportLatitude: Double, viewportLongitude: Double, rangeKm: Double) throws -> TerrainFrame? {
        guard let root else { throw TerrainError.missingBundle }
        let zoom = TerrainGeometry.zoom(rangeKm)
        let radius = rangeKm * 1.4
        // 山頂と同じ固定観測点からの投影を使い、表示中心周囲だけを合成する。
        let center = DialGeometry.project(
            distanceKm: GeoMath.distanceKm(latitude, longitude, viewportLatitude, viewportLongitude),
            bearingDeg: GeoMath.bearingDeg(latitude, longitude, viewportLatitude, viewportLongitude), headingDeg: 0)
        // Match source image spacing, capped to bound the composite's memory.
        let tileKm = 40075.0166856 * cos(viewportLatitude * .pi / 180) / Double(1 << zoom)
        let pixels = min(4096, max(512, Int(ceil(radius * 2 * 512 / tileKm))))
        let size = CGFloat(pixels)
        let scale = size / CGFloat(radius * 2)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        format.preferredRange = .standard
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: size,height: size),format: format)
        var failure: Error?
        var found = false
        let result = renderer.image { output in
            let ctx = output.cgContext
            ctx.setFillColor(UIColor(red: 82/255.0,green: 145/255.0,blue: 180/255.0,alpha: 1).cgColor)
            ctx.fill(CGRect(x: 0,y: 0,width: size,height: size))
            ctx.setShouldAntialias(false) // Adjacent triangles must not reveal seams.
            ctx.interpolationQuality = .medium
            do {
                for key in TerrainGeometry.covering(latitude: viewportLatitude,longitude: viewportLongitude,radiusKm: radius,zoom: zoom) {
                    try Task.checkCancellation()
                    let path = key.path
                    let url = root.appendingPathComponent(path)
                    guard FileManager.default.fileExists(atPath: url.path) else { continue }
                    let image: UIImage
                    if let hit = cache.object(forKey: path as NSString) { image = hit }
                    else {
                        guard let source = CGImageSourceCreateWithURL(url as CFURL,nil),
                              let cg = CGImageSourceCreateImageAtIndex(source,0,
                                [kCGImageSourceShouldCacheImmediately: true] as CFDictionary),
                              cg.width == 512, cg.height == 512 else { throw TerrainError.invalidTile }
                        image = UIImage(cgImage: cg)
                        cache.setObject(image,forKey: path as NSString,cost: cg.bytesPerRow * cg.height)
                    }
                    found = true
                    let mesh = TerrainGeometry.mesh(key,latitude: latitude,longitude: longitude).map {
                        CGPoint(x: size/2 + CGFloat($0.x - center.x)*scale,y: size/2 - CGFloat($0.y - center.y)*scale)
                    }
                    for row in 0..<4 { for col in 0..<4 {
                        let a = row*5+col, b = a+1, c = a+5, d = c+1
                        let x = CGFloat(col)*128, y = CGFloat(row)*128
                        Self.triangle(ctx,image,[CGPoint(x:x,y:y),CGPoint(x:x+128,y:y),CGPoint(x:x,y:y+128)],
                                      [mesh[a],mesh[b],mesh[c]])
                        Self.triangle(ctx,image,[CGPoint(x:x+128,y:y+128),CGPoint(x:x,y:y+128),CGPoint(x:x+128,y:y)],
                                      [mesh[d],mesh[c],mesh[b]])
                    } }
                }
            } catch { failure = error }
        }
        if let failure { throw failure }
        try Task.checkCancellation()
        guard found else { return nil } // Outside the bundled coverage: keep the beige background.
        guard let image = result.cgImage else { throw TerrainError.renderFailed }
        return TerrainFrame(image: image,latitude: latitude,longitude: longitude,centerX: center.x,centerY: center.y,radiusKm: radius)
    }

    private static func triangle(_ ctx: CGContext,_ image: UIImage,_ source: [CGPoint],_ target: [CGPoint]) {
        let sx = source[1].x-source[0].x, sy = source[1].y-source[0].y
        let ux = source[2].x-source[0].x, uy = source[2].y-source[0].y
        let dx = target[1].x-target[0].x, dy = target[1].y-target[0].y
        let vx = target[2].x-target[0].x, vy = target[2].y-target[0].y
        let det = sx*uy-ux*sy
        let a = (dx*uy-vx*sy)/det, c = (vx*sx-dx*ux)/det
        let b = (dy*uy-vy*sy)/det, d = (vy*sx-dy*ux)/det
        ctx.saveGState()
        ctx.beginPath(); ctx.move(to: target[0]); ctx.addLine(to: target[1]); ctx.addLine(to: target[2]); ctx.closePath()
        ctx.clip()
        ctx.concatenate(CGAffineTransform(a:a,b:b,c:c,d:d,
            tx:target[0].x-a*source[0].x-c*source[0].y,ty:target[0].y-b*source[0].x-d*source[0].y))
        image.draw(in: CGRect(x:0,y:0,width:512,height:512))
        ctx.restoreGState()
    }
}
