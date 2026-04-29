package com.example.transcritorsemantico.ui

import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ArrowOutward
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.transcritorsemantico.R
import com.example.transcritorsemantico.audio.TranscriptionEngine
import com.example.transcritorsemantico.data.AudioSession
import com.example.transcritorsemantico.data.SearchHit
import com.example.transcritorsemantico.data.TranscriptChunk
import com.example.transcritorsemantico.whisper.WhisperModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MemoryWaveApp(
    viewModel: MainViewModel,
    micGranted: Boolean,
    requestMicPermission: () -> Unit,
) {
    val uiState = viewModel.uiState
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var noteDialogOpen by remember { mutableStateOf(false) }
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            viewModel.importDocument(uri, context.contentResolver)
        }
    }
    val modelImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            viewModel.importLiteRtModel(uri, context.contentResolver)
        }
    }

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .navigationBarsPadding(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                HeroCard(
                    isRecording = uiState.isRecording,
                    elapsedMs = uiState.elapsedMs,
                    micLevel = uiState.micLevel,
                    micGranted = micGranted,
                    selectedEngine = uiState.selectedEngine,
                    onEngineSelected = viewModel::setTranscriptionEngine,
                    selectedWhisperModel = uiState.selectedWhisperModel,
                    availableWhisperModelIds = uiState.availableWhisperModelIds,
                    liteRtModelReady = uiState.liteRtModelReady,
                    whisperQueueCount = uiState.whisperQueueCount,
                    whisperStatus = uiState.whisperStatus,
                    onWhisperModelSelected = viewModel::setWhisperModel,
                    onPrimary = {
                        if (!micGranted) {
                            requestMicPermission()
                        } else if (uiState.isRecording) {
                            viewModel.stopRecording()
                        } else {
                            viewModel.startRecording()
                        }
                    },
                    onSecondary = {
                        importLauncher.launch("*/*")
                    },
                    onImportModel = {
                        modelImportLauncher.launch("*/*")
                    },
                    onCreateNote = {
                        noteDialogOpen = true
                    },
                )
            }

            item {
                RecorderStatusCard(
                    draftChunks = uiState.draftChunks,
                    partialText = uiState.partialText,
                    isRecording = uiState.isRecording,
                    micLevel = uiState.micLevel,
                    indexedChunkCount = uiState.indexedChunkCount,
                    lastIndexedPreview = uiState.lastIndexedPreview,
                    indexingStatus = uiState.indexingStatus,
                )
            }

            item {
                SearchPanel(
                    query = uiState.searchQuery,
                    hits = uiState.searchHits,
                    sessions = uiState.sessions,
                    onQueryChange = viewModel::updateSearchQuery,
                    onOpenSession = viewModel::selectSession,
                )
            }

            item {
                SectionHeader(
                    icon = Icons.AutoMirrored.Rounded.LibraryBooks,
                    title = "Biblioteca",
                    subtitle = "Sessoes locais, audio gravado e memoria pesquisavel.",
                )
            }

            if (uiState.sessions.isEmpty()) {
                item {
                    EmptyLibraryCard()
                }
            } else {
                val rankedSessions = uiState.rankedSessionIds.mapNotNull { rankedId ->
                    uiState.sessions.firstOrNull { it.id == rankedId }
                }
                items(rankedSessions, key = { it.id }) { session ->
                    SessionCard(
                        session = session,
                        onClick = { viewModel.selectSession(session.id) },
                    )
                }
            }
        }

        val selectedSession = uiState.sessions.firstOrNull { it.id == uiState.selectedSessionId }
        if (selectedSession != null) {
            SessionDetailDialog(
                session = selectedSession,
                selectedWhisperModel = uiState.selectedWhisperModel,
                liteRtModelReady = uiState.liteRtModelReady,
                whisperStatus = uiState.whisperStatus,
                whisperBusy = uiState.whisperBusySessionId == selectedSession.id,
                onTranscribeWithWhisper = { viewModel.transcribeSessionWithWhisper(selectedSession.id) },
                onDismiss = { viewModel.selectSession(null) },
            )
        }

        if (noteDialogOpen) {
            NoteDialog(
                onDismiss = { noteDialogOpen = false },
                onSave = { title, body ->
                    viewModel.createIndexedNote(title, body)
                    noteDialogOpen = false
                },
            )
        }
    }
}

