package io.github.johnrocky.hfmodels.catalog

import io.github.johnrocky.hfmodels.DescriptorOrigin
import io.github.johnrocky.hfmodels.ErrorCode
import io.github.johnrocky.hfmodels.ModelException
import io.github.johnrocky.hfmodels.descriptor.Descriptor
import org.json.JSONException
import org.json.JSONObject

/**
 * A catalog entry: an external descriptor for a repo whose owner did not add `hfmodels.json`,
 * pinned to one model commit. The catalog's own identity (repo / commit / path) is the
 * [DescriptorOrigin]; the model files are read from [modelCommit] (spec §6.2 / §8.1).
 */
data class CatalogEntry(
    val modelId: String,
    val modelCommit: String,
    val descriptorJson: String,
    val descriptor: Descriptor,
    val origin: DescriptorOrigin,
    val provenance: String?,
)

/**
 * A static JSON catalog (`catalog.json`): `{ "schema_version": 1, "catalog_id": ..., "origin": {repo, commit},
 * "entries": [ { "model_id", "model_commit", "path", "provenance", "descriptor": {...} } ] }`.
 * The bundled one lives in the SDK's assets; a later `refreshCatalog()` reads the same shape from a URL.
 */
class Catalog(val id: String, val entries: List<CatalogEntry>) {
    fun find(modelId: String, modelCommit: String): CatalogEntry? =
        entries.firstOrNull { it.modelId == modelId && it.modelCommit == modelCommit }

    /** The entry a revision-less load falls back to when the branch head carries no descriptor (§6.3). */
    fun defaultBinding(modelId: String): CatalogEntry? = entries.firstOrNull { it.modelId == modelId }

    companion object {
        const val SCHEMA_VERSION = 1

        @Throws(ModelException::class)
        fun parse(text: String): Catalog {
            val o = try { JSONObject(text) } catch (e: JSONException) { throw ModelException(ErrorCode.MANIFEST_INVALID, "catalog is not JSON: ${e.message}") }
            if (o.optInt("schema_version", -1) != SCHEMA_VERSION) throw ModelException(ErrorCode.UNSUPPORTED_SCHEMA, "catalog schema_version ${o.opt("schema_version")} is not supported")
            val id = o.optString("catalog_id", "catalog")
            val origin = o.optJSONObject("origin") ?: throw ModelException(ErrorCode.MANIFEST_INVALID, "catalog has no origin")
            val originRepo = origin.optString("repo", "")
            val originCommit = origin.optString("commit", "")
            if (originRepo.isEmpty() || originCommit.isEmpty()) throw ModelException(ErrorCode.MANIFEST_INVALID, "catalog origin needs repo and commit")
            val arr = o.optJSONArray("entries") ?: throw ModelException(ErrorCode.MANIFEST_INVALID, "catalog has no entries")
            val entries = ArrayList<CatalogEntry>()
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: throw ModelException(ErrorCode.MANIFEST_INVALID, "catalog entry $i is not an object")
                val modelId = e.optString("model_id", "")
                val commit = e.optString("model_commit", "")
                if (modelId.isEmpty() || commit.length != 40) throw ModelException(ErrorCode.MANIFEST_INVALID, "catalog entry $i needs model_id and a 40-hex model_commit")
                val d = e.optJSONObject("descriptor") ?: throw ModelException(ErrorCode.MANIFEST_INVALID, "catalog entry $modelId has no descriptor")
                val text = d.toString()
                val descriptor = Descriptor.parse(text, modelIdExpected = modelId)
                val path = e.optString("path", "catalog/$modelId.json")
                entries += CatalogEntry(modelId, commit, text, descriptor, DescriptorOrigin(originRepo, originCommit, path), e.optString("provenance").takeIf { it.isNotEmpty() })
            }
            return Catalog(id, entries)
        }
    }
}
