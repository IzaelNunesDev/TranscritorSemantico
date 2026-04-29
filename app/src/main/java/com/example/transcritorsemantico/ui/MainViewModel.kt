package com.example.transcritorsemantico.ui

import android.app.Application
import android.content.ContentResolver
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.transcritorsemantico.audio.RecorderCallbacks
import com.example.transcritorsemantico.audio.RecorderEngine
import com.example.transcritorsemantico.audio.TranscriptionEngine
import com.example.transcritorsemantico.data.AudioSession
import com.example.transcritorsemantico.data.SearchHit
import com.example.transcritorsemantico.data.SessionStorage
import com.example.transcritorsemantico.data.TranscriptChunk
import com.example.transcritorsemantico.data.TranscriptVariant
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
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.charset.StandardCharsets
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
    val totalAppStorageBytes: Long = 0L,
    val sessionStorageBytes: Map<String, Long> = emptyMap(),
)

private enum class TranscriptRequestType(
    val statusLabel: String,
    val queueNote: String,
) {
    LITERT_PRIMARY(
        statusLabel = "LiteRT",
        queueNote = "Fila LiteRT preparada para transcricao principal offline.",
    ),
    LEGACY_TURBO_REFINE(
        statusLabel = "Whisper Turbo",
        queueNote = "Fila Whisper legado turbo preparada para refinamento offline.",
    ),
}

