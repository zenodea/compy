#!/usr/bin/env bash
# Renders the program icons from a copy of Rex installed on this Mac into local-icons/, for your
# own debug and QA builds. They belong to Rex's makers: local-icons/ is ignored by Git and left out
# of release builds, so don't commit or redistribute them.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
app="${1:-/Applications/Rex Beta.app}"
bundle="$app/Contents/Resources/SuperBlockIcons_SuperBlockIcons.bundle"
out="$root/local-icons/rex-icons"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$out"

cat > "$work/render.swift" <<'SWIFT'
import AppKit

let bundle = Bundle(path: CommandLine.arguments[1])!
let out = CommandLine.arguments[2]
var index: [String: [String: Bool]] = [:]
for name in CommandLine.arguments.dropFirst(3) {
    guard let image = bundle.image(forResource: name) else { continue }
    let size = 192
    let rep = NSBitmapImageRep(
        bitmapDataPlanes: nil, pixelsWide: size, pixelsHigh: size, bitsPerSample: 8,
        samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB,
        bytesPerRow: 0, bitsPerPixel: 0)!
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
    let fit = min(CGFloat(size) / image.size.width, CGFloat(size) / image.size.height)
    let w = image.size.width * fit
    let h = image.size.height * fit
    image.draw(in: NSRect(x: (CGFloat(size) - w) / 2, y: (CGFloat(size) - h) / 2, width: w, height: h))
    NSGraphicsContext.restoreGraphicsState()
    // A single-colour dark glyph is a template, which Compy tints to suit its tile.
    var mono = true
    outer: for y in stride(from: 0, to: size, by: 3) {
        for x in stride(from: 0, to: size, by: 3) {
            guard let c = rep.colorAt(x: x, y: y), c.alphaComponent > 0.5 else { continue }
            if c.redComponent > 0.2 || c.greenComponent > 0.2 || c.blueComponent > 0.2 {
                mono = false
                break outer
            }
        }
    }
    let key = String(name.dropFirst("block-glyph-".count))
    try rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: "\(out)/\(key).png"))
    index[key] = ["mono": mono]
}
let json = try JSONSerialization.data(withJSONObject: index, options: [.sortedKeys])
try json.write(to: URL(fileURLWithPath: "\(out)/index.json"))
print("rendered \(index.count) icons")
SWIFT

swiftc -O "$work/render.swift" -o "$work/render"
names=()
while IFS= read -r name; do names+=("$name"); done < <(
  assetutil --info "$bundle/Contents/Resources/Assets.car" | python3 -c '
import json, sys
print("\n".join(sorted({x.get("Name", "") for x in json.load(sys.stdin) if isinstance(x, dict) and x.get("Name", "").startswith("block-glyph-")})))'
)
"$work/render" "$bundle" "$out" "${names[@]}"
