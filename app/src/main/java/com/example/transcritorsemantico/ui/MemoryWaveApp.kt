package com.example.transcritorsemantico.ui

import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ArrowOutward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.transcritorsemantico.R
import com.example.transcritorsemantico.audio.TranscriptionEngine
import com.example.transcritorsemantico.data.AudioSession
import com.example.transcritorsemantico.data.SearchHit
import com.example.transcritorsemantico.data.TranscriptChunk
import com.example.transcritorsemantico.data.TranscriptVariant
import com.example.transcritorsemantico.whisper.WhisperModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val AppBackground = Color(0xFF070A14)
private val Panel = Color(0xFF10151F)
private val PanelAlt = Color(0xFF171B27)
private val Stroke = Color(0xFF252B3A)
private val Purple = Color(0xFF8A5CF6)
private val PurpleSoft = Color(0xFFB69CFF)
private val Gold = Color(0xFFFFC466)
private val MutedText = Color(0xFFA7AAB7)
private val DetailShell = Color(0xFF090D16)

private enum class AppTab(
    val label: String,
    val icon: ImageVector,
) {
    HOME("Inicio", Icons.Rounded.Mic),
    SESSIONS("Sessoes", Icons.Rounded.Album),
    SEARCH("Buscar", Icons.Rounded.Search),
    MODELS("Modelos", Icons.Rounded.GraphicEq),
    EXPORT("Exportar", Icons.Rounded.ArrowOutward),
}

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
    var selectedTab by remember { mutableStateOf(AppTab.HOME) }
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
            .background(AppBackground)
            .statusBarsPadding(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = AppBackground,
        bottomBar = {
            AppBottomBar(
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it },
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (selectedTab) {
                AppTab.HOME -> {
                    item {
                        HeroCard(
                            uiState = uiState,
                            micGranted = micGranted,
                            onEngineSelected = viewModel::setTranscriptionEngine,
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
                            onImportAudio = { importLauncher.launch("*/*") },
                            onImportModel = { modelImportLauncher.launch("*/*") },
                            onCreateNote = { noteDialogOpen = true },
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
                        RecentSessionsPreview(
                            sessions = uiState.sessions.take(3),
                            sessionStorageBytes = uiState.sessionStorageBytes,
                            onOpenSession = viewModel::selectSession,
                            onSeeAll = { selectedTab = AppTab.SESSIONS },
                        )
                    }
                }

                AppTab.SESSIONS -> {
                    item {
                        ScreenTitle(
                            title = "Sessoes",
                            subtitle = "Audios, notas e transcricoes organizados para consulta.",
                        )
                    }
                    if (uiState.sessions.isEmpty()) {
                        item { EmptyLibraryCard() }
                    } else {
                        val rankedSessions = uiState.rankedSessionIds.mapNotNull { rankedId ->
                            uiState.sessions.firstOrNull { it.id == rankedId }
                        }
                        items(rankedSessions, key = { it.id }) { session ->
                            SessionCard(
                                session = session,
                                sessionBytes = uiState.sessionStorageBytes[session.id] ?: 0L,
                                onClick = { viewModel.selectSession(session.id) },
                            )
                        }
                    }
                }

                AppTab.SEARCH -> {
                    item {
                        SearchPanel(
                            query = uiState.searchQuery,
                            hits = uiState.searchHits,
                            sessions = uiState.sessions,
                            onQueryChange = viewModel::updateSearchQuery,
                            onOpenSession = viewModel::selectSession,
                        )
                    }
                }

                AppTab.MODELS -> {
                    item {
                        ModelSettingsPanel(
                            uiState = uiState,
                            onEngineSelected = viewModel::setTranscriptionEngine,
                            onWhisperModelSelected = viewModel::setWhisperModel,
                            onImportModel = { modelImportLauncher.launch("*/*") },
                        )
                    }
                }

                AppTab.EXPORT -> {
                    item {
                        ExportPanel(
                            sessions = uiState.sessions,
                            onOpenSession = viewModel::selectSession,
                        )
                    }
                }
            }
        }

        val selectedSession = uiState.sessions.firstOrNull { it.id == uiState.selectedSessionId }
        if (selectedSession != null) {
            SessionDetailDialog(
                session = selectedSession,
                sessionBytes = uiState.sessionStorageBytes[selectedSession.id] ?: 0L,
                selectedWhisperModel = uiState.selectedWhisperModel,
                liteRtModelReady = uiState.liteRtModelReady,
                whisperStatus = uiState.whisperStatus,
                whisperBusy = uiState.whisperBusySessionId == selectedSession.id,
                onTranscribeWithLiteRt = { viewModel.transcribeSessionWithLiteRt(selectedSession.id) },
                onRefineWithLegacyTurbo = { viewModel.refineSessionWithLegacyTurbo(selectedSession.id) },
                onDelete = { viewModel.deleteSession(selectedSession.id) },
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
private fun AppBottomBar(
    selectedTab: AppTab,
    onTabSelected: (AppTab) -> Unit,
) {
    Surface(
        color = Color(0xEE090D16),
        shadowElevation = 12.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppTab.entries.forEach { tab ->
                val selected = selectedTab == tab
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .clickable { onTabSelected(tab) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.label,
                        tint = if (selected) Purple else MutedText,
                        modifier = Modifier.size(21.dp),
                    )
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) PurpleSoft else MutedText,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScreenTitle(
    title: String,
    subtitle: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MutedText,
        )
    }
}

@Composable
private fun HeroCard(
    uiState: UiState,
    micGranted: Boolean,
    onEngineSelected: (TranscriptionEngine) -> Unit,
    onWhisperModelSelected: (WhisperModel) -> Unit,
    onPrimary: () -> Unit,
    onImportAudio: () -> Unit,
    onImportModel: () -> Unit,
    onCreateNote: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(30.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF0B0D1D), Color(0xFF090B13), Color(0xFF12091F))
                    )
                )
                .border(1.dp, Stroke, RoundedCornerShape(30.dp))
                .padding(22.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.memorywave_header_art),
                contentDescription = null,
                alpha = 0.28f,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (uiState.isRecording) formatDuration(uiState.elapsedMs) else "Offline",
                        style = MaterialTheme.typography.titleMedium,
                        color = PurpleSoft,
                    )
                    Text(
                        text = "MemoryWave",
                        style = MaterialTheme.typography.headlineLarge,
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    Text(
                        text = "Transcritor Semantico",
                        style = MaterialTheme.typography.titleLarge,
                        color = PurpleSoft,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Grave, transcreva e encontre o que realmente importa, com dados no dispositivo.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFE5E7F2),
                    )
                }

                WavePreview(level = uiState.micLevel, active = uiState.isRecording)

                if (uiState.isRecording) {
                    LinearProgressIndicator(
                        progress = { uiState.micLevel.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = Purple,
                        trackColor = Color.White.copy(alpha = 0.12f),
                    )
                }

                HeroActions(
                    isRecording = uiState.isRecording,
                    onPrimary = onPrimary,
                    onImportAudio = onImportAudio,
                    onCreateNote = onCreateNote,
                    onImportModel = onImportModel,
                )
            }
        }

        FeatureList(
            liteRtModelReady = uiState.liteRtModelReady,
            selectedEngine = uiState.selectedEngine,
            totalStorageBytes = uiState.totalAppStorageBytes,
        )
    }
}

