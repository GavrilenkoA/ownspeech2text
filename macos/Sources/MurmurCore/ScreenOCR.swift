import CoreGraphics
import Foundation
import Vision

/// Screen text for ctx v2 with Apple's Vision OCR (ru + en), lines in reading order, boxes in image pixels
/// (top-left origin). Replaces PP-OCR of the Android build.
public enum ScreenOCR {
    public static func recognize(_ image: CGImage, languages: [String] = ["ru-RU", "en-US"]) throws -> [CtxV2.Line] {
        let req = VNRecognizeTextRequest()
        req.recognitionLevel = .accurate
        req.usesLanguageCorrection = true
        req.recognitionLanguages = languages
        try VNImageRequestHandler(cgImage: image).perform([req])
        let w = Double(image.width), h = Double(image.height)
        var lines: [CtxV2.Line] = []
        for obs in req.results ?? [] {
            guard let top = obs.topCandidates(1).first else { continue }
            let b = obs.boundingBox  // normalized, origin bottom-left
            let box = [Int(b.minX * w), Int((1 - b.maxY) * h), Int(b.maxX * w), Int((1 - b.minY) * h)]
            lines.append(CtxV2.Line(text: top.string, conf: Double((top.confidence * 100).rounded() / 100), box: box))
        }
        // reading order: top to bottom, then left to right
        return lines.sorted { a, b in
            let ay = a.box![1], by = b.box![1]
            return abs(ay - by) > 8 ? ay < by : a.box![0] < b.box![0]
        }
    }
}
