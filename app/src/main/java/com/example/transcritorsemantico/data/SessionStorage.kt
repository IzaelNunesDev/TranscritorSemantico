package com.example.transcritorsemantico.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

class SessionStorage(context: Context) {

    private val gson = Gson()
    private val storeDir = File(context.filesDir, "memorywave")
    private val storeFile = File(storeDir, "sessions.json")

    init {
        if (!storeDir.exists()) {
            storeDir.mkdirs()
        }
    }

    fun loadSessions(): List<AudioSession> {
        if (!storeFile.exists()) {
            return emptyList()
        }

        return runCatching {
            val root = JsonParser.parseString(storeFile.readText())
            if (!root.isJsonArray) {
                return@runCatching emptyList()
            }
            root.asJsonArray.mapNotNull(::audioSessionFromJson)
        }.getOrDefault(emptyList())
    }

    fun saveSessions(sessions: List<AudioSession>) {
        storeFile.writeText(gson.toJson(sessions))
    }

    private fun audioSessionFromJson(element: com.google.gson.JsonElement): AudioSession? {
        if (!element.isJsonObject) return null
        val obj = element.asJsonObject
        val id = obj.stringOr("id", "")
        if (id.isBlank()) return null

        return AudioSession(
            id = id,
            title = obj.stringOr("title", "Sessão sem título"),
            createdAt = obj.longOr("createdAt", 0L),
            updatedAt = obj.longOr("updatedAt", obj.longOr("createdAt", System.currentTimeMillis())),
            durationMs = obj.longOr("durationMs", 0L),
            audioPath = obj.nullableString("audioPath"),
            sourceType = obj.stringOr("sourceType", "recording"),
            status = obj.stringOr("status", "indexed"),
            transcriptionEngine = obj.stringOr("transcriptionEngine", "android_system"),
            note = obj.nullableString("note"),
            chunks = obj.chunksOrEmpty(),
            liteRtTranscript = obj.variantOrNull("liteRtTranscript"),
            legacyTurboTranscript = obj.variantOrNull("legacyTurboTranscript"),
        )
    }

    private fun JsonObject.chunksOrEmpty(): List<TranscriptChunk> {
        val array = get("chunks") as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj.stringOr("id", "")
            val text = obj.stringOr("text", "").trim()
            if (id.isBlank() || text.isBlank()) return@mapNotNull null
            TranscriptChunk(
                id = id,
                startMs = obj.longOr("startMs", 0L),
                endMs = obj.longOr("endMs", obj.longOr("startMs", 0L)),
                text = text,
                packedEmbedding = obj.stringOr("packedEmbedding", ""),
            )
        }
    }

    private fun JsonObject.variantOrNull(key: String): TranscriptVariant? {
        val obj = get(key) as? JsonObject ?: return null
        val engineId = obj.stringOr("engineId", "")
        val label = obj.stringOr("label", "")
        if (engineId.isBlank() || label.isBlank()) return null
        return TranscriptVariant(
            engineId = engineId,
            label = label,
            createdAt = obj.longOr("createdAt", 0L),
            speechWindowCount = obj.intOr("speechWindowCount", 0),
            speechSeconds = obj.floatOr("speechSeconds", 0f),
            totalSeconds = obj.floatOr("totalSeconds", 0f),
            text = obj.stringOr("text", ""),
            chunks = obj.chunksOrEmpty(),
        )
    }

    private fun JsonObject.stringOr(key: String, fallback: String): String {
        val value = get(key)
        if (value == null || value.isJsonNull) return fallback
        return runCatching { value.asString }
            .getOrDefault(fallback)
            .ifBlank { fallback }
    }

    private fun JsonObject.nullableString(key: String): String? {
        val value = get(key)
        if (value == null || value.isJsonNull) return null
        return runCatching { value.asString }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.longOr(key: String, fallback: Long): Long {
        val value = get(key)
        if (value == null || value.isJsonNull) return fallback
        return runCatching { value.asLong }.getOrDefault(fallback)
    }

    private fun JsonObject.intOr(key: String, fallback: Int): Int {
        val value = get(key)
        if (value == null || value.isJsonNull) return fallback
        return runCatching { value.asInt }.getOrDefault(fallback)
    }

    private fun JsonObject.floatOr(key: String, fallback: Float): Float {
        val value = get(key)
        if (value == null || value.isJsonNull) return fallback
        return runCatching { value.asFloat }.getOrDefault(fallback)
    }
}