@Composable
private fun HeroActions(
    isRecording: Boolean,
    onPrimary: () -> Unit,
    onImportAudio: () -> Unit,
    onCreateNote: () -> Unit,
    onImportModel: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onPrimary,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Purple,
                    contentColor = Color.White,
                ),
                shape = RoundedCornerShape(24.dp),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
            ) {
                Icon(
                    imageVector = if (isRecording) Icons.Rounded.StopCircle else Icons.Rounded.Mic,
                    contentDescription = null,
                )
                Spacer(Modifier.width(10.dp))
                Text(if (isRecording) "Encerrar sessao" else "Comecar captura")
            }

            FilledTonalButton(
                onClick = onImportAudio,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = PanelAlt,
                    contentColor = Color.White,
                ),
            ) {
                Icon(Icons.Rounded.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text("Importar audio")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onCreateNote,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
            ) {
                Text("Nova nota de texto")
            }

            OutlinedButton(
                onClick = onImportModel,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
            ) {
                Text("Importar LiteRT")
            }
        }
    }
}

@Composable
private fun WavePreview(
    level: Float,
    active: Boolean,
) {
    val bars = listOf(16, 28, 20, 38, 26, 52, 34, 22, 46, 24, 18, 31, 19, 27, 15)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(92.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Black.copy(alpha = 0.28f))
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(24.dp))
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        bars.forEachIndexed { index, base ->
            val boost = if (active) (level * 34).toInt() else 0
            val height = (base + if (index % 3 == 0) boost else boost / 2).coerceIn(12, 78)
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(height.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(PurpleSoft, Purple, Color(0xFF5B2BD8))
                        )
                    )
            )
        }
    }
}