@Composable
private fun HeroCard(
    isRecording: Boolean,
    elapsedMs: Long,
    micLevel: Float,
    micGranted: Boolean,
    selectedEngine: TranscriptionEngine,
    onEngineSelected: (TranscriptionEngine) -> Unit,
    selectedWhisperModel: WhisperModel,
    availableWhisperModelIds: List<String>,
    liteRtModelReady: Boolean,
    whisperQueueCount: Int,
    whisperStatus: String,
    onWhisperModelSelected: (WhisperModel) -> Unit,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
    onImportModel: () -> Unit,
    onCreateNote: () -> Unit,
) {
    val gradient = if (isRecording) {
        listOf(Color(0xFF5E0B15), Color(0xFFB42318), Color(0xFFF97316))
    } else {
        listOf(Color(0xFF09203F), Color(0xFF1F4F78), Color(0xFF67A4BA))
    }

    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .background(Brush.linearGradient(gradient))
                .padding(22.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "MemoryWave",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                        )
                        Text(
                            text = "Grave agora. Busque depois. Tudo local.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color(0xFFE8F7FF),
                        )
                    }
                    Image(
                        painter = painterResource(id = R.drawable.memorywave_brand),
                        contentDescription = "Marca MemoryWave",
                        modifier = Modifier
                            .size(84.dp)
                            .clip(RoundedCornerShape(24.dp))
                    )
                }

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MetricChip(label = if (isRecording) "Sessao em curso" else "Pronto para gravar")
                    MetricChip(label = "TurboMSE 4-bit")
                    MetricChip(label = "Busca semantica local")
                    if (liteRtModelReady) {
                        MetricChip(label = "LiteRT batch pronto")
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Motor de transcricao",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TranscriptionEngine.entries.forEach { engine ->
                            FilterChip(
                                selected = selectedEngine == engine,
                                onClick = {
                                    if (!isRecording) {
                                        onEngineSelected(engine)
                                    }
                                },
                                label = { Text(engine.label) },
                                enabled = !isRecording,
                            )
                        }
                    }
                    Text(
                        text = selectedEngine.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFD7EDF8),
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (liteRtModelReady) {
                            "LiteRT batch + Whisper legado"
                        } else {
                            "Whisper.cpp por arquivo"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        WhisperModel.entries.forEach { model ->
                            FilterChip(
                                selected = selectedWhisperModel == model,
                                onClick = { onWhisperModelSelected(model) },
                                label = {
                                    Text(
                                        if (availableWhisperModelIds.contains(model.id)) {
                                            "${model.title} pronto"
                                        } else {
                                            "${model.title} ${model.sizeLabel}"
                                        }
                                    )
                                },
                            )
                        }
                    }
                    if (whisperQueueCount > 0) {
                        MetricChip(label = "Fila batch $whisperQueueCount")
                    }
                    Text(
                        text = whisperStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFD7EDF8),
                    )
                }

                Text(
                    text = if (isRecording) {
                        "Escutando o microfone do aparelho com ${selectedEngine.label}. Tempo atual: ${formatDuration(elapsedMs)}"
                    } else if (micGranted) {
                        "Use o gravador para capturar reunioes, aulas ou ideias. O TurboMSE so indexa texto depois da transcricao."
                    } else {
                        "Autorize o microfone para transformar o aparelho em uma memoria de audio pesquisavel."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFD7EDF8),
                )

                if (isRecording) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = "Nivel do microfone",
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White,
                            )
                            Text(
                                text = "${(micLevel * 100).toInt()}%",
                                style = MaterialTheme.typography.labelLarge,
                                color = Color(0xFFFFE7C2),
                            )
                        }
                        LinearProgressIndicator(
                            progress = { micLevel.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFFFFE7C2),
                            trackColor = Color.White.copy(alpha = 0.18f),
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = onPrimary,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color(0xFF09203F),
                        )
                    ) {
                        Icon(
                            imageVector = if (isRecording) Icons.Rounded.StopCircle else Icons.Rounded.Mic,
                            contentDescription = null,
                        )
                        Spacer(modifier = Modifier.size(10.dp))
                        Text(if (isRecording) "Encerrar sessao" else "Comecar captura")
                    }

                    FilledTonalButton(
                        onClick = onSecondary,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Color.White.copy(alpha = 0.14f),
                            contentColor = Color.White,
                        )
                    ) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = null)
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("Importar arquivo")
                    }
                }

                OutlinedButton(
                    onClick = onImportModel,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color.White,
                    ),
                ) {
                    Icon(Icons.Rounded.Album, contentDescription = null)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(if (liteRtModelReady) "Trocar modelo LiteRT" else "Importar modelo LiteRT")
                }

                OutlinedButton(
                    onClick = onCreateNote,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color.White,
                    ),
                ) {
                    Icon(Icons.Rounded.Search, contentDescription = null)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("Criar nota indexada")
                }
            }
        }
    }
}

