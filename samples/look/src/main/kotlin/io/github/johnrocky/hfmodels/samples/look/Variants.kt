package io.github.johnrocky.hfmodels.samples.look

/**
 * Which file of litert-community/MiniCPM-V-4.7-1B a phone loads. The descriptor's profiles say nothing about
 * memory, so the app chooses by the phone's RAM: `vl_int8` (fp16 vision encoder, 2.21 GB; the card measured it
 * on a 12 GB-class phone) from 10 GiB up, `vl_int8vis` (int8 vision encoder, 1.70 GB, CPU only; made for 8 GB
 * phones such as the Pixel 8a) below.
 */
object Variants {
    const val VL_INT8 = "vl_int8"
    const val VL_INT8VIS = "vl_int8vis"
    const val LARGE_PHONE_BYTES = 10L shl 30

    fun forMemory(totalMemBytes: Long): String = if (totalMemBytes >= LARGE_PHONE_BYTES) VL_INT8 else VL_INT8VIS
}