@Composable
private fun FeatureList(
    liteRtModelReady: Boolean,
    selectedEngine: TranscriptionEngine,
    totalStorageBytes: Long,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FeatureItem(Icons.Rounded.Mic, "Captura com IA local", selectedEngine.label)
        FeatureItem(Icons.Rounded.Search, "Busca semantica inteligente", "Encontre pelo significado, nao so por palavras.")
        FeatureItem(Icons.Rounded.GraphicEq, "Modelos avancados", if (liteRtModelReady) "LiteRT instalado" else "Importe um modelo LiteRT")
        FeatureItem(Icons.Rounded.FolderOpen, "Armazenamento local", formatStorageSize(totalStorageBytes))
    }
}

@Composable
private fun FeatureItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Panel)
            .border(1.dp, Stroke, RoundedCornerShape(18.dp))
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Purple.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = PurpleSoft)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Color.White)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MutedText)
        }
    }
}

@Composable
private fun HeroSection(
    title: String,
    body: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
        )
        content()
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFD9EDF5),
        )
    }
}

@Composable
private fun HeroPill(label: String) {
    Surface(
        color = Color.White.copy(alpha = 0.13f),
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
        )
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
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Panel),
        modifier = Modifier.border(1.dp, Stroke, RoundedCornerShape(28.dp)),
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Gravacao inteligente",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
            )
            Text(
                text = indexingStatus,
                style = MaterialTheme.typography.bodyMedium,
                color = MutedText,
            )
            WrapRow(
                horizontalSpacing = 10.dp,
                verticalSpacing = 10.dp,
            ) {
                MetricChipOnSurface("${indexedChunkCount} trechos")
                MetricChipOnSurface("${(micLevel * 100).toInt()}% mic")
            }
            if (partialText.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(
                        text = partialText,
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                    )
                }
            }
            if (lastIndexedPreview.isNotBlank()) {
                Text(
                    text = "Ultimo trecho: $lastIndexedPreview",
                    style = MaterialTheme.typography.bodySmall,
                    color = PurpleSoft,
                )
            }
            if (!isRecording && draftChunks.isEmpty()) {
                Text(
                    text = "Quando a sessao terminar, os trechos reconhecidos aparecem aqui antes de irem para a biblioteca.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedText,
                )
            }
        }
    }
}

