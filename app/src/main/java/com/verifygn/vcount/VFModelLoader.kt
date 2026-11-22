package com.verifygn.vcount

import android.graphics.RectF
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Collections
import java.util.zip.Inflater
import kotlin.math.max
import kotlin.math.min

/**
 * VerifyGN Proprietary `.vf` Binary Container Parser & ONNX Runtime Engine for Native Android.
 *
 * Binary layout of `best_fast.vf` (or `best.vf` exported with `--export-fast`):
 *   [0..7]   8 bytes  : Magic Header b"VFGN26\x01\x00"
 *   [8..11]  4 bytes  : Header JSON length (uint32 little-endian)
 *   [12..15] 4 bytes  : Flags (bit 0 = zlib compressed, bit 1 = encrypted, bit 2 = ONNX payload)
 *   [16..47] 32 bytes : SHA-256 checksum of unencrypted payload
 *   [48..48+N]        : UTF-8 JSON Metadata Header
 *   [48+N..]          : Zlib-compressed ONNX graph bytes
 */
data class ShortlinkDetection(
    val id: Int,
    val label: String,
    val confidence: Float,
    val boxPx: RectF,
    val boxNorm: RectF
)

data class VFDetectionResult(
    val count: Int,
    val inferMs: Float,
    val detections: List<ShortlinkDetection>,
    val metadataJson: String,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0
)

class VFModelLoader {
    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var ortSession: OrtSession? = null
    var metadataJson: String = "{}"
        private set
    val inputSize: Int = 640

    /**
     * Converts any GitHub blob URL into a direct raw binary download URL.
     */
    fun normalizeGithubUrl(url: String): String {
        var clean = url.trim()
        if (clean.contains("github.com/") && clean.contains("/blob/")) {
            clean = clean.replace("https://github.com/", "https://raw.githubusercontent.com/")
                .replace("http://github.com/", "https://raw.githubusercontent.com/")
                .replace("/blob/", "/")
        }
        return clean
    }

    /**
     * Downloads a `.vf` model file from GitHub and parses the embedded ONNX graph in RAM.
     */
    fun loadFromGithubUrl(githubUrl: String) {
        val rawUrl = normalizeGithubUrl(githubUrl)
        val conn = URL(rawUrl).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 30000
        conn.readTimeout = 45000
        conn.setRequestProperty("User-Agent", "VerifyGN-VCount-Android/1.0")
        conn.connect()

        if (conn.responseCode != HttpURLConnection.HTTP_OK) {
            throw IllegalStateException("GitHub download failed with HTTP ${conn.responseCode}")
        }
        val vfBytes = conn.inputStream.use { it.readBytes() }
        loadFromVfBytes(vfBytes)
    }

    /**
     * Parses a `.vf` container (`VFGN26\x01\x00`), decompresses the zlib payload, and initializes ONNX Runtime.
     */
    fun loadFromVfBytes(vfBytes: ByteArray) {
        require(vfBytes.size >= 48) { "Invalid .vf file: smaller than 48-byte VFGN26 header." }
        val magic = String(vfBytes, 0, 6, Charsets.US_ASCII)
        require(magic == "VFGN26") { "Invalid .vf magic header: expected VFGN26, got $magic" }

        val bb = ByteBuffer.wrap(vfBytes, 8, 8).order(ByteOrder.LITTLE_ENDIAN)
        val headerLen = bb.int
        val flags = bb.int

        val flagCompressed = (flags and 1) != 0
        val flagOnnx = (flags and 4) != 0

        metadataJson = String(vfBytes, 48, headerLen, Charsets.UTF_8)
        val payloadOffset = 48 + headerLen
        val compressedPayload = vfBytes.copyOfRange(payloadOffset, vfBytes.size)

        val rawPayload = if (flagCompressed) {
            zlibDecompress(compressedPayload)
        } else {
            compressedPayload
        }

        require(flagOnnx || rawPayload.isNotEmpty()) {
            "This .vf contains PyTorch training weights. For standalone offline Android execution, upload best_fast.vf (ONNX payload) or connect to the V-Count FastAPI server."
        }

        val sessionOptions = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        ortSession?.close()
        ortSession = ortEnv.createSession(rawPayload, sessionOptions)
    }

    private fun zlibDecompress(data: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(data)
        val out = ByteArrayOutputStream(data.size * 2)
        val buf = ByteArray(64 * 1024)
        while (!inflater.finished()) {
            val count = inflater.inflate(buf)
            if (count == 0 && inflater.needsInput()) break
            out.write(buf, 0, count)
        }
        inflater.end()
        return out.toByteArray()
    }

