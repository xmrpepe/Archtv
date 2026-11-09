package dev.archdock.installer

import java.io.File

/**
 * One marker file per completed installer step. Steps are idempotent, so an
 * interrupted install simply resumes from the first step without a marker.
 */
class CheckpointStore(private val dir: File) {
    private fun marker(id: String) = File(dir, "step-$id.done")

    fun isDone(id: String) = marker(id).exists()
    fun markDone(id: String) { dir.mkdirs(); marker(id).writeText(System.currentTimeMillis().toString()) }
    fun clear(id: String) { marker(id).delete() }
    fun clearAll() { dir.listFiles { f -> f.name.startsWith("step-") }?.forEach { it.delete() } }
    fun completed(): Set<String> =
        dir.listFiles { f -> f.name.startsWith("step-") && f.name.endsWith(".done") }
            ?.map { it.name.removePrefix("step-").removeSuffix(".done") }?.toSet() ?: emptySet()
}