@Composable
private fun RecentSessionsPreview(
    sessions: List<AudioSession>,
    sessionStorageBytes: Map<String, Long>,
    onOpenSession: (String) -> Unit,
    onSeeAll: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ScreenTitle("Sessoes recentes", "Audios prontos para consulta.")
            TextButton(onClick = onSeeAll) {
                Text("Ver todas", color = PurpleSoft)
            }
        }
        if (sessions.isEmpty()) {
            EmptyLibraryCard()
        } else {
            sessions.forEach { session ->
                SessionCard(
                    session = session,
                    sessionBytes = sessionStorageBytes[session.id] ?: 0L,
                    onClick = { onOpenSession(session.id) },
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
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Panel),
        modifier = Modifier.border(1.dp, Stroke, RoundedCornerShape(28.dp)),
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionHeader(
                icon = Icons.Rounded.Search,
                title = "Busca semantica",
                subtitle = "Procure por contexto, nomes, decisoes e frases parecidas em todo o historico.",
            )
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Buscar na memoria") },
                placeholder = { Text("Ex.: o prazo do cliente ou a decisao da reuniao") },
                singleLine = true,
            )
            if (hits.isEmpty() && query.isNotBlank()) {
                Text(
                    text = "Nenhum trecho relevante encontrado ainda.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MutedText,
                )
            } else {
                hits.forEach { hit ->
                    val sessionTitle = sessions.firstOrNull { it.id == hit.sessionId }?.title ?: "Sessao"
                    SearchHitCard(
                        hit = hit,
                        sessionTitle = sessionTitle,
                        onOpen = { onOpenSession(hit.sessionId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelSettingsPanel(
    uiState: UiState,
    onEngineSelected: (TranscriptionEngine) -> Unit,
    onWhisperModelSelected: (WhisperModel) -> Unit,
    onImportModel: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ScreenTitle(
            title = "Modelos avancados",
            subtitle = "Escolha o motor de captura e prepare processamento offline.",
        )
        DarkPanel {
            Text("Motor de transcricao", style = MaterialTheme.typography.titleMedium, color = Color.White)
            TranscriptionEngine.entries.forEach { engine ->
                OptionRow(
                    icon = Icons.Rounded.Mic,
                    title = engine.label,
                    subtitle = engine.description,
                    selected = uiState.selectedEngine == engine,
                    enabled = !uiState.isRecording,
                    onClick = { onEngineSelected(engine) },
                )
            }
        }
        DarkPanel {
            Text("Modelo Whisper", style = MaterialTheme.typography.titleMedium, color = Color.White)
            WhisperModel.entries.forEach { model ->
                OptionRow(
                    icon = Icons.Rounded.GraphicEq,
                    title = model.title,
                    subtitle = if (uiState.availableWhisperModelIds.contains(model.id)) {
                        "Instalado para refinamento offline"
                    } else {
                        "${model.sizeLabel} para baixar/importar"
                    },
                    selected = uiState.selectedWhisperModel == model,
                    onClick = { onWhisperModelSelected(model) },
                )
            }
            FilledTonalButton(
                onClick = onImportModel,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = Purple,
                    contentColor = Color.White,
                ),
            ) {
                Text(if (uiState.liteRtModelReady) "Trocar modelo LiteRT" else "Importar modelo LiteRT")
            }
        }
        DarkPanel {
            Text("100% offline e seguro", style = MaterialTheme.typography.titleMedium, color = Color.White)
            Text("Processamento local, sem envio automatico para servidores.", color = MutedText)
            Text("Audios e transcricoes permanecem no aparelho.", color = MutedText)
        }
    }
}

@Composable
private fun ExportPanel(
    sessions: List<AudioSession>,
    onOpenSession: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ScreenTitle(
            title = "Exportar",
            subtitle = "Prepare transcricoes e audios para compartilhar com facilidade.",
        )
        DarkPanel {
            Text("Formato da transcricao", style = MaterialTheme.typography.titleMedium, color = Color.White)
            WrapRow(horizontalSpacing = 10.dp, verticalSpacing = 10.dp) {
                listOf("TXT", "PDF", "DOCX", "SRT").forEachIndexed { index, label ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (index == 0) Purple.copy(alpha = 0.28f) else PanelAlt,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (index == 0) Purple else Stroke,
                        ),
                    ) {
                        Text(
                            text = label,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
            ExportToggle("Incluir timestamps", true)
            ExportToggle("Incluir identificacao do falante", false)
        }
        DarkPanel {
            Text("Escolha uma sessao", style = MaterialTheme.typography.titleMedium, color = Color.White)
            if (sessions.isEmpty()) {
                Text("Nenhuma sessao disponivel para exportar.", color = MutedText)
            } else {
                sessions.take(5).forEach { session ->
                    OptionRow(
                        icon = Icons.Rounded.Album,
                        title = session.title,
                        subtitle = "${formatDate(session.createdAt)} - ${session.chunks.size} trechos",
                        selected = false,
                        onClick = { onOpenSession(session.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DarkPanel(content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = Panel),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Stroke, RoundedCornerShape(26.dp)),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun OptionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .background(if (selected) Purple.copy(alpha = 0.20f) else PanelAlt)
            .border(
                1.dp,
                if (selected) Purple else Stroke,
                RoundedCornerShape(16.dp),
            )
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) PurpleSoft else MutedText)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Color.White)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MutedText)
        }
        if (selected) {
            Text("✓", color = PurpleSoft, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun ExportToggle(
    title: String,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = Color.White, style = MaterialTheme.typography.bodyMedium)
        Box(
            modifier = Modifier
                .width(48.dp)
                .height(28.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(if (enabled) Purple else Color(0xFF3A3D49))
                .padding(4.dp),
            contentAlignment = if (enabled) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color.White),
            )
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

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.62f),
            shape = RoundedCornerShape(30.dp),
            color = DetailShell,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Nova nota de texto", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                        Text(
                            "Fluxo separado do audio para registrar ideias, atas e resumos de forma direta.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MutedText,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "Fechar", tint = Color.White)
                    }
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Titulo") },
                    placeholder = { Text("Ex.: Checklist do projeto") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    label = { Text("Conteudo da nota") },
                    placeholder = { Text("Escreva decisoes, nomes, prazos e contexto para testar a busca.") },
                    colors = TextFieldDefaults.colors(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancelar")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onSave(title, body) }, enabled = body.isNotBlank()) {
                        Text("Indexar nota")
                    }
                }
            }
        }
    }
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
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClick = onOpen),
        color = PanelAlt,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
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
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(Icons.Rounded.ArrowOutward, contentDescription = null, tint = PurpleSoft)
            }
            Text(
                text = hit.text,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFE4E5EC),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Trecho em ${formatDuration(hit.timestampMs)}",
                style = MaterialTheme.typography.labelLarge,
                color = PurpleSoft,
            )
        }
    }
}

