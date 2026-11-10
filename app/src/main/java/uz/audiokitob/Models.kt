package uz.audiokitob

import org.json.JSONObject

enum class JobState { NEW, PREPARING, QUEUED, RUNNING, WAITING, PAUSED, LIMIT, DONE, FAILED }

/** Bitta kitob/matn uchun ish. Holati job.json faylida saqlanadi. */
data class Job(
    val id: String,
    var title: String,
    var srcName: String = "",
    var state: JobState = JobState.NEW,
    var message: String = "",
    var total: Int = 0,
    var offset: Int = 0,
    var audioMs: Long = 0L,
    var audioBytes: Long = 0L,
    var chunksDone: Int = 0,
    var skipped: Int = 0,
    var lastSnippet: String = "",
    var posLabel: String = "",
    var preview: String = "",
    var format: String = "mp3",
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("title", title)
        o.put("srcName", srcName)
        o.put("state", state.name)
        o.put("message", message)
        o.put("total", total)
        o.put("offset", offset)
        o.put("audioMs", audioMs)
        o.put("audioBytes", audioBytes)
        o.put("chunksDone", chunksDone)
        o.put("skipped", skipped)
        o.put("lastSnippet", lastSnippet)
        o.put("posLabel", posLabel)
        o.put("preview", preview)
        o.put("format", format)
        o.put("createdAt", createdAt)
        o.put("updatedAt", updatedAt)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): Job {
            val st = try {
                JobState.valueOf(o.optString("state", "NEW"))
            } catch (e: Exception) {
                JobState.NEW
            }
            return Job(
                id = o.getString("id"),
                title = o.optString("title", "Audio"),
                srcName = o.optString("srcName", ""),
                state = st,
                message = o.optString("message", ""),
                total = o.optInt("total", 0),
                offset = o.optInt("offset", 0),
                audioMs = o.optLong("audioMs", 0L),
                audioBytes = o.optLong("audioBytes", 0L),
                chunksDone = o.optInt("chunksDone", 0),
                skipped = o.optInt("skipped", 0),
                lastSnippet = o.optString("lastSnippet", ""),
                posLabel = o.optString("posLabel", ""),
                preview = o.optString("preview", ""),
                format = o.optString("format", "mp3"),
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
            )
        }
    }
}
