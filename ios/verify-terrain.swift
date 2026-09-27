// Run on macOS in CI to exercise Apple's decoder against the real bundled tiles.
import Foundation
import ImageIO

let root = URL(fileURLWithPath: CommandLine.arguments[1])
let manifest = try JSONSerialization.jsonObject(with: Data(contentsOf:root.appendingPathComponent("manifest.json"))) as! [String:Any]
let terrain = manifest["terrain"] as! [String:Any]
let expectedCount = terrain["tile_count"] as! Int
let files = FileManager.default.enumerator(at:root,includingPropertiesForKeys:nil)!
var count = 0
for case let url as URL in files where url.pathExtension == "webp" {
    try autoreleasepool {
        guard let source = CGImageSourceCreateWithURL(url as CFURL,nil),
              let image = CGImageSourceCreateImageAtIndex(source,0,[kCGImageSourceShouldCacheImmediately:true] as CFDictionary),
              image.width == 512, image.height == 512 else {
            throw NSError(domain:"TerrainValidation",code:1,userInfo:[NSLocalizedDescriptionKey:url.path])
        }
    }
    count += 1
}
precondition(count == expectedCount,"Missing terrain images")
print("ImageIO decoded \(count) 512px terrain images")