@Composable
private fun EmptyLibraryCard() {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Panel),
        modifier = Modifier.border(1.dp, Stroke, RoundedCornerShape(28.dp)),
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Sua biblioteca ainda esta vazia.",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
            )
            Text(
                text = "Grave uma sessao, importe um audio ou crie uma nota. Depois compare LiteRT com Whisper turbo nos detalhes.",
                style = MaterialTheme.typography.bodyMedium,
                color = MutedText,
            )
        }
    }
}

@Composable
private fun SessionCard(
    session: AudioSession,
    sessionBytes: Long,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Stroke, RoundedCornerShape(26.dp)),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = Panel),
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
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = formatDate(session.createdAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedText,
                    )
                }
                SessionBadge(session.status)
            }

            Text(
                text = session.note.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MutedText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            WrapRow(
                horizontalSpacing = 10.dp,
                verticalSpacing = 10.dp,
            ) {
                MetricChipOnSurface("Duracao ${formatDuration(session.durationMs)}")
                MetricChipOnSurface("${session.chunks.size} trechos")
                MetricChipOnSurface(formatStorageSize(sessionBytes))
                MetricChipOnSurface(session.sourceType.replace('_', ' '))
            }
        }
    }
}

@Composable
private fun SessionBadge(status: String) {
    val color = when (status) {
        "indexed" -> Color(0xFF11806A)
        "queued" -> Color(0xFF2962FF)
        "transcribing" -> Color(0xFF7C4DFF)
        "needs_transcription" -> Color(0xFFE0821E)
        "failed" -> Color(0xFFD64545)
        else -> Color(0xFF64748B)
    }
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            text = status.replace('_', ' '),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = color,
        )
    }
}

