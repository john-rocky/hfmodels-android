// The text on a frame, read with macOS Vision (accurate mode, English): one line per recognised string,
// "<top px> <left px> <text>" in image pixels from the top-left, sorted top to bottom. take_lines.py reads the numbers on
// the result screen with it (line (c): the ms on screen = the run JSON's embed_ms).
//   swift K/scripts/ocr.swift <image.png> [<image.png> ...]     (a "# <path>" line before each image's lines)
import AppKit
import Foundation
import CoreML
import Vision

for path in CommandLine.arguments.dropFirst() {
    print("# \(path)")
    guard let img = NSImage(contentsOfFile: path),
          let cg = img.cgImage(forProposedRect: nil, context: nil, hints: nil) else {
        print("! cannot read \(path)")
        continue
    }
    let w = Double(cg.width), h = Double(cg.height)
    let req = VNRecognizeTextRequest()
    req.recognitionLevel = .accurate
    req.usesLanguageCorrection = false
    req.recognitionLanguages = ["en-US"]
    // The Neural Engine path fails in a sandboxed shell (e5rt error 13): run the request on the CPU.
    if let cpu = MLComputeDevice.allComputeDevices.first(where: { if case .cpu = $0 { return true }; return false }) {
        for stage in ((try? req.supportedComputeStageDevices) ?? [:]).keys { req.setComputeDevice(cpu, for: stage) }
    }
    do {
        try VNImageRequestHandler(cgImage: cg, options: [:]).perform([req])
    } catch {
        print("! vision failed: \(error)")
        continue
    }
    var rows: [(Int, Int, String)] = []
    for obs in req.results ?? [] {
        guard let top = obs.topCandidates(1).first else { continue }
        let b = obs.boundingBox  // normalised, origin bottom-left
        rows.append((Int((1.0 - b.maxY) * h), Int(b.minX * w), top.string))
    }
    for r in rows.sorted(by: { $0.0 == $1.0 ? $0.1 < $1.1 : $0.0 < $1.0 }) {
        print("\(r.0) \(r.1) \(r.2)")
    }
}
