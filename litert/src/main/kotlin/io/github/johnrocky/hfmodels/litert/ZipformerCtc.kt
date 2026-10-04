// Ported from google-ai-edge/litert-samples c18346a8, samples/litert_model_zoo/android/.../models/zipformer/ZipformerAsr.kt (Apache-2.0).
package io.github.johnrocky.hfmodels.litert

import java.io.File

/**
 * The `zipformer_ctc` graph contract (litert-community/Zipformer-medium-CR-CTC-LiteRT, every size):
 * inputs are the log(1e-10)-padded fbank `[1, frames, 80]` plus four additive attention biases
 * (0 = real frame, -1000 = padding), one per internal frame rate (`[1, t50]`, `[1, t50/2]`,
 * `[1, t50/4]`, `[1, t50/8]`, rounded up); the output is raw CTC logits `[1, tOut, classes]` at
 * 25 Hz. For the published 16 s window: frames 1600, biases 796 / 398 / 199 / 100, logits 398 x 500.
 * Inputs are found by their size, as the zoo app does, so the converter's tensor order and names do
 * not matter.
 */
internal class ZipformerCtc(val frames: Int, val blank: Int) {
    /** Frames after the 2x subsampling (50 Hz). */
    val t50 = (frames - 7) / 2
    /** Bias lengths at 50, 25, 12.5 and 6.25 Hz: t50 / ds, rounded up. */
    val biasLengths = intArrayOf(1, 2, 4, 8).map { ds -> (t50 + ds - 1) / ds }
    /** Output frames (25 Hz). */
    val tOut = biasLengths[1]

    /** Additive bias for internal rate [r] (0..3): 0 for the frames that carry audio, -1000 for padding. */
    fun bias(r: Int, valid50: Int): FloatArray {
        val len = biasLengths[r]
        val ds = t50 / len + if (t50 % len != 0) 1 else 0 // 1, 2, 4, 8
        return FloatArray(len) { i -> if (i * ds < valid50) 0f else -1000f }
    }

    /** 50 Hz frames that carry audio for [fbankFrames] real fbank frames. */
    fun valid50(fbankFrames: Int): Int = (minOf(fbankFrames, frames) - 7) / 2

    /** 25 Hz output frames to decode. */
    fun validOut(valid50: Int): Int = minOf((valid50 + 1) / 2, tOut)

    companion object {
        /** `tokens.txt`: one `<piece> <id>` per line (the zoo app's reading: the id after the last space). */
        fun readTokens(file: File): Map<Int, String> =
            file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.associate { line ->
                val cut = line.lastIndexOf(' ')
                line.substring(cut + 1).trim().toInt() to line.substring(0, cut)
            }

        /** Greedy CTC over the real frames: argmax per frame, drop blanks and repeats, `▁` -> space, trim. */
        fun decode(logits: FloatArray, validOut: Int, classes: Int, blank: Int, pieces: Map<Int, String>): String {
            val sb = StringBuilder()
            var prev = -1
            for (t in 0 until validOut) {
                var best = -Float.MAX_VALUE
                var arg = 0
                val base = t * classes
                for (c in 0 until classes) {
                    val v = logits[base + c]
                    if (v > best) {
                        best = v
                        arg = c
                    }
                }
                if (arg != blank && arg != prev) sb.append(pieces[arg] ?: "")
                prev = arg
            }
            return sb.toString().replace('▁', ' ').trim()
        }
    }
}