    /**
     * Runs 640x640 resize preprocessing, ONNX Runtime inference, and Non-Maximum Suppression (NMS).
     */
    fun detectAndCount(
        rgbFloatCHW: FloatArray,
        origWidth: Int,
        origHeight: Int,
        confThreshold: Float = 0.25f,
        iouThreshold: Float = 0.45f
    ): VFDetectionResult {
        val session = ortSession ?: throw IllegalStateException("Model not loaded yet.")
        val t0 = System.nanoTime()

        val shape = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
        val tensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(rgbFloatCHW), shape)

        val inputName = session.inputNames.iterator().next()
        val rawCandidates = mutableListOf<ShortlinkDetection>()
        val scaleX = origWidth.toFloat() / inputSize
        val scaleY = origHeight.toFloat() / inputSize

        tensor.use {
            session.run(Collections.singletonMap(inputName, tensor)).use { results ->
                @Suppress("UNCHECKED_CAST")
                val output = results[0].value as Array<Array<FloatArray>>
                val preds = output[0] // [8400, 5] -> [x1, y1, x2, y2, score]

                for (i in preds.indices) {
                    val row = preds[i]
                    val score = row[4]
                    if (score >= confThreshold) {
                        val x1 = (row[0] * scaleX).coerceIn(0f, origWidth.toFloat())
                        val y1 = (row[1] * scaleY).coerceIn(0f, origHeight.toFloat())
                        val x2 = (row[2] * scaleX).coerceIn(0f, origWidth.toFloat())
                        val y2 = (row[3] * scaleY).coerceIn(0f, origHeight.toFloat())
                        val bw = x2 - x1
                        val bh = y2 - y1
                        val maxAllowedW = origWidth * 0.35f
                        val maxAllowedH = origHeight * 0.35f
                        if (bw > 8f && bh > 8f && bw < maxAllowedW && bh < maxAllowedH) {
                            rawCandidates.add(
                                ShortlinkDetection(
                                    id = 0,
                                    label = "shortlink",
                                    confidence = score,
                                    boxPx = RectF(x1, y1, x2, y2),
                                    boxNorm = RectF(
                                        x1 / origWidth,
                                        y1 / origHeight,
                                        x2 / origWidth,
                                        y2 / origHeight
                                    )
                                )
                            )
                        }
                    }
                }
            }
        }

        val kept = nms(rawCandidates, iouThreshold)
            .sortedWith(compareBy({ (it.boxPx.top / 35f).toInt() }, { it.boxPx.left }))
            .mapIndexed { idx, det -> det.copy(id = idx + 1) }

        val inferMs = (System.nanoTime() - t0) / 1_000_000f
        return VFDetectionResult(
            count = kept.size,
            inferMs = inferMs,
            detections = kept,
            metadataJson = metadataJson,
            frameWidth = origWidth,
            frameHeight = origHeight
        )
    }

    private fun nms(boxes: List<ShortlinkDetection>, iouThreshold: Float): List<ShortlinkDetection> {
        val sorted = boxes.sortedByDescending { it.confidence }
        val selected = mutableListOf<ShortlinkDetection>()
        for (cand in sorted) {
            var keep = true
            for (sel in selected) {
                val boxIou = computeIoU(cand.boxPx, sel.boxPx)
                val cdist = kotlin.math.hypot(
                    cand.boxPx.centerX() - sel.boxPx.centerX(),
                    cand.boxPx.centerY() - sel.boxPx.centerY()
                )
                val minDim = min(
                    min(cand.boxPx.width(), cand.boxPx.height()),
                    min(sel.boxPx.width(), sel.boxPx.height())
                )
                if (boxIou > 0.72f || (boxIou > iouThreshold && cdist < minDim * 0.26f)) {
                    keep = false
                    break
                }
            }
            if (keep) {
                selected.add(cand)
                if (selected.size >= 300) break
            }
        }
        return selected
    }

    private fun computeIoU(a: RectF, b: RectF): Float {
        val ix1 = max(a.left, b.left)
        val iy1 = max(a.top, b.top)
        val ix2 = min(a.right, b.right)
        val iy2 = min(a.bottom, b.bottom)
        val iw = max(0f, ix2 - ix1)
        val ih = max(0f, iy2 - iy1)
        val inter = iw * ih
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        return inter / (areaA + areaB - inter + 1e-6f)
    }
}
