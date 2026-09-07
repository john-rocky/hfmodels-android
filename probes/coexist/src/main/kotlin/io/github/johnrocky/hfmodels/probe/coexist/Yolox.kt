package io.github.johnrocky.hfmodels.probe.coexist

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import java.io.Closeable
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Minimal YOLOX-Nano on LiteRT CompiledModel, enough for one run: letterbox to 416, BGR 0-255,
 * decode the raw [1, 3549, 85] head (grid + stride), per-class NMS. Contract from the model card
 * of litert-community/yolox-nano-litert and LiteRT-Models/yolox.
 */
class Yolox(modelPath: String, accelerator: Accelerator) : Closeable {
    data class Det(val classId: Int, val score: Float, val l: Float, val t: Float, val r: Float, val b: Float)

    private val model: CompiledModel = CompiledModel.create(modelPath, CompiledModel.Options(accelerator))
    private val inputs: List<TensorBuffer> = model.createInputBuffers()
    private val outputs: List<TensorBuffer> = model.createOutputBuffers()
    private val gridX = IntArray(ANCHORS)
    private val gridY = IntArray(ANCHORS)
    private val gridS = IntArray(ANCHORS)

    init {
        var i = 0
        for (s in STRIDES) {
            val n = SIZE / s
            for (y in 0 until n) for (x in 0 until n) { gridX[i] = x; gridY[i] = y; gridS[i] = s; i++ }
        }
        check(i == ANCHORS)
    }

    fun detect(bitmap: Bitmap, scoreThreshold: Float = 0.30f, iou: Float = 0.45f): List<Det> {
        val ratio = min(SIZE.toFloat() / bitmap.width, SIZE.toFloat() / bitmap.height)
        val canvas = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        Canvas(canvas).apply {
            drawColor(Color.rgb(114, 114, 114))
            drawBitmap(bitmap, Matrix().apply { setScale(ratio, ratio) }, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        val px = IntArray(SIZE * SIZE)
        canvas.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        val f = FloatArray(SIZE * SIZE * 3)
        var k = 0
        for (p in px) {
            f[k++] = (p and 0xFF).toFloat()
            f[k++] = ((p shr 8) and 0xFF).toFloat()
            f[k++] = ((p shr 16) and 0xFF).toFloat()
        }
        inputs[0].writeFloat(f)
        model.run(inputs, outputs)
        val raw = outputs[0].readFloat()
        val cands = ArrayList<Det>()
        for (i in 0 until ANCHORS) {
            val base = i * FIELDS
            val obj = raw[base + 4]
            if (obj < scoreThreshold) continue
            var best = 0; var bestS = 0f
            for (c in 0 until CLASSES) { val s = raw[base + 5 + c]; if (s > bestS) { bestS = s; best = c } }
            val score = obj * bestS
            if (score < scoreThreshold) continue
            val s = gridS[i]
            val cx = (raw[base] + gridX[i]) * s
            val cy = (raw[base + 1] + gridY[i]) * s
            val w = exp(raw[base + 2]) * s
            val h = exp(raw[base + 3]) * s
            cands += Det(
                best, score,
                ((cx - w / 2) / ratio).coerceIn(0f, bitmap.width.toFloat()),
                ((cy - h / 2) / ratio).coerceIn(0f, bitmap.height.toFloat()),
                ((cx + w / 2) / ratio).coerceIn(0f, bitmap.width.toFloat()),
                ((cy + h / 2) / ratio).coerceIn(0f, bitmap.height.toFloat()),
            )
        }
        val sorted = cands.sortedByDescending { it.score }
        val keep = BooleanArray(sorted.size) { true }
        val out = ArrayList<Det>()
        for (i in sorted.indices) {
            if (!keep[i]) continue
            out += sorted[i]
            for (j in i + 1 until sorted.size) if (keep[j] && sorted[j].classId == sorted[i].classId && iou(sorted[i], sorted[j]) > iou) keep[j] = false
        }
        return out
    }

    private fun iou(a: Det, b: Det): Float {
        val inter = max(0f, min(a.r, b.r) - max(a.l, b.l)) * max(0f, min(a.b, b.b) - max(a.t, b.t))
        return inter / ((a.r - a.l) * (a.b - a.t) + (b.r - b.l) * (b.b - b.t) - inter + 1e-6f)
    }

    override fun close() {
        inputs.forEach { it.close() }
        outputs.forEach { it.close() }
        model.close()
    }

    companion object {
        const val SIZE = 416
        const val CLASSES = 80
        const val FIELDS = CLASSES + 5
        val STRIDES = intArrayOf(8, 16, 32)
        const val ANCHORS = 52 * 52 + 26 * 26 + 13 * 13 // 3549
    }
}