@Composable
private fun SessionDetailDialog(
    session: AudioSession,
    sessionBytes: Long,
    selectedWhisperModel: WhisperModel,
    liteRtModelReady: Boolean,
    whisperStatus: String,
    whisperBusy: Boolean,
    onTranscribeWithLiteRt: () -> Unit,
    onRefineWithLegacyTurbo: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentSeek by remember { mutableLongStateOf(0L) }
    val tabs = listOfNotNull(
        session.liteRtTranscript?.let { "LiteRT" to it },
        session.legacyTurboTranscript?.let { "Whisper turbo" to it },
    )
    var selectedTabIndex by remember(session.id, tabs.size) {
        mutableStateOf(if (tabs.isNotEmpty()) 0 else -1)
    }

    DisposableEffect(session.id) {
        onDispose {
            runCatching { player?.release() }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.92f),
            color = DetailShell,
            shape = RoundedCornerShape(34.dp),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(182.dp)
                        .clip(RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp))
                ) {
                    Image(
                        painter = painterResource(R.drawable.memorywave_header_art),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize(),
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color(0x7F08131F), Color(0xCC10233A))
                                )
                            )
                    )
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(22.dp)
                            .fillMaxWidth(0.8f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = session.title,
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color.White,
                        )
                        Text(
                            text = session.note.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFE8F4FB),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(14.dp)
                            .background(Color.White.copy(alpha = 0.18f), CircleShape)
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = "Fechar", tint = Color.White)
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item {
                        WrapRow(
                            horizontalSpacing = 10.dp,
                            verticalSpacing = 10.dp,
                        ) {
                            MetricChipOnSurface(formatDuration(session.durationMs))
                            MetricChipOnSurface("${session.chunks.size} trechos")
                            MetricChipOnSurface(formatStorageSize(sessionBytes))
                            MetricChipOnSurface(session.transcriptionEngine)
                        }
                    }

                    if (session.audioPath != null) {
                        item {
                            Card(
                                shape = RoundedCornerShape(24.dp),
                                colors = CardDefaults.cardColors(containerColor = Panel),
                            ) {
                                Column(
                                    modifier = Modifier.padding(18.dp),
                                    verticalArrangement = Arrangement.spacedBy(14.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
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
                                                },
                                                modifier = Modifier.background(
                                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                                    shape = CircleShape,
                                                ),
                                            ) {
                                                Icon(
                                                    imageVector = if (isPlaying) Icons.Rounded.PauseCircle else Icons.Rounded.PlayCircle,
                                                    contentDescription = null,
                                                )
                                            }
                                            Column {
                                                Text("Audio salvo", style = MaterialTheme.typography.titleMedium, color = Color.White)
                                                Text(
                                                    "Toque nos blocos de transcricao para pular no ponto certo do audio.",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MutedText,
                                                )
                                            }
                                        }
                                    }

                                    Surface(
                                        color = PanelAlt,
                                        shape = RoundedCornerShape(16.dp),
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(14.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Text(
                                                text = "Status do pipeline",
                                                style = MaterialTheme.typography.labelLarge,
                                                color = PurpleSoft,
                                            )
                                            Text(
                                                text = whisperStatus,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = Color.White,
                                            )
                                            Text(
                                                text = "Modelo legado global: ${selectedWhisperModel.title}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MutedText,
                                            )
                                        }
                                    }

                                    WrapRow(
                                        horizontalSpacing = 10.dp,
                                        verticalSpacing = 10.dp,
                                    ) {
                                        Button(
                                            onClick = onTranscribeWithLiteRt,
                                            enabled = !whisperBusy && liteRtModelReady,
                                            shape = RoundedCornerShape(20.dp),
                                        ) {
                                            Text(
                                                if (!liteRtModelReady) "LiteRT indisponivel"
                                                else if (whisperBusy) "Processando"
                                                else "Transcrever com LiteRT"
                                            )
                                        }
                                        FilledTonalButton(
                                            onClick = onRefineWithLegacyTurbo,
                                            enabled = !whisperBusy,
                                            shape = RoundedCornerShape(20.dp),
                                        ) {
                                            Text(if (whisperBusy) "Processando" else "Refinar com Whisper turbo")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (tabs.isNotEmpty()) {
                        item {
                            Text(
                                text = "Comparacao de qualidade",
                                style = MaterialTheme.typography.titleLarge,
                                color = Color.White,
                            )
                        }
                        item {
                            TabRow(selectedTabIndex = selectedTabIndex.coerceAtLeast(0)) {
                                tabs.forEachIndexed { index, tab ->
                                    Tab(
                                        selected = selectedTabIndex == index,
                                        onClick = { selectedTabIndex = index },
                                        text = { Text(tab.first) },
                                    )
                                }
                            }
                        }
                        item {
                            tabs.getOrNull(selectedTabIndex)?.second?.let { variant ->
                                VariantSummaryCard(variant)
                            }
                        }
                        tabs.getOrNull(selectedTabIndex)?.second?.chunks?.let { variantChunks ->
                            itemsIndexed(
                                variantChunks,
                                key = { index, chunk -> "variant-$selectedTabIndex-$index-${chunk.id}" },
                            ) { _, chunk ->
                                TranscriptChunkCard(
                                    chunk = chunk,
                                    onSeek = {
                                        currentSeek = chunk.startMs
                                        val mediaPlayer = player ?: createPlayer(context, session.audioPath.orEmpty())?.also {
                                            player = it
                                        }
                                        mediaPlayer?.seekTo(chunk.startMs.toInt())
                                        mediaPlayer?.start()
                                        isPlaying = mediaPlayer != null
                                    },
                                )
                            }
                        }
                    }

                    if (session.chunks.isNotEmpty()) {
                        item {
                            Text(
                                text = "Indice ativo usado pela busca",
                                style = MaterialTheme.typography.titleLarge,
                                color = Color.White,
                            )
                        }
                        itemsIndexed(
                            session.chunks,
                            key = { index, chunk -> "active-$index-${chunk.id}" },
                        ) { _, chunk ->
                            TranscriptChunkCard(
                                chunk = chunk,
                                onSeek = {
                                    currentSeek = chunk.startMs
                                    val mediaPlayer = player ?: createPlayer(context, session.audioPath.orEmpty())?.also {
                                        player = it
                                    }
                                    mediaPlayer?.seekTo(chunk.startMs.toInt())
                                    mediaPlayer?.start()
                                    isPlaying = mediaPlayer != null
                                },
                            )
                        }
                    } else {
                        item {
                            Text(
                                text = "Esta sessao ainda nao tem transcricao indexada.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MutedText,
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Panel)
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDelete, enabled = !whisperBusy) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Excluir audio")
                    }
                    Button(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Text("Fechar")
                    }
                }
            }
        }
    }
}

