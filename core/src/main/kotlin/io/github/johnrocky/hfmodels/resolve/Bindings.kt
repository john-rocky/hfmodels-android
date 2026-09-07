package io.github.johnrocky.hfmodels.resolve

import io.github.johnrocky.hfmodels.store.FlatJson
import java.io.File

/**
 * The saved binding of a revision-less id to an immutable model commit (spec §7): written after the
 * first successful `prepare`, read on every later load so the model does not move under the app.
 * Layout: `<root>/bindings/<owner>__<name>.json`.
 */
internal class Bindings(private val root: File) {
    data class Binding(val repoId: String, val commit: String, val descriptorSha256: String, val descriptorOriginRepo: String, val descriptorOriginCommit: String, val descriptorOriginPath: String, val boundAt: String)

    private fun file(repoId: String) = File(File(root, "bindings"), repoId.replace("/", "__") + ".json")

    fun read(repoId: String): Binding? {
        val f = file(repoId)
        if (!f.isFile) return null
        val m = FlatJson.read(f.readText())
        val commit = m["commit"] ?: return null
        return Binding(repoId, commit, m["descriptor_sha256"] ?: "", m["descriptor_origin_repo"] ?: "", m["descriptor_origin_commit"] ?: "", m["descriptor_origin_path"] ?: "", m["bound_at"] ?: "")
    }

    fun write(b: Binding) {
        val f = file(b.repoId)
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeText(FlatJson.write(mapOf(
            "repo_id" to b.repoId, "commit" to b.commit, "descriptor_sha256" to b.descriptorSha256,
            "descriptor_origin_repo" to b.descriptorOriginRepo, "descriptor_origin_commit" to b.descriptorOriginCommit,
            "descriptor_origin_path" to b.descriptorOriginPath, "bound_at" to b.boundAt,
        )))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    fun remove(repoId: String): Boolean = file(repoId).delete()
}

/** Descriptor texts cached by (repo, commit) so an Offline load can re-plan without the Hub. */
internal class DescriptorCache(private val root: File) {
    private fun file(repoId: String, commit: String) = File(File(File(root, "descriptors"), repoId.replace("/", "__")), "$commit.json")

    fun read(repoId: String, commit: String): String? = file(repoId, commit).takeIf { it.isFile }?.readText()

    fun write(repoId: String, commit: String, text: String) {
        val f = file(repoId, commit)
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }
}