private data class TranscriptTask(
    val sessionId: String,
    val type: TranscriptRequestType,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val storage = SessionStorage(application)
    private val recorder = RecorderEngine(application)
    private val semanticEngine = LocalSemanticEngine()
    private val whisperModelManager = WhisperModelManager(application)
    private val liteRtModelManager = LiteRtModelManager(application)
    private val liteRtTranscriber = WhisperLiteRtTranscriber(application)
    private val whisperTranscriber = WhisperFileTranscriber(application)
    private val appStorageDir = File(application.filesDir, "memorywave")

    private var timerJob: Job? = null
    private var activeTranscriptionJob: Job? = null
    private var activeTask: TranscriptTask? = null
    private var recordingStartedAt = 0L
    private var lastChunkEndsAt = 0L
    private val pendingTranscriptionQueue = ArrayDeque<TranscriptTask>()

    var uiState by mutableStateOf(UiState())
        private set

    init {
        val rawSessions = storage.loadSessions()
        val cleanedSessions = rawSessions.map { session ->
            if (session.status == "transcribing" || session.status == "queued") {
                session.copy(
                    status = "needs_transcription",
                    note = "Transcricao interrompida (app reiniciado). Abra os detalhes para retomar.",
                )
            } else {
                session
            }
        }.sortedByDescending { it.updatedAt }

        if (cleanedSessions != rawSessions) {
            storage.saveSessions(cleanedSessions)
        }

        val liteRtReady = liteRtModelManager.hasQuickTranscriptionModel()
        Log.i(TAG, "ViewModel init: LiteRT ready=$liteRtReady ${liteRtModelManager.describeQuickModel()}")
        uiState = uiState.copy(
            availableWhisperModelIds = whisperModelManager.installedModelIds(),
            liteRtModelReady = liteRtReady,
            whisperStatus = if (liteRtReady) {
                "LiteRT pronto. Abra um audio para transcrever com ele ou comparar com Whisper turbo."
            } else {
                "LiteRT nao instalado. Use Whisper legado turbo ou importe um modelo .tflite."
            },
        )
        refreshSessions(cleanedSessions)
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
                "Modelo ${model.title} ja esta pronto para uso legado."
            } else {
                "Modelo ${model.title} ainda nao foi baixado."
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

        persistImportedSession(newSession)
        uiState = uiState.copy(
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
                refreshLiteRtAvailability("modelo importado")
                uiState = uiState.copy(
                    whisperStatus = "Modelo LiteRT instalado: ${modelFile.name} (${modelFile.length() / (1024 * 1024)} MB).",
                    message = "Modelo LiteRT instalado e validado para uso offline.",
                )
            } else if (isText) {
                val text = resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                val chunks = semanticEngine.importChunksFromPlainText(text)
                val session = AudioSession(
                    id = "import_$now",
                    title = prettifyImportedTitle(name, now),
                    createdAt = now,
                    updatedAt = now,
                    durationMs = chunks.lastOrNull()?.endMs ?: 0L,
                    sourceType = "text_import",
                    status = "indexed",
                    transcriptionEngine = "text_import",
                    note = "Texto importado e compactado no indice TurboMSE local.",
                    chunks = chunks,
                )
                persistImportedSession(session)
                uiState = uiState.copy(message = "Texto importado com indexacao pronta.")
            } else {
                val importedFile = copyIntoAppStorage(uri, resolver, name, now)
                val duration = extractDuration(importedFile.absolutePath)
                val session = AudioSession(
                    id = "media_$now",
                    title = prettifyImportedTitle(name, now),
                    createdAt = now,
                    updatedAt = now,
                    durationMs = duration,
                    audioPath = importedFile.absolutePath,
                    sourceType = if (isVideo) "video_import" else "audio_import",
                    status = "needs_transcription",
                    transcriptionEngine = "pending",
                    note = "Midia copiada para a biblioteca local. Abra os detalhes para transcrever.",
                    chunks = emptyList(),
                )
                persistImportedSession(session)
                uiState = uiState.copy(message = "Arquivo importado para a biblioteca.")
            }
        }.onFailure {
            Log.e(TAG, "Import failed for uri=$uri", it)
            uiState = uiState.copy(message = "Nao consegui importar este arquivo.")
        }
    }

    fun importLiteRtModel(uri: Uri, resolver: ContentResolver) {
        runCatching {
            val modelFile = liteRtModelManager.importQuickTranscriptionModel(uri, resolver)
            refreshLiteRtAvailability("modelo importado manualmente")
            uiState = uiState.copy(
                whisperStatus = "Modelo LiteRT instalado: ${modelFile.name} (${modelFile.length() / (1024 * 1024)} MB).",
                message = "Modelo LiteRT instalado e validado para uso offline.",
            )
        }.onFailure {
            Log.e(TAG, "LiteRT model import failed for uri=$uri", it)
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
        uiState = uiState.copy(message = "Nota textual indexada e pronta para busca semantica.")
    }

    fun transcribeSessionWithLiteRt(sessionId: String) {
        val liteRtReady = liteRtModelManager.hasQuickTranscriptionModel()
        Log.i(TAG, "LiteRT transcription requested for session=$sessionId ready=$liteRtReady ${liteRtModelManager.describeQuickModel()}")
        if (!liteRtReady) {
            uiState = uiState.copy(
                liteRtModelReady = false,
                message = "Modelo LiteRT nao encontrado. Importe um .tflite antes de usar esta opcao.",
            )
            return
        }
        enqueueTranscription(sessionId, TranscriptRequestType.LITERT_PRIMARY)
    }

    fun refineSessionWithLegacyTurbo(sessionId: String) {
        Log.i(TAG, "Legacy turbo refinement requested for session=$sessionId")
        enqueueTranscription(sessionId, TranscriptRequestType.LEGACY_TURBO_REFINE)
    }

    fun deleteSession(sessionId: String) {
        val session = uiState.sessions.firstOrNull { it.id == sessionId }
        if (session == null) {
            uiState = uiState.copy(message = "Sessao nao encontrada.")
            return
        }
        if (activeTask?.sessionId == sessionId || pendingTranscriptionQueue.any { it.sessionId == sessionId }) {
            uiState = uiState.copy(message = "Espere a transcricao terminar antes de excluir esta sessao.")
            return
        }

        deleteManagedMediaFile(session.audioPath)
        val updatedSessions = uiState.sessions.filterNot { it.id == sessionId }
        storage.saveSessions(updatedSessions)
        refreshSessions(updatedSessions, selectedSessionId = null)
        uiState = uiState.copy(
            message = "Audio excluido e indexacao removida da memoria local.",
            selectedSessionId = null,
        )
        Log.i(TAG, "Session deleted id=$sessionId title=${session.title}")
    }

    private fun enqueueTranscription(sessionId: String, type: TranscriptRequestType) {
        val session = uiState.sessions.firstOrNull { it.id == sessionId }
        if (session == null) {
            uiState = uiState.copy(message = "Sessao nao encontrada.")
            return
        }
        val audioPath = session.audioPath
        if (audioPath.isNullOrBlank()) {
            uiState = uiState.copy(message = "Essa sessao nao possui arquivo de audio/video para transcrever.")
            return
        }
        if (activeTask?.sessionId == sessionId || pendingTranscriptionQueue.any { it.sessionId == sessionId }) {
            uiState = uiState.copy(message = "Essa sessao ja esta em processamento na fila offline.")
            return
        }

        pendingTranscriptionQueue.addLast(TranscriptTask(sessionId = sessionId, type = type))
        replaceSession(
            session.copy(
                status = "queued",
                note = type.queueNote,
                updatedAt = System.currentTimeMillis(),
            )
        )
        uiState = uiState.copy(
            whisperQueueCount = pendingTranscriptionQueue.size,
            whisperStatus = "Sessao adicionada a fila ${type.statusLabel}. ${pendingTranscriptionQueue.size} item(ns) aguardando.",
            message = "Sessao enviada para ${type.statusLabel}.",
        )
        Log.i(TAG, "Queued task type=${type.name} session=$sessionId queueSize=${pendingTranscriptionQueue.size}")
        processNextTranscriptionJob()
    }

    private fun processNextTranscriptionJob() {
        activeTranscriptionJob?.let { job ->
            if (job.isActive) return
            activeTranscriptionJob = null
            activeTask = null
        }
        val nextTask = pendingTranscriptionQueue.removeFirstOrNull() ?: run {
            uiState = uiState.copy(whisperQueueCount = 0)
            return
        }

        activeTask = nextTask
        activeTranscriptionJob = viewModelScope.launch {
            try {
                val session = uiState.sessions.firstOrNull { it.id == nextTask.sessionId }
                    ?: error("Sessao removida antes de iniciar a transcricao.")

                refreshLiteRtAvailability("inicio da tarefa ${nextTask.type.name}")
                Log.i(
                    TAG,
                    "Starting task type=${nextTask.type.name} session=${session.id} title=${session.title} liteRtReady=${uiState.liteRtModelReady}"
                )
                replaceSession(
                    session.copy(
                        status = "transcribing",
                        note = when (nextTask.type) {
                            TranscriptRequestType.LITERT_PRIMARY ->
                                "Transcrevendo com LiteRT. Compare depois com Whisper turbo nos detalhes."
                            TranscriptRequestType.LEGACY_TURBO_REFINE ->
                                "Refinando com Whisper legado large-v3-turbo para comparar qualidade."
                        },
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                uiState = uiState.copy(
                    whisperBusySessionId = nextTask.sessionId,
                    whisperQueueCount = pendingTranscriptionQueue.size,
                    whisperStatus = when (nextTask.type) {
                        TranscriptRequestType.LITERT_PRIMARY ->
                            "Validando modelo LiteRT e preparando pipeline offline..."
                        TranscriptRequestType.LEGACY_TURBO_REFINE ->
                            "Preparando Whisper legado turbo para refinamento..."
                    },
                )

                val localAudioFile = ensureLocalAudioFile(session) { progress ->
                    uiState = uiState.copy(whisperStatus = progress)
                }

                val result = withTimeout(TRANSCRIPTION_TIMEOUT_MS) {
                    when (nextTask.type) {
                        TranscriptRequestType.LITERT_PRIMARY -> {
                            if (!liteRtModelManager.hasQuickTranscriptionModel()) {
                                Log.e(TAG, "LiteRT false-positive prevented: model not installed at execution time.")
                                error("Modelo LiteRT nao encontrado no momento da execucao.")
                            }
                            liteRtTranscriber.transcribeFile(
                                filePath = localAudioFile.absolutePath,
                                language = preferredWhisperLanguage(),
                            ) { progress ->
                                uiState = uiState.copy(whisperStatus = progress)
                            }
                        }
                        TranscriptRequestType.LEGACY_TURBO_REFINE -> {
                            transcribeWithWhisperCpp(
                                localAudioFile = localAudioFile,
                                model = WhisperModel.LARGE_V3_TURBO,
                            ) { progress ->
                                uiState = uiState.copy(whisperStatus = progress)
                            }
                        }
                    }
                }

                val chunks = result.segments.map { segment ->
                    semanticEngine.chunkFromText(segment.text, segment.startMs, segment.endMs)
                }
                if (chunks.isEmpty()) {
                    error("Nenhum trecho foi gerado para indexacao.")
                }

                val updatedSession = buildUpdatedSessionWithResult(
                    session = uiState.sessions.first { it.id == nextTask.sessionId },
                    type = nextTask.type,
                    localAudioFile = localAudioFile,
                    result = result,
                    chunks = chunks,
                )
                replaceSession(updatedSession)

                val usedEngineLabel = when (nextTask.type) {
                    TranscriptRequestType.LITERT_PRIMARY -> "LiteRT"
                    TranscriptRequestType.LEGACY_TURBO_REFINE -> "Whisper legado turbo"
                }
                uiState = uiState.copy(
                    whisperStatus = "$usedEngineLabel concluiu ${chunks.size} trechos para ${updatedSession.title}.",
                    message = when (nextTask.type) {
                        TranscriptRequestType.LITERT_PRIMARY ->
                            "Transcricao LiteRT concluida. Agora voce pode comparar com Whisper turbo."
                        TranscriptRequestType.LEGACY_TURBO_REFINE ->
                            "Refinamento Whisper turbo concluido e indice principal atualizado."
                    },
                )
                Log.i(
                    TAG,
                    "Task finished type=${nextTask.type.name} session=${updatedSession.id} engine=${updatedSession.transcriptionEngine} chunks=${chunks.size}"
                )
            } catch (error: Throwable) {
                val detail = error.message ?: "Falha ao transcrever offline."
                Log.e(TAG, "Offline transcription failed for task=${nextTask.type.name} session=${nextTask.sessionId}", error)
                uiState.sessions.firstOrNull { it.id == nextTask.sessionId }?.let { failedSession ->
                    replaceSession(
                        failedSession.copy(
                            status = "failed",
                            note = "Falha na transcricao offline: $detail",
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                }
                uiState = uiState.copy(
                    message = detail,
                    whisperStatus = "Falha na transcricao: $detail",
                )
            } finally {
                uiState = uiState.copy(
                    whisperBusySessionId = null,
                    whisperQueueCount = pendingTranscriptionQueue.size,
                )
                activeTask = null
                activeTranscriptionJob = null
                processNextTranscriptionJob()
            }
        }
    }

    private fun buildUpdatedSessionWithResult(
        session: AudioSession,
        type: TranscriptRequestType,
        localAudioFile: File,
        result: BatchTranscriptionResult,
        chunks: List<TranscriptChunk>,
    ): AudioSession {
        val variant = TranscriptVariant(
            engineId = when (type) {
                TranscriptRequestType.LITERT_PRIMARY -> result.engineId
                TranscriptRequestType.LEGACY_TURBO_REFINE -> "whisper_cpp:${WhisperModel.LARGE_V3_TURBO.id}"
            },
            label = when (type) {
                TranscriptRequestType.LITERT_PRIMARY -> "LiteRT"
                TranscriptRequestType.LEGACY_TURBO_REFINE -> "Whisper legado turbo"
            },
            createdAt = System.currentTimeMillis(),
            speechWindowCount = result.speechWindowCount,
            speechSeconds = result.speechSeconds,
            totalSeconds = result.totalSeconds,
            text = chunks.joinToString("\n") { it.text },
            chunks = chunks,
        )

        return when (type) {
            TranscriptRequestType.LITERT_PRIMARY -> session.copy(
                status = "indexed",
                transcriptionEngine = variant.engineId,
                note = "Transcrito com LiteRT. Abra as abas para comparar com Whisper turbo quando quiser.",
                audioPath = localAudioFile.absolutePath,
                chunks = chunks,
                liteRtTranscript = variant,
                updatedAt = System.currentTimeMillis(),
            )
            TranscriptRequestType.LEGACY_TURBO_REFINE -> session.copy(
                status = "indexed",
                transcriptionEngine = variant.engineId,
                note = "Refinado com Whisper legado turbo. A comparacao com LiteRT continua disponivel nos detalhes.",
                audioPath = localAudioFile.absolutePath,
                chunks = chunks,
                legacyTurboTranscript = variant,
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    private suspend fun transcribeWithWhisperCpp(
        localAudioFile: File,
        model: WhisperModel,
        onProgress: suspend (String) -> Unit,
    ): BatchTranscriptionResult {
        Log.i(TAG, "Preparing whisper.cpp model=${model.id} for file=${localAudioFile.absolutePath}")
        val modelFile = whisperModelManager.ensureModel(model, onProgress)
        uiState = uiState.copy(
            availableWhisperModelIds = whisperModelManager.installedModelIds(),
            whisperStatus = "Modelo ${model.title} pronto. Iniciando pipeline VAD + Whisper legado...",
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
        val audioPath = session.audioPath ?: error("Sessao sem midia para transcricao.")
        if (!audioPath.startsWith("content://")) {
            val file = File(audioPath)
            if (file.exists() && file.length() > 0L) {
                Log.i(TAG, "Using local media file path=${file.absolutePath} sizeBytes=${file.length()}")
                return file
            }
            error("O arquivo de midia nao foi encontrado no armazenamento local.")
        }

        onProgress("Copiando midia importada para o armazenamento local...")
        val resolver = getApplication<Application>().contentResolver
        val uri = Uri.parse(audioPath)
        val displayName = queryDisplayName(uri, resolver)
            ?: session.title.ifBlank { "midia_importada" }
        val copied = copyIntoAppStorage(uri, resolver, displayName, System.currentTimeMillis())
        if (!copied.exists() || copied.length() == 0L) {
            error("Nao consegui copiar a midia importada para transcricao offline.")
        }
        Log.i(TAG, "Copied external media into app storage path=${copied.absolutePath} sizeBytes=${copied.length()}")
        return copied
    }

    private fun persistImportedSession(session: AudioSession) {
        val updatedSessions = (listOf(session) + uiState.sessions).sortedByDescending { it.updatedAt }
        storage.saveSessions(updatedSessions)
        refreshSessions(updatedSessions)
    }

    private fun replaceSession(session: AudioSession) {
        val updatedSessions = uiState.sessions
            .map { if (it.id == session.id) session else it }
            .sortedByDescending { it.updatedAt }
        storage.saveSessions(updatedSessions)
        refreshSessions(updatedSessions)
    }

    private fun refreshSessions(
        sessions: List<AudioSession>,
        selectedSessionId: String? = uiState.selectedSessionId,
    ) {
        val safeSelection = selectedSessionId?.takeIf { candidate -> sessions.any { it.id == candidate } }
        uiState = uiState.copy(
            sessions = sessions,
            totalAppStorageBytes = computeDirectorySize(appStorageDir),
            sessionStorageBytes = sessions.associate { it.id to estimateSessionStorageBytes(it) },
            selectedSessionId = safeSelection,
        )
        updateSearchQuery(uiState.searchQuery)
    }

    private fun refreshLiteRtAvailability(reason: String) {
        val ready = liteRtModelManager.hasQuickTranscriptionModel()
        Log.i(TAG, "LiteRT availability check ($reason): ready=$ready ${liteRtModelManager.describeQuickModel()}")
        uiState = uiState.copy(liteRtModelReady = ready)
    }

    private fun estimateSessionStorageBytes(session: AudioSession): Long {
        val audioBytes = session.audioPath
            ?.takeUnless { it.startsWith("content://") }
            ?.let(::File)
            ?.takeIf { it.exists() }
            ?.length()
            ?: 0L
        val chunkBytes = estimateChunkBytes(session.chunks)
        val liteRtBytes = estimateVariantBytes(session.liteRtTranscript)
        val legacyBytes = estimateVariantBytes(session.legacyTurboTranscript)
        val metadataBytes = session.title.toByteArray(StandardCharsets.UTF_8).size +
            (session.note?.toByteArray(StandardCharsets.UTF_8)?.size ?: 0)
        return audioBytes + chunkBytes + liteRtBytes + legacyBytes + metadataBytes
    }

    private fun estimateChunkBytes(chunks: List<TranscriptChunk>): Long {
        return chunks.sumOf { chunk ->
            chunk.text.toByteArray(StandardCharsets.UTF_8).size.toLong() +
                chunk.packedEmbedding.toByteArray(StandardCharsets.UTF_8).size.toLong() +
                24L
        }
    }

    private fun estimateVariantBytes(variant: TranscriptVariant?): Long {
        if (variant == null) return 0L
        return variant.text.toByteArray(StandardCharsets.UTF_8).size.toLong() +
            variant.label.toByteArray(StandardCharsets.UTF_8).size.toLong() +
            estimateChunkBytes(variant.chunks) +
            32L
    }

    private fun computeDirectorySize(root: File): Long {
        if (!root.exists()) return 0L
        if (root.isFile) return root.length()
        return root.listFiles().orEmpty().sumOf { computeDirectorySize(it) }
    }

    private fun deleteManagedMediaFile(audioPath: String?) {
        if (audioPath.isNullOrBlank() || audioPath.startsWith("content://")) return
        val file = File(audioPath)
        val safeRoots = listOf(
            getApplication<Application>().filesDir.absolutePath,
            getApplication<Application>().cacheDir.absolutePath,
        )
        val isManaged = safeRoots.any { root -> file.absolutePath.startsWith(root) }
        if (!isManaged) {
            Log.w(TAG, "Skipped deletion for unmanaged path=${file.absolutePath}")
            return
        }
        if (file.exists()) {
            val deleted = runCatching { file.delete() }.getOrDefault(false)
            Log.i(TAG, "Deleted managed media file path=${file.absolutePath} deleted=$deleted")
        }
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
        Log.i(TAG, "Imported file copied to path=${outFile.absolutePath} sizeBytes=${outFile.length()}")
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

    private fun prettifyImportedTitle(rawName: String, now: Long): String {
        val withoutExtension = rawName.substringBeforeLast('.', rawName)
        val normalized = withoutExtension
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.isBlank()) {
            return "Audio ${SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(now))}"
        }
        return normalized.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
        }
    }

    companion object {
        private const val TAG = "MainViewModel"
        private const val TRANSCRIPTION_TIMEOUT_MS = 120_000L
    }
}