@Composable
private fun RecorderStatusCard(
    draftChunks: List<TranscriptChunk>,
    partialText: String,
    isRecording: Boolean,
    micLevel: Float,
    indexedChunkCount: Int,
    lastIndexedPreview: String,
    indexingStatus: String,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionHeader(
                icon = Icons.Rounded.GraphicEq,
                title = "Captura ao vivo",
                subtitle = "Trechos reconhecidos entram direto no indice local.",
            )

            StatusStrip(
                isRecording = isRecording,
                micLevel = micLevel,
                indexedChunkCount = indexedChunkCount,
                indexingStatus = indexingStatus,
            )

            if (draftChunks.isEmpty() && !isRecording) {
                Text(
                    text = "Nenhuma sessao ativa. Quando voce começar a gravar, os trechos aparecem aqui em tempo real.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                if (lastIndexedPreview.isNotBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "Ultimo trecho indexado",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            Text(
                                text = lastIndexedPreview,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                draftChunks.takeLast(4).forEach { chunk ->
                    ChunkRow(
                        title = formatDuration(chunk.startMs),
                        text = chunk.text,
                        accent = Color(0xFF0EA5A4),
                    )
                }

                AnimatedVisibility(visible = partialText.isNotBlank()) {
                    ChunkRow(
                        title = "Escutando",
                        text = partialText,
                        accent = Color(0xFFF59E0B),
                    )
                }

                if (isRecording) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FiberManualRecord,
                            contentDescription = null,
                            tint = Color(0xFFEF4444),
                        )
                        Text(
                            text = "Gravando e reiniciando o reconhecimento por segmento.",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusStrip(
    isRecording: Boolean,
    micLevel: Float,
    indexedChunkCount: Int,
    indexingStatus: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(18.dp),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = if (isRecording) "Microfone ativo" else "Microfone inativo",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                LinearProgressIndicator(
                    progress = { micLevel.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Surface(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(18.dp),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "$indexedChunkCount trechos",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = indexingStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SearchPanel(
    query: String,
    hits: List<SearchHit>,
    sessions: List<AudioSession>,
    onQueryChange: (String) -> Unit,
    onOpenSession: (String) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionHeader(
                icon = Icons.Rounded.Search,
                title = "Busca Semantica",
                subtitle = "Exemplos: prazo, cliente XPTO, decisao de budget, proxima entrega.",
            )

            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                placeholder = { Text("Quando falaram sobre prazo do projeto?") },
                shape = RoundedCornerShape(18.dp),
                maxLines = 1,
            )

            if (query.isBlank()) {
                Text(
                    text = "A busca consulta apenas sessoes que ja viraram texto indexado. Audios sem transcricao ficam fora por enquanto.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (hits.isEmpty()) {
                Text(
                    text = "Nenhum trecho relevante encontrado ainda. Grave ou importe mais material para alimentar o indice.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = "Os arquivos mais relevantes tambem sobem para o topo da biblioteca abaixo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                hits.forEach { hit ->
                    val session = sessions.firstOrNull { it.id == hit.sessionId }
                    SearchHitCard(
                        hit = hit,
                        sessionTitle = session?.title.orEmpty(),
                        onOpen = { onOpenSession(hit.sessionId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NoteDialog(
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = { onSave(title, body) },
                enabled = body.isNotBlank(),
            ) {
                Text("Indexar nota")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        },
        title = {
            Text("Nova nota semântica")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Digite um texto livre, salve e depois teste a busca semântica.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Título") },
                    placeholder = { Text("Ex.: Reunião com Izael") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.45f),
                    label = { Text("Conteúdo da nota") },
                    placeholder = { Text("Escreva frases com nomes, decisões, prazos e contexto para testar a busca.") },
                    minLines = 6,
                    maxLines = 10,
                    colors = TextFieldDefaults.colors(),
                )
            }
        },
    )
}

@Composable
private fun SearchHitCard(
    hit: SearchHit,
    sessionTitle: String,
    onOpen: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onOpen),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = sessionTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${(hit.score * 100).toInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = hit.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatDuration(hit.timestampMs),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Icon(Icons.Rounded.ArrowOutward, contentDescription = null)
            }
        }
    }
}

@Composable
private fun EmptyLibraryCard() {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Sua biblioteca ainda esta vazia.",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "A primeira sessao gravada ja cria audio, historico e indice quantizado. Importacoes de texto tambem entram prontas para busca.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SessionCard(
    session: AudioSession,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = session.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = formatDate(session.createdAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SessionBadge(session.status)
            }

            Text(
                text = session.note.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MetricChip("Duracao ${formatDuration(session.durationMs)}")
                MetricChip("${session.chunks.size} trechos")
                MetricChip(session.sourceType.replace('_', ' '))
            }
        }
    }
}

@Composable
private fun SessionBadge(status: String) {
    val color = when (status) {
        "indexed" -> Color(0xFF0EA5A4)
        "queued" -> Color(0xFF3B82F6)
        "transcribing" -> Color(0xFF8B5CF6)
        "needs_transcription" -> Color(0xFFF59E0B)
        "failed" -> Color(0xFFDC2626)
        else -> Color(0xFF64748B)
    }
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = status.replace('_', ' '),
            style = MaterialTheme.typography.labelLarge,
            color = color,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionDetailDialog(
    session: AudioSession,
    selectedWhisperModel: WhisperModel,
    liteRtModelReady: Boolean,
    whisperStatus: String,
    whisperBusy: Boolean,
    onTranscribeWithWhisper: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentSeek by remember { mutableLongStateOf(0L) }

    DisposableEffect(session.id) {
        onDispose {
            runCatching { player?.release() }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Fechar")
            }
        },
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(session.title, style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = session.note.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AssistChip(
                            onClick = {},
                            label = { Text(formatDuration(session.durationMs)) },
                            leadingIcon = {
                                Icon(Icons.Rounded.Album, contentDescription = null)
                            }
                        )
                        AssistChip(
                            onClick = {},
                            label = { Text("${session.chunks.size} trechos") },
                            leadingIcon = {
                                Icon(Icons.Rounded.GraphicEq, contentDescription = null)
                            }
                        )
                    }
                }

                if (session.audioPath != null) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconButton(
                                    onClick = {
                                        val mediaPlayer = player ?: createPlayer(context, session.audioPath)?.also {
                                            player = it
                                        }
                                        mediaPlayer ?: return@IconButton
                                        if (isPlaying) {
                                            mediaPlayer.pause()
                                            isPlaying = false
                                        } else {
                                            mediaPlayer.seekTo(currentSeek.toInt())
                                            mediaPlayer.start()
                                            isPlaying = true
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Rounded.PauseCircle else Icons.Rounded.PlayCircle,
                                        contentDescription = null,
                                        modifier = Modifier.size(30.dp),
                                    )
                                }
                                Text(
                                    text = "Reproduzir ou pausar o áudio salvo.",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }

                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = RoundedCornerShape(18.dp),
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = if (liteRtModelReady) {
                                            "LiteRT batch instalado; Whisper ${selectedWhisperModel.title} fica como legado"
                                        } else {
                                            "Whisper.cpp selecionado: ${selectedWhisperModel.title}"
                                        },
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                    Text(
                                        text = whisperStatus,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                    Button(
                                        onClick = onTranscribeWithWhisper,
                                        enabled = !whisperBusy && session.status != "queued" && session.status != "transcribing",
                                    ) {
                                        Text(
                                            if (whisperBusy) {
                                                "Transcrevendo..."
                                            } else if (session.status == "queued") {
                                                "Na fila batch"
                                            } else if (session.status == "transcribing") {
                                                "Processando áudio"
                                            } else if (session.status == "indexed") {
                                                if (liteRtModelReady) "Retranscrever com LiteRT" else "Retranscrever com Whisper"
                                            } else {
                                                if (liteRtModelReady) "Transcrever com LiteRT" else "Transcrever com Whisper"
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                items(session.chunks, key = { it.id }) { chunk ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .clickable {
                                currentSeek = chunk.startMs
                                val mediaPlayer = player ?: createPlayer(context, session.audioPath.orEmpty())?.also {
                                    player = it
                                }
                                mediaPlayer?.seekTo(chunk.startMs.toInt())
                                mediaPlayer?.start()
                                isPlaying = mediaPlayer != null
                            },
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = formatDuration(chunk.startMs),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = chunk.text,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }

                if (session.chunks.isEmpty()) {
                    item {
                        Text(
                            text = "Esta sessao ainda nao tem transcricao indexada.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
    )
}

private fun createPlayer(context: android.content.Context, audioPath: String): MediaPlayer? {
    if (audioPath.isBlank()) return null
    return runCatching {
        MediaPlayer().apply {
            if (audioPath.startsWith("content://")) {
                setDataSource(context, Uri.parse(audioPath))
            } else {
                setDataSource(File(audioPath).absolutePath)
            }
            prepare()
        }
    }.getOrNull()
}

@Composable
private fun ChunkRow(
    title: String,
    text: String,
    accent: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .size(10.dp)
                .clip(CircleShape)
                .background(accent)
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary,
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun SectionHeader(
    icon: ImageVector,
    title: String,
    subtitle: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MetricChip(label: String) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.14f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
        )
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

private fun formatDate(epochMs: Long): String {
    val locale = Locale.Builder().setLanguage("pt").setRegion("BR").build()
    return SimpleDateFormat("dd MMM yyyy, HH:mm", locale).format(Date(epochMs))
}