@Composable
private fun VariantSummaryCard(variant: TranscriptVariant) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Panel),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = variant.label,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            WrapRow(
                horizontalSpacing = 10.dp,
                verticalSpacing = 10.dp,
            ) {
                MetricChipOnSurface("${variant.chunks.size} segmentos")
                MetricChipOnSurface("${variant.speechWindowCount} janelas")
                MetricChipOnSurface("${"%.1f".format(Locale.US, variant.speechSeconds)}s fala")
                MetricChipOnSurface("${"%.1f".format(Locale.US, variant.totalSeconds)}s total")
            }
        }
    }
}

@Composable
private fun TranscriptChunkCard(
    chunk: TranscriptChunk,
    onSeek: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onSeek),
        color = Panel,
        shadowElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Purple.copy(alpha = 0.18f))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = formatDuration(chunk.startMs),
                    style = MaterialTheme.typography.labelLarge,
                    color = PurpleSoft,
                )
            }
            Text(
                text = chunk.text,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFE7E8F0),
            )
        }
    }
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
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Purple.copy(alpha = 0.20f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = PurpleSoft)
        }
        Column {
            Text(text = title, style = MaterialTheme.typography.titleLarge, color = Color.White)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MutedText,
            )
        }
    }
}

@Composable
private fun MetricChipOnSurface(label: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = Purple.copy(alpha = 0.16f),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = PurpleSoft,
        )
    }
}

@Composable
private fun WrapRow(
    modifier: Modifier = Modifier,
    horizontalSpacing: Dp = 0.dp,
    verticalSpacing: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    Layout(
        content = content,
        modifier = modifier,
    ) { measurables, constraints ->
        val horizontalGap = horizontalSpacing.roundToPx()
        val verticalGap = verticalSpacing.roundToPx()
        val placeables = measurables.map { measurable ->
            measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
        }

        val rows = mutableListOf<List<androidx.compose.ui.layout.Placeable>>()
        val rowWidths = mutableListOf<Int>()
        val rowHeights = mutableListOf<Int>()
        var currentRow = mutableListOf<androidx.compose.ui.layout.Placeable>()
        var currentWidth = 0
        var currentHeight = 0

        placeables.forEach { placeable ->
            val nextWidth = if (currentRow.isEmpty()) {
                placeable.width
            } else {
                currentWidth + horizontalGap + placeable.width
            }
            if (currentRow.isNotEmpty() && nextWidth > constraints.maxWidth) {
                rows += currentRow
                rowWidths += currentWidth
                rowHeights += currentHeight
                currentRow = mutableListOf()
                currentWidth = 0
                currentHeight = 0
            }

            currentRow += placeable
            currentWidth = if (currentWidth == 0) placeable.width else currentWidth + horizontalGap + placeable.width
            currentHeight = maxOf(currentHeight, placeable.height)
        }

        if (currentRow.isNotEmpty()) {
            rows += currentRow
            rowWidths += currentWidth
            rowHeights += currentHeight
        }

        val contentWidth = rowWidths.maxOrNull() ?: 0
        val contentHeight = rowHeights.sum() + (rowHeights.size - 1).coerceAtLeast(0) * verticalGap
        val width = contentWidth.coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = contentHeight.coerceIn(constraints.minHeight, constraints.maxHeight)

        layout(width, height) {
            var y = 0
            rows.forEachIndexed { index, row ->
                var x = 0
                row.forEach { placeable ->
                    placeable.placeRelative(x, y)
                    x += placeable.width + horizontalGap
                }
                y += rowHeights[index] + verticalGap
            }
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

private fun formatStorageSize(bytes: Long): String {
    if (bytes <= 0L) return "0 KB"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return if (mb >= 1.0) {
        String.format(Locale.US, "%.2f MB", mb)
    } else {
        String.format(Locale.US, "%.1f KB", kb)
    }
}

private fun formatDate(epochMs: Long): String {
    val locale = Locale.Builder().setLanguage("pt").setRegion("BR").build()
    return SimpleDateFormat("dd MMM yyyy, HH:mm", locale).format(Date(epochMs))
}
