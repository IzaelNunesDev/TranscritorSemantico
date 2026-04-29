package com.example.transcritorsemantico.ui

import android.app.Application
import android.content.ContentResolver
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.transcritorsemantico.audio.TranscriptionEngine
import com.example.transcritorsemantico.audio.RecorderCallbacks
import com.example.transcritorsemantico.audio.RecorderEngine
import com.example.transcritorsemantico.data.AudioSession
import com.example.transcritorsemantico.data.SearchHit
import com.example.transcritorsemantico.data.SessionStorage
import com.example.transcritorsemantico.data.TranscriptChunk
import com.example.transcritorsemantico.litert.LiteRtModelManager
import com.example.transcritorsemantico.litert.WhisperLiteRtTranscriber
import com.example.transcritorsemantico.semantic.LocalSemanticEngine
import com.example.transcritorsemantico.transcription.BatchTranscriptionResult
import com.example.transcritorsemantico.whisper.WhisperFileTranscriber
import com.example.transcritorsemantico.whisper.WhisperModel
import com.example.transcritorsemantico.whisper.WhisperModelManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class UiState(
    val sessions: List<AudioSession> = emptyList(),
    val rankedSessionIds: List<String> = emptyList(),
    val searchQuery: String = "",
    val searchHits: List<SearchHit> = emptyList(),
    val isRecording: Boolean = false,
    val elapsedMs: Long = 0L,
    val micLevel: Float = 0f,
    val partialText: String = "",
    val draftChunks: List<TranscriptChunk> = emptyList(),
    val indexedChunkCount: Int = 0,
    val lastIndexedPreview: String = "",
    val indexingStatus: String = "Aguardando captura.",
    val selectedEngine: TranscriptionEngine = TranscriptionEngine.ANDROID_SYSTEM,
    val selectedWhisperModel: WhisperModel = WhisperModel.BASE,
    val availableWhisperModelIds: List<String> = emptyList(),
    val liteRtModelReady: Boolean = false,
    val whisperBusySessionId: String? = null,
    val whisperQueueCount: Int = 0,
    val whisperStatus: String = "Whisper.cpp pronto para ser configurado.",
    val currentAudioPath: String? = null,
    val message: String? = null,
    val selectedSessionId: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val storage = SessionStorage(application)
    private val recorder = RecorderEngine(application)
    private val semanticEngine = LocalSemanticEngine()
    private val whisperModelManager = WhisperModelManager(application)
    private val liteRtModelManager = LiteRtModelManager(application)
    private val liteRtTranscriber = WhisperLiteRtTranscriber(application)
    private val whisperTranscriber = WhisperFileTranscriber(application)

    private var timerJob: Job? = null
    private var activeWhisperJob: Job? = null
    private var recordingStartedAt = 0L
    private var lastChunkEndsAt = 0L
    private val pendingWhisperQueue = ArrayDeque<String>()

    var uiState by mutableStateOf(UiState())
        private set

    init {
        val rawSessions = storage.loadSessions()
        // Reset stuck sessions (transcribing/queued) to needs_transcription
        val cleanedSessions = rawSessions.map { session ->
            if (session.status == "transcribing" || session.status == "queued") {
                session.copy(
                    status = "needs_transcription",
                    note = "Transcrição interrompida (app reiniciado). Clique para tentar novamente."
                )
            } else {
                session
            }
        }.sortedByDescending { it.updatedAt }

        if (cleanedSessions.isNotEmpty()) {
            storage.saveSessions(cleanedSessions)
        }

        uiState = uiState.copy(
            sessions = cleanedSessions,
            rankedSessionIds = cleanedSessions.map { it.id },
            availableWhisperModelIds = whisperModelManager.installedModelIds(),
            liteRtModelReady = liteRtModelManager.hasQuickTranscriptionModel(),
            whisperStatus = if (liteRtModelManager.hasQuickTranscriptionModel()) {
                "LiteRT Batch pronto (Otimizado). Whisper.cpp fica como fallback."
            } else {
                "Whisper.cpp pronto (Legado). Importe um modelo LiteRT para mais velocidade."
            },
        )
    }

    fun updateSearchQuery(query: String) {
        val hits = semanticEngine.search(query, uiState.sessions)
        val rankedIds = if (query.isBlank()) {
            uiState.sessions.sortedByDescending { it.updatedAt }.map { it.id }
        } else {
            rankSessionsFromHits(hits, uiState.sessions)
        }
        uiState = uiState.copy(
            searchQuery = query,
            searchHits = hits,
            rankedSessionIds = rankedIds,
        )
    }

    fun dismissMessage() {
        uiState = uiState.copy(message = null)
    }

    fun selectSession(sessionId: String?) {
        uiState = uiState.copy(selectedSessionId = sessionId)
    }

    fun setTranscriptionEngine(engine: TranscriptionEngine) {
        uiState = uiState.copy(
            selectedEngine = engine,
            message = "Motor selecionado: ${engine.label}.",
        )
    }

    fun setWhisperModel(model: WhisperModel) {
        uiState = uiState.copy(
            selectedWhisperModel = model,
            whisperStatus = if (uiState.availableWhisperModelIds.contains(model.id)) {
                "Modelo ${model.title} já está pronto."
            } else {
                "Modelo ${model.title} ainda não foi baixado."
            },
        )
    }

    fun startRecording(languageTag: String = "pt-BR") {
        if (uiState.isRecording) return

        val chosenEngine = uiState.selectedEngine
        val startResult = recorder.start(languageTag, chosenEngine, object : RecorderCallbacks {
            override fun onPartialText(text: String) {
                uiState = uiState.copy(
                    partialText = text,
                    indexingStatus = if (text.isNotBlank()) {
                        "Ouvindo e preparando o proximo trecho para indexacao."
                    } else {
                        uiState.indexingStatus
                    },
                )
            }

            override fun onFinalText(text: String, timestampMs: Long) {
                val cleanText = text.trim()
                if (cleanText.isBlank()) return
                val startMs = if (uiState.draftChunks.isEmpty()) 0L else lastChunkEndsAt
                val endMs = timestampMs.coerceAtLeast(startMs + 1_000L)
                lastChunkEndsAt = endMs
                val newChunk = semanticEngine.chunkFromText(cleanText, startMs, endMs)
                uiState = uiState.copy(
                    draftChunks = uiState.draftChunks + newChunk,
                    indexedChunkCount = uiState.indexedChunkCount + 1,
                    lastIndexedPreview = cleanText,
                    indexingStatus = "TurboMSE indexou o trecho ${uiState.indexedChunkCount + 1}.",
                    partialText = "",
                )
            }

            override fun onLevelChanged(level: Float) {
                uiState = uiState.copy(micLevel = level)
            }

            override fun onError(message: String) {
                uiState = uiState.copy(message = message)
            }
        })

        recordingStartedAt = startResult.startedAt
        lastChunkEndsAt = 0L
        uiState = uiState.copy(
            isRecording = true,
            elapsedMs = 0L,
            micLevel = 0f,
            partialText = "",
            draftChunks = emptyList(),
            indexedChunkCount = 0,
            lastIndexedPreview = "",
            indexingStatus = if (startResult.transcriptionActive) {
                "Motor de captura iniciado com ${chosenEngine.label}. Aguardando fala para transcrever e indexar."
            } else {
                "Gravando apenas audio. Esta sessao ainda nao sera pesquisavel."
            },
            currentAudioPath = startResult.audioPath,
            message = if (startResult.transcriptionActive) {
                "Motor local ativo com ${chosenEngine.label}."
            } else {
                "Audio-only ativo. O TurboMSE so entra depois que existir texto transcrito."
            },
        )

        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (uiState.isRecording) {
                uiState = uiState.copy(
                    elapsedMs = System.currentTimeMillis() - recordingStartedAt,
                    micLevel = maxOf(uiState.micLevel * 0.55f, recorder.currentLevel()),
                )
                delay(500L)
            }
        }
    }

    fun stopRecording() {
        if (!uiState.isRecording) return
        recorder.stop()
        timerJob?.cancel()

        val chunks = uiState.draftChunks
        val now = System.currentTimeMillis()
        val title = semanticEngine.suggestTitle(chunks).ifBlank {
            "Sessao ${SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(now))}"
        }
        val note = when {
            chunks.isEmpty() -> "Audio salvo. Nenhum trecho reconhecido pelo motor de fala."
            else -> "${chunks.size} trechos indexados com embeddings locais quantizados."
        }

        val newSession = AudioSession(
            id = "session_$now",
            title = title,
            createdAt = now,
            updatedAt = now,
            durationMs = uiState.elapsedMs,
            audioPath = uiState.currentAudioPath,
            sourceType = "recording",
            status = if (chunks.isEmpty()) "needs_transcription" else "indexed",
            transcriptionEngine = uiState.selectedEngine.id,
            note = note,
            chunks = chunks,
        )

        val updatedSessions = (listOf(newSession) + uiState.sessions).sortedByDescending { it.updatedAt }
        storage.saveSessions(updatedSessions)
        uiState = uiState.copy(
            sessions = updatedSessions,
            isRecording = false,
            elapsedMs = 0L,
            micLevel = 0f,
            partialText = "",
            draftChunks = emptyList(),
            indexedChunkCount = 0,
            lastIndexedPreview = "",
            indexingStatus = "Aguardando captura.",
            currentAudioPath = null,
            message = if (chunks.isEmpty()) {
                "Sessao salva. O audio foi guardado, mas ainda nao virou texto pesquisavel."
            } else {
                "Sessao salva e pronta para busca semantica."
            },
        )
        updateSearchQuery(uiState.searchQuery)
    }

    fun importDocument(uri: Uri, resolver: ContentResolver) {
        runCatching {
            val mime = resolver.getType(uri).orEmpty()
            val name = queryDisplayName(uri, resolver) ?: "Arquivo importado"
            val now = System.currentTimeMillis()
            val lowerName = name.lowercase(Locale.getDefault())
            val isLiteRtModel = lowerName.endsWith(".tflite") ||
                lowerName.endsWith(".litert") ||
                lowerName.endsWith(".liter")
            val isText = mime.startsWith("text/") || lowerName.endsWith(".txt") || lowerName.endsWith(".md")
            val isVideo = mime.startsWith("video/")

            if (isLiteRtModel) {
                val modelFile = liteRtModelManager.importQuickTranscriptionModel(uri, resolver)
                uiState = uiState.copy(
                    liteRtModelReady = true,
                    whisperStatus = "Modelo LiteRT instalado: ${modelFile.name} (${modelFile.length() / (1024 * 1024)} MB).",
                    message = "Modelo LiteRT instalado. Agora ele fica no armazenamento interno do app.",
                )
            } else if (isText) {
                val text = resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                val chunks = semanticEngine.importChunksFromPlainText(text)
                val session = AudioSession(
                    id = "import_$now",
                    title = name.substringBeforeLast('.'),
                    createdAt = now,
                    updatedAt = now,
                    durationMs = chunks.lastOrNull()?.endMs ?: 0L,
                    sourceType = "text_import",
                    status = "indexed",
                    transcriptionEngine = "text_import",
                    note = "Transcript importado e comprimido no indice TurboMSE local.",
                    chunks = chunks,
                )
                persistImportedSession(session)
                uiState = uiState.copy(message = "Transcript importado com indexacao pronta.")
            } else {
                val importedFile = copyIntoAppStorage(uri, resolver, name, now)
                val duration = extractDuration(importedFile.absolutePath)
                val session = AudioSession(
                    id = "media_$now",
                    title = name.substringBeforeLast('.'),
                    createdAt = now,
                    updatedAt = now,
                    durationMs = duration,
                    audioPath = importedFile.absolutePath,
                    sourceType = if (isVideo) "video_import" else "audio_import",
                    status = "needs_transcription",
                    transcriptionEngine = "pending",
                    note = "Midia copiada para a biblioteca local. Ainda nao foi transcrita, entao nao entra na busca semantica.",
                    chunks = emptyList(),
                )
                persistImportedSession(session)
                uiState = uiState.copy(message = "Arquivo importado para a biblioteca.")
            }
        }.onFailure {
            uiState = uiState.copy(message = "Nao consegui importar este arquivo.")
        }
    }

    fun importLiteRtModel(uri: Uri, resolver: ContentResolver) {
        runCatching {
            val modelFile = liteRtModelManager.importQuickTranscriptionModel(uri, resolver)
            uiState = uiState.copy(
                liteRtModelReady = true,
                whisperStatus = "Modelo LiteRT instalado: ${modelFile.name} (${modelFile.length() / (1024 * 1024)} MB).",
                message = "Modelo LiteRT instalado. Agora ele fica no armazenamento interno do app.",
            )
        }.onFailure {
            uiState = uiState.copy(message = "Nao consegui importar este modelo LiteRT.")
        }
    }

    fun createIndexedNote(title: String, body: String) {
        val trimmedBody = body.trim()
        if (trimmedBody.isBlank()) {
            uiState = uiState.copy(message = "Digite algum texto para indexar a nota.")
            return
        }

        val now = System.currentTimeMillis()
        val normalizedTitle = title.trim().ifBlank {
            semanticEngine.suggestTitle(semanticEngine.importChunksFromPlainText(trimmedBody))
        }
        val chunks = semanticEngine.importChunksFromPlainText(trimmedBody)
        val session = AudioSession(
            id = "note_$now",
            title = normalizedTitle,
            createdAt = now,
            updatedAt = now,
            durationMs = chunks.lastOrNull()?.endMs ?: 0L,
            sourceType = "note",
            status = "indexed",
            transcriptionEngine = "manual_note",
            note = "Nota textual indexada localmente com TurboMSE.",
            chunks = chunks,
        )
        persistImportedSession(session)
        uiState = uiState.copy(message = "Nota indexada e pronta para testar a busca semântica.")
    }

    fun transcribeSessionWithWhisper(sessionId: String) {
        val session = uiState.sessions.firstOrNull { it.id == sessionId }
        if (session == null) {
            uiState = uiState.copy(message = "Sessão não encontrada.")
            return
        }
        val audioPath = session.audioPath
        if (audioPath.isNullOrBlank()) {
            uiState = uiState.copy(message = "Essa sessão não possui arquivo de áudio/vídeo para transcrever.")
            return
        }
        if (uiState.whisperBusySessionId == sessionId || pendingWhisperQueue.contains(sessionId)) {
            uiState = uiState.copy(message = "Essa sessão já está em processamento na fila Whisper.")
            return
        }

        pendingWhisperQueue.addLast(sessionId)
        replaceSession(
            session.copy(
                status = "queued",
                transcriptionEngine = session.safeTranscriptionEngine("pending"),
                note = if (uiState.liteRtModelReady) {
                    "Arquivo aguardando na fila LiteRT batch para transcrição offline."
                } else {
                    "Arquivo aguardando na fila Whisper para transcrição offline."
                },
                updatedAt = System.currentTimeMillis(),
            )
        )
        uiState = uiState.copy(
            whisperQueueCount = pendingWhisperQueue.size,
            whisperStatus = if (uiState.whisperBusySessionId == null) {
                "Sessão adicionada à fila batch. Preparando transcrição offline..."
            } else {
                "Sessão adicionada à fila batch. ${pendingWhisperQueue.size} item(ns) aguardando."
            },
            message = if (uiState.whisperBusySessionId == null) {
                "Sessão enviada para transcrição batch."
            } else {
                "Sessão adicionada à fila batch."
            },
        )
        processNextWhisperJob()
    }

    private fun processNextWhisperJob() {
        if (activeWhisperJob != null) return
        val nextSessionId = pendingWhisperQueue.removeFirstOrNull() ?: run {
            uiState = uiState.copy(whisperQueueCount = 0)
            return
        }

        val model = uiState.selectedWhisperModel
        activeWhisperJob = viewModelScope.launch {
            try {
                val session = uiState.sessions.firstOrNull { it.id == nextSessionId }
                    ?: error("Sessão removida antes de iniciar a transcrição.")
                val useLiteRt = liteRtModelManager.hasQuickTranscriptionModel()

                replaceSession(
                    session.copy(
                        status = "transcribing",
                        transcriptionEngine = session.safeTranscriptionEngine("pending"),
                        note = if (useLiteRt) {
                            "Transcrevendo offline com LiteRT batch. Whisper.cpp fica como fallback legado."
                        } else {
                            "Transcrevendo offline com whisper.cpp e VAD local."
                        },
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                uiState = uiState.copy(
                    liteRtModelReady = useLiteRt,
                    whisperBusySessionId = nextSessionId,
                    whisperQueueCount = pendingWhisperQueue.size,
                    whisperStatus = if (useLiteRt) {
                        "Preparando LiteRT batch para ${session.title}..."
                    } else {
                        "Preparando modelo ${model.title} para ${session.title}..."
                    },
                )

                val localAudioFile = ensureLocalAudioFile(session) { progress ->
                    uiState = uiState.copy(whisperStatus = progress)
                }

                var usedEngineId = ""
                var usedEngineLabel = ""
                val result: BatchTranscriptionResult = if (useLiteRt) {
                    runCatching {
                        uiState = uiState.copy(whisperStatus = "Iniciando LiteRT CompiledModel NPU > GPU > CPU...")
                        liteRtTranscriber.transcribeFile(
                            filePath = localAudioFile.absolutePath,
                            language = preferredWhisperLanguage(),
                        ) { progress ->
                            uiState = uiState.copy(whisperStatus = progress)
                        }
                    }.onSuccess {
                        usedEngineId = it.engineId
                        usedEngineLabel = "LiteRT Batch"
                    }.getOrElse { liteRtError ->
                        val detail = liteRtError.message ?: "LiteRT ainda não concluiu esta transcrição."
                        uiState = uiState.copy(
                            whisperStatus = "LiteRT falhou, usando Whisper.cpp legado: $detail",
                        )
                        transcribeWithWhisperCpp(localAudioFile, model) { progress ->
                            uiState = uiState.copy(whisperStatus = progress)
                        }.also {
                            usedEngineId = "whisper_cpp:${model.id}"
                            usedEngineLabel = "Whisper ${model.title} (Legado)"
                        }
                    }
                } else {
                    transcribeWithWhisperCpp(localAudioFile, model) { progress ->
                        uiState = uiState.copy(whisperStatus = progress)
                    }.also {
                        usedEngineId = "whisper_cpp:${model.id}"
                        usedEngineLabel = "Whisper ${model.title} (Legado)"
                    }
                }

                val chunks = result.segments.map { segment ->
                    semanticEngine.chunkFromText(segment.text, segment.startMs, segment.endMs)
                }
                if (chunks.isEmpty()) {
                    error("Nenhum trecho foi gerado pelo Whisper.")
                }

                replaceSession(
                    session.copy(
                        status = "indexed",
                        transcriptionEngine = usedEngineId,
                        note = "Transcrito com $usedEngineLabel. VAD encontrou ${result.speechWindowCount} trecho(s) com fala em ${"%.1f".format(result.speechSeconds)}s úteis.",
                        audioPath = localAudioFile.absolutePath,
                        chunks = chunks,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                uiState = uiState.copy(
                    whisperStatus = "Transcrição concluída. ${chunks.size} trechos indexados para ${session.title}.",
                    message = "Sessão transcrita com $usedEngineLabel e pronta para busca semântica.",
                )
            } catch (error: Throwable) {
                val detail = error.message ?: "Falha ao transcrever com Whisper."
                uiState.sessions.firstOrNull { it.id == nextSessionId }?.let { failedSession ->
                    replaceSession(
                        failedSession.copy(
                            status = "failed",
                            transcriptionEngine = failedSession.safeTranscriptionEngine("pending"),
                            note = "Falha na transcrição offline: $detail",
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                }
                uiState = uiState.copy(
                    message = "$detail Tente novamente com o modelo base se esta mídia for curta.",
                    whisperStatus = "Falha na transcrição Whisper: $detail",
                )
            } finally {
                uiState = uiState.copy(
                    whisperBusySessionId = null,
                    whisperQueueCount = pendingWhisperQueue.size,
                )
                activeWhisperJob = null
                processNextWhisperJob()
            }
        }
    }

    private suspend fun transcribeWithWhisperCpp(
        localAudioFile: File,
        model: WhisperModel,
        onProgress: suspend (String) -> Unit,
    ): BatchTranscriptionResult {
        val modelFile = whisperModelManager.ensureModel(model, onProgress)
        uiState = uiState.copy(
            availableWhisperModelIds = whisperModelManager.installedModelIds(),
            whisperStatus = "Modelo pronto. Iniciando pipeline VAD + Whisper legado...",
        )
        return whisperTranscriber.transcribeFile(
            filePath = localAudioFile.absolutePath,
            modelPath = modelFile.absolutePath,
            language = preferredWhisperLanguage(),
            onProgress = onProgress,
        )
    }

    private suspend fun ensureLocalAudioFile(
        session: AudioSession,
        onProgress: suspend (String) -> Unit,
    ): File {
        val audioPath = session.audioPath ?: error("Sessão sem mídia para transcrição.")
        if (!audioPath.startsWith("content://")) {
            val file = File(audioPath)
            if (file.exists() && file.length() > 0L) {
                return file
            }
            error("O arquivo de mídia não foi encontrado no armazenamento local.")
        }

        onProgress("Copiando mídia importada para o armazenamento local...")
        val resolver = getApplication<Application>().contentResolver
        val uri = Uri.parse(audioPath)
        val displayName = queryDisplayName(uri, resolver)
            ?: session.title.ifBlank { "midia_importada" }
        val copied = copyIntoAppStorage(uri, resolver, displayName, System.currentTimeMillis())
        if (!copied.exists() || copied.length() == 0L) {
            error("Não consegui copiar a mídia importada para transcrição offline.")
        }
        return copied
    }

    private fun persistImportedSession(session: AudioSession) {
        val updatedSessions = (listOf(session) + uiState.sessions).sortedByDescending { it.updatedAt }
        storage.saveSessions(updatedSessions)
        uiState = uiState.copy(
            sessions = updatedSessions,
            rankedSessionIds = updatedSessions.map { it.id },
        )
        updateSearchQuery(uiState.searchQuery)
    }

    private fun replaceSession(session: AudioSession) {
        val updatedSessions = uiState.sessions
            .map { if (it.id == session.id) session else it }
            .sortedByDescending { it.updatedAt }
        storage.saveSessions(updatedSessions)
        uiState = uiState.copy(
            sessions = updatedSessions,
            rankedSessionIds = updatedSessions.map { it.id },
        )
        updateSearchQuery(uiState.searchQuery)
    }

    private fun rankSessionsFromHits(hits: List<SearchHit>, sessions: List<AudioSession>): List<String> {
        val bestScoresBySession = linkedMapOf<String, Float>()
        hits.forEach { hit ->
            val current = bestScoresBySession[hit.sessionId]
            if (current == null || hit.score > current) {
                bestScoresBySession[hit.sessionId] = hit.score
            }
        }

        val remaining = sessions
            .asSequence()
            .filterNot { bestScoresBySession.containsKey(it.id) }
            .sortedByDescending { it.updatedAt }
            .map { it.id }
            .toList()

        return bestScoresBySession.entries
            .sortedByDescending { it.value }
            .map { it.key } + remaining
    }

    private fun queryDisplayName(uri: Uri, resolver: ContentResolver): String? {
        val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                return cursor.getString(nameIndex)
            }
        }
        return null
    }

    private fun copyIntoAppStorage(
        uri: Uri,
        resolver: ContentResolver,
        displayName: String,
        timestamp: Long,
    ): File {
        val importDir = File(getApplication<Application>().filesDir, "memorywave/imports")
        if (!importDir.exists()) {
            importDir.mkdirs()
        }
        val safeName = displayName.ifBlank { "arquivo_$timestamp" }
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val outFile = File(importDir, "${timestamp}_$safeName")
        resolver.openInputStream(uri)?.use { input ->
            outFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: error("Nao foi possivel ler o arquivo importado.")
        return outFile
    }

    private fun extractDuration(filePath: String): Long {
        return runCatching {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(filePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            retriever.release()
            duration?.toLongOrNull() ?: 0L
        }.getOrDefault(0L)
    }

    private fun preferredWhisperLanguage(): String {
        val language = Locale.getDefault().language.lowercase(Locale.ROOT)
        return when (language) {
            "pt" -> "pt"
            "en" -> "en"
            "es" -> "es"
            else -> "auto"
        }
    }

    private fun AudioSession.safeTranscriptionEngine(fallback: String): String {
        val rawValue = runCatching {
            AudioSession::class.java
                .getDeclaredField("transcriptionEngine")
                .apply { isAccessible = true }
                .get(this) as? String
        }.getOrNull()
        return rawValue?.takeIf { it.isNotBlank() } ?: fallback
    }
}
