package io.github.johnrocky.hfmodels.litertlm

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The parts of a `.litertlm` bundle's header the SDK reads before the runtime opens it: the section
 * table and, from the `LlmMetadataProto` section, the declared channels and the model-turn prompt
 * affixes. Only the header (the first 16 KiB block) and that one section are read, so this costs
 * two small reads on a multi-gigabyte file and never touches the weights.
 *
 * Layout (LiteRT-LM `schema/core/litertlm_header_schema.fbs`, v0.16.1): 8 magic bytes `LITERTLM`,
 * a 64-bit little-endian header end offset at byte 24, a FlatBuffer `LiteRTLMMetaData` root at
 * byte 32 whose `section_metadata.objects[]` carry `begin_offset` / `end_offset` / `data_type`
 * per section. The metadata section is a serialized `litert.lm.proto.LlmMetadata`.
 */
internal class LitertlmBundle(
    val sections: List<Section>,
    val channels: List<BundleChannel>,
    /** `prompt_templates.model.prefix`: the model-turn opener of a structured template ("" on the jinja path). */
    val modelPrefix: String,
    val modelSuffix: String,
    /** `jinja_prompt_template`, or null when the bundle uses the structured affixes. */
    val jinjaTemplate: String?,
) {
    data class Section(val dataType: Int, val begin: Long, val end: Long) {
        val typeName: String get() = SECTION_TYPES.getOrNull(dataType) ?: "type$dataType"
    }

    data class BundleChannel(val name: String, val start: String, val end: String, val isReasoning: Boolean?)

    companion object {
        private val MAGIC = "LITERTLM".toByteArray(Charsets.US_ASCII)
        private const val HEADER_END_LOCATION = 24
        private const val HEADER_BEGIN = 32
        const val SECTION_LLM_METADATA = 5
        private val SECTION_TYPES = listOf("NONE", "GenericBinaryData", "Deprecated", "TFLiteModel", "SP_Tokenizer", "LlmMetadataProto", "HF_Tokenizer_Zlib", "TFLiteWeights", "EmbeddingMetadataProto", "ExecutorMetadataProto")
        private const val MAX_HEADER = 64L * 1024 * 1024
        private const val MAX_METADATA = 64L * 1024 * 1024

        @Throws(IOException::class)
        fun read(file: File): LitertlmBundle = RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(HEADER_BEGIN)
            raf.readFully(head)
            if (!head.copyOf(MAGIC.size).contentEquals(MAGIC)) throw IOException("${file.name}: not a .litertlm (bad magic)")
            val headerEnd = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getLong(HEADER_END_LOCATION)
            if (headerEnd <= HEADER_BEGIN || headerEnd > MAX_HEADER) throw IOException("${file.name}: header end $headerEnd out of range")
            val header = ByteArray((headerEnd - HEADER_BEGIN).toInt())
            raf.seek(HEADER_BEGIN.toLong()); raf.readFully(header)
            val sections = parseSections(header)
            val meta = sections.firstOrNull { it.dataType == SECTION_LLM_METADATA }
            if (meta == null) return LitertlmBundle(sections, emptyList(), "", "", null)
            val len = meta.end - meta.begin
            if (len < 0 || len > MAX_METADATA) throw IOException("${file.name}: LlmMetadata section size $len out of range")
            if (meta.end > raf.length()) throw IOException("${file.name}: LlmMetadata section ends at ${meta.end}, file is ${raf.length()} bytes")
            val bytes = ByteArray(len.toInt())
            raf.seek(meta.begin); raf.readFully(bytes)
            parseLlmMetadata(sections, bytes)
        }

        // ---- FlatBuffer: LiteRTLMMetaData { system_metadata; section_metadata { objects: [SectionObject { items; begin_offset; end_offset; data_type }] } }
        private fun parseSections(buf: ByteArray): List<Section> {
            val b = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
            fun u32(pos: Int): Long = b.getInt(pos).toLong() and 0xFFFFFFFFL
            fun u16(pos: Int): Int = b.getShort(pos).toInt() and 0xFFFF
            fun field(table: Int, index: Int): Int { // absolute position of a field, or -1
                val vtable = table - b.getInt(table)
                val vsize = u16(vtable)
                val slot = 4 + index * 2
                if (slot + 2 > vsize) return -1
                val off = u16(vtable + slot)
                return if (off == 0) -1 else table + off
            }
            fun tableAt(pos: Int): Int = pos + u32(pos).toInt()
            val root = u32(0).toInt()
            val sectionMetaPos = field(root, 1).takeIf { it >= 0 } ?: throw IOException("header has no section_metadata")
            val sectionMeta = tableAt(sectionMetaPos)
            val objectsPos = field(sectionMeta, 0).takeIf { it >= 0 } ?: throw IOException("section_metadata has no objects")
            val vec = tableAt(objectsPos)
            val n = u32(vec).toInt()
            if (n < 0 || n > 4096) throw IOException("section count $n out of range")
            return List(n) { i ->
                val obj = tableAt(vec + 4 + i * 4)
                val begin = field(obj, 1).let { if (it >= 0) b.getLong(it) else 0L }
                val end = field(obj, 2).let { if (it >= 0) b.getLong(it) else 0L }
                val type = field(obj, 3).let { if (it >= 0) buf[it].toInt() and 0xFF else 0 }
                Section(type, begin, end)
            }
        }

        // ---- protobuf: LlmMetadata { prompt_templates = 3 { model = 2 { prefix = 1, suffix = 2 } }, jinja_prompt_template = 7, channels = 8 { channel_name = 1, start = 2, end = 3, is_reasoning_channel = 4 } }
        private fun parseLlmMetadata(sections: List<Section>, bytes: ByteArray): LitertlmBundle {
            var prefix = ""; var suffix = ""; var jinja: String? = null
            val channels = ArrayList<BundleChannel>()
            forEachField(bytes, 0, bytes.size) { num, wire, r ->
                when {
                    num == 3 && wire == 2 -> forEachField(bytes, r.start, r.end) { n2, w2, r2 ->
                        if (n2 == 2 && w2 == 2) forEachField(bytes, r2.start, r2.end) { n3, w3, r3 ->
                            if (w3 == 2) when (n3) { 1 -> prefix = r3.string(bytes); 2 -> suffix = r3.string(bytes) }
                        }
                    }
                    num == 7 && wire == 2 -> jinja = r.string(bytes)
                    num == 8 && wire == 2 -> {
                        var name = ""; var start = ""; var end = ""; var reasoning: Boolean? = null
                        forEachField(bytes, r.start, r.end) { n2, w2, r2 ->
                            when {
                                n2 == 1 && w2 == 2 -> name = r2.string(bytes)
                                n2 == 2 && w2 == 2 -> start = r2.string(bytes)
                                n2 == 3 && w2 == 2 -> end = r2.string(bytes)
                                n2 == 4 && w2 == 0 -> reasoning = r2.varint != 0L
                            }
                        }
                        channels += BundleChannel(name, start, end, reasoning)
                    }
                }
            }
            return LitertlmBundle(sections, channels, prefix, suffix, jinja?.takeIf { it.isNotEmpty() })
        }

        private class Range(val start: Int, val end: Int, val varint: Long) {
            fun string(bytes: ByteArray) = String(bytes, start, end - start, Charsets.UTF_8)
        }

        /** Walks one message's fields in [from, to); `body` gets (field number, wire type, payload range or varint). */
        private fun forEachField(bytes: ByteArray, from: Int, to: Int, body: (Int, Int, Range) -> Unit) {
            var p = from
            fun varint(): Long {
                var shift = 0; var v = 0L
                while (true) {
                    if (p >= to) throw IOException("truncated varint in LlmMetadata")
                    val x = bytes[p++].toInt() and 0xFF
                    v = v or ((x and 0x7F).toLong() shl shift)
                    if (x and 0x80 == 0) return v
                    shift += 7
                    if (shift > 63) throw IOException("varint too long in LlmMetadata")
                }
            }
            while (p < to) {
                val tag = varint()
                val num = (tag ushr 3).toInt(); val wire = (tag and 7).toInt()
                when (wire) {
                    0 -> body(num, wire, Range(p, p, varint()))
                    1 -> { body(num, wire, Range(p, p + 8, 0)); p += 8 }
                    2 -> { val len = varint().toInt(); if (len < 0 || p + len > to) throw IOException("truncated field $num in LlmMetadata"); body(num, wire, Range(p, p + len, 0)); p += len }
                    5 -> { body(num, wire, Range(p, p + 4, 0)); p += 4 }
                    else -> throw IOException("unsupported wire type $wire in LlmMetadata")
                }
            }
        }
    }
}
