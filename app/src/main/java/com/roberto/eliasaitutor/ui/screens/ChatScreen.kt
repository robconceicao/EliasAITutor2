package com.roberto.eliasaitutor.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import com.roberto.eliasaitutor.data.GameConstants
import com.roberto.eliasaitutor.model.*
import com.roberto.eliasaitutor.ui.components.VoiceWaveformVisualizer
import com.roberto.eliasaitutor.network.ConnectionState
import com.roberto.eliasaitutor.network.SocketClient
import com.roberto.eliasaitutor.viewmodel.EliasViewModel
import kotlinx.coroutines.launch

private val Bg      = Color(0xFFF8FAFC) // Very light blue/gray background
private val Surface = Color(0xFFFFFFFF) // White cards
private val Border  = Color(0xFFE2E8F0) // Soft gray borders
private val Accent  = Color(0xFF3B82F6) // Bright Blue for user/actions
private val Gold    = Color(0xFFFBBF24) // Warm Amber for gamification/coins
private val Green   = Color(0xFF10B981) // Emerald for correct/positive
private val Red     = Color(0xFFEF4444) // Soft Red for errors/frustration
private val Muted   = Color(0xFF64748B) // Slate 500 for secondary text
private val Purple  = Color(0xFF8B5CF6) // Violet
private val TextMain= Color(0xFF1E293B) // Dark text for light theme


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: EliasViewModel) {
    val profile       by vm.profile.collectAsState()
    val bubbles       by vm.chatBubbles.collectAsState()
    val isLoading     by vm.isLoading.collectAsState()
    val loadError     by vm.loadError.collectAsState()
    val scenario      by vm.selectedScenario.collectAsState()
    val programChat   by vm.programChat.collectAsState()
    val quiz          by vm.quiz.collectAsState()
    val quizAnswered  by vm.quizAnswered.collectAsState()
    val inProgram = programChat != null

    var inputText     by remember { mutableStateOf("") }
    var showQuiz      by remember { mutableStateOf(false) }
    var quizChosen    by remember { mutableStateOf(-1) }
    var quizResult    by remember { mutableStateOf<Boolean?>(null) }
    val isRecording   by vm.isRecording.collectAsState()
    val isIaSpeaking  by vm.isIaSpeaking.collectAsState()
    
    val rms           by vm.userVoiceRms.collectAsState()
    val connectionState by SocketClient.connectionState.collectAsState()
    val jitterStats   by vm.jitterStats.collectAsState()
 
    val listState     = rememberLazyListState()
    val scope         = rememberCoroutineScope()
    val context       = androidx.compose.ui.platform.LocalContext.current
    val sheetState    = rememberModalBottomSheetState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) vm.startRecording(context)
    }

    // Auto-scroll to bottom
    LaunchedEffect(bubbles.size) {
        if (bubbles.isNotEmpty()) listState.animateScrollToItem(bubbles.size - 1)
    }

    Column(Modifier.fillMaxSize().background(Bg).padding(12.dp)) {

        // ── Header: program mode vs free chat scenario ───────────────────
        val statusColor = when (connectionState) {
            ConnectionState.CONNECTED -> Green
            ConnectionState.CONNECTING -> Gold
            ConnectionState.RECONNECTING -> Color(0xFFff9800)
            ConnectionState.DISCONNECTED -> Red
        }
        val statusText = when (connectionState) {
            ConnectionState.CONNECTED -> "Online"
            ConnectionState.CONNECTING -> "Connecting..."
            ConnectionState.RECONNECTING -> "Reconnecting..."
            ConnectionState.DISCONNECTED -> "Offline"
        }

        if (inProgram) {
            val pc = programChat!!
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Elias · Semana ${pc.week}",
                            color = Accent,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(statusColor.copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Box(
                                Modifier.size(6.dp).clip(CircleShape).background(statusColor)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(statusText, color = statusColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text(pc.title, color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    // A.1: CEFR always from program_weeks.level — never free-chat picker
                    if (pc.level.isNotBlank()) {
                        Text(
                            "Nível ${pc.level} (semana ${pc.week})",
                            color = Accent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    if (pc.lexis.isNotBlank()) {
                        Text("Tema: ${pc.lexis}", color = Muted, fontSize = 11.sp)
                    }
                    Text(
                        "Pronúncia Máxima: IPA · schwa · linking · elisão · entonação · shadowing",
                        color = Purple,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Text(
                        "🇧🇷 Traduzir sob a mensagem · ou diga “não entendi / traduz pra mim”",
                        color = Muted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        } else {
            var scenarioExpanded by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Scenario:", color = Muted, fontSize = 13.sp)
                Spacer(Modifier.width(8.dp))
                Box {
                    OutlinedButton(onClick = { scenarioExpanded = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent),
                        border = BorderStroke(1.dp, Border)) {
                        Text(scenario.ifBlank { "Livre" }, fontSize = 13.sp)
                    }
                    DropdownMenu(scenarioExpanded, { scenarioExpanded = false },
                        Modifier.background(Surface)) {
                        GameConstants.SCENARIOS.forEach { (name, data) ->
                            val locked = profile.level < data.first && name !in profile.unlockedScenarios
                            DropdownMenuItem(
                                text = { Text("$name ${if (locked) "🔒" else ""}", color = if (locked) Muted else TextMain) },
                                onClick = { vm.selectScenario(name); scenarioExpanded = false },
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                val bonus = GameConstants.SCENARIOS[scenario]?.second ?: 0
                if (bonus > 0) {
                    Text("+$bonus XP", color = Green, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(12.dp))
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(statusColor.copy(alpha = 0.15f))
                        .border(1.dp, statusColor.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(statusText, color = statusColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── Chat messages ──────────────────────────────────────────────────
        LazyColumn(state = listState, modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {

            // FREE only: level chips. PROGRAM never asks level (A.1).
            if (bubbles.isEmpty() && !inProgram) {
                item {
                    LevelSelectionBox { level ->
                        vm.sendMessage(level)
                    }
                }
            }
            if (bubbles.isEmpty() && inProgram && isLoading) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                "Elias está preparando a abertura…",
                                color = Accent,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                            Text(
                                "Semana ${programChat?.week ?: "—"} · TTS streaming · 🇧🇷 Traduzir se precisar",
                                color = Muted,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }
            // A.5: timed-out wait — show error + retry, never infinite "Carregando…"
            if (!loadError.isNullOrBlank()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF7ED)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(loadError!!, color = TextMain, fontSize = 13.sp)
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = {
                                vm.clearLoadError()
                                if (inProgram && bubbles.isEmpty()) {
                                    val pc = programChat!!
                                    vm.beginProgramSession(
                                        week = pc.week ?: 1,
                                        title = pc.title,
                                        lexis = pc.lexis,
                                        grammar = pc.grammar,
                                        phase = pc.phase,
                                        sessionType = pc.sessionType,
                                        userId = profile.userId.ifBlank { "local_user" },
                                        level = pc.level,
                                    )
                                }
                            }) {
                                Text("Tentar novamente", color = Accent)
                            }
                        }
                    }
                }
            }
            items(bubbles.size) { index ->
                val bubble = bubbles[index]
                if (bubble.isUser) UserBubble(bubble.message)
                else EliasBubble(
                    bubble = bubble,
                    onListen = { vm.speakText(bubble.message) },
                    onTranslate = { vm.translateBubble(index) },
                )
            }

            if (isLoading || isRecording || isIaSpeaking) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        VoiceWaveformVisualizer(
                            rms = rms,
                            isRecording = isRecording,
                            isIaSpeaking = isIaSpeaking,
                            isLoading = isLoading
                        )
                        if (isIaSpeaking && jitterStats != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "Jitter: ${jitterStats!!.jitterMs}ms | Target Delay: ${jitterStats!!.targetDelayMs}ms | Loss: ${jitterStats!!.packetLoss}",
                                color = Muted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

        }

        Spacer(Modifier.height(8.dp))

        // ── Modal Bottom Sheet for Quiz ────────────────────────────────────
        if (showQuiz) {
            ModalBottomSheet(
                onDismissRequest = { showQuiz = false },
                sheetState = sheetState,
                containerColor = Surface
            ) {
                QuizPanel(
                    quiz         = quiz,
                    answered     = quizAnswered,
                    chosen       = quizChosen,
                    result       = quizResult,
                    onGenerate   = { vm.generateQuiz(); quizChosen = -1; quizResult = null },
                    onChoose     = { quizChosen = it },
                    onSubmit     = {
                        if (quizChosen >= 0) {
                            quizResult = vm.submitQuizAnswer(quizChosen)
                        }
                    },
                    onClose      = { showQuiz = false },
                )
                Spacer(Modifier.height(16.dp))
            }
        }

        // ── Input row ──────────────────────────────────────────────────────
        val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "pulse")
        val pulseAlpha by infiniteTransition.animateFloat(
            initialValue = 0.2f, targetValue = 0.6f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                animation = androidx.compose.animation.core.tween(800),
                repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
            ),
            label = "pulseAlpha"
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextField(
                value = inputText, onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type in English...", color = Muted, fontSize = 14.sp) },
                colors = TextFieldDefaults.colors(
                    unfocusedContainerColor = Surface, focusedContainerColor = Surface,
                    unfocusedIndicatorColor = Color.Transparent, focusedIndicatorColor = Color.Transparent,
                    unfocusedTextColor = TextMain, focusedTextColor = TextMain,
                ),
                shape = RoundedCornerShape(24.dp), maxLines = 3,
            )
            IconButton(onClick = {
                val hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

                if (hasPermission) {
                    if (isRecording) {
                        vm.stopRecording(context)
                    } else {
                        vm.startRecording(context)
                    }
                } else {

                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }, enabled = !isLoading,
               modifier = Modifier
                   .size(56.dp) // Make it slightly larger
                   .background(if (isRecording) Red.copy(alpha = pulseAlpha) else Accent.copy(alpha = 0.1f), CircleShape)
            ) {
                Icon(
                    if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                    contentDescription = "Mic",
                    tint = if (isRecording) Red else Accent
                )
            }
            IconButton(onClick = {
                if (inputText.isNotBlank() && !isLoading) {
                    vm.sendMessage(inputText.trim())
                    inputText = ""
                }
            }, enabled = !isLoading,
                modifier = Modifier.background(Surface, CircleShape)) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Accent)
            }
        }

        // ── Quiz toggle ────────────────────────────────────────────────────
        TextButton(onClick = { showQuiz = !showQuiz }) {
            Text(if (showQuiz) "Hide Quiz" else "🧠 Quiz (+${GameConstants.QUIZ_COINS}🪙)",
                color = Gold, fontSize = 12.sp)
        }
    }
}

// ── Sub-composables ────────────────────────────────────────────────────────────

@Composable
private fun LevelSelectionBox(onLevelSelected: (String) -> Unit) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
        .background(Surface).border(1.dp, Border, RoundedCornerShape(16.dp))
        .padding(16.dp)) {
        Column {
            Text("👋 Hey! I'm Elias — your American English tutor.",
                color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("To get started, tell me your current English level:",
                color = Muted, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                "💡 Chat livre: Elias fala em inglês. Não entendeu? Toque 🇧🇷 Traduzir na mensagem ou diga: “não entendi, traduz pra mim”.",
                color = Gold, fontSize = 12.sp, lineHeight = 16.sp
            )
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onLevelSelected("Beginner") }, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2d4070)),
                    shape = RoundedCornerShape(12.dp)) { Text("Beginner", fontSize = 14.sp) }
                Button(onClick = { onLevelSelected("Intermediate") }, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    shape = RoundedCornerShape(12.dp)) { Text("Intermediate", fontSize = 14.sp) }
                Button(onClick = { onLevelSelected("Advanced") }, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Purple),
                    shape = RoundedCornerShape(12.dp)) { Text("Advanced", fontSize = 14.sp) }
            }
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(Modifier.widthIn(max = 280.dp)
            .clip(RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp))
            .background(Accent)
            .padding(16.dp, 12.dp)) {
            Text(text, color = Color.White, fontSize = 16.sp, lineHeight = 24.sp)
        }
    }
}

@Composable
private fun EliasBubble(
    bubble: UiChatBubble,
    onListen: () -> Unit,
    onTranslate: () -> Unit,
) {
    val sentimentColor = when (bubble.sentiment) {
        "frustrated"   -> Red
        "enthusiastic" -> Green
        "confused"     -> Gold
        else           -> Border
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.Bottom) {
        // Elias Avatar
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Surface)
                .border(2.dp, sentimentColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = androidx.compose.ui.res.painterResource(id = com.roberto.eliasaitutor.R.drawable.avatar_elias),
                contentDescription = "Elias Avatar",
                modifier = Modifier.fillMaxSize().clip(CircleShape),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).widthIn(max = 300.dp)) {
            Box(Modifier.clip(RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp))
                .background(Surface)
                .border(1.dp, Border, RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp))
                .padding(16.dp, 14.dp)) {
                Column {
                    val parsedMessage = parseMarkdownToAnnotatedString(bubble.message)
                    Text(parsedMessage, color = TextMain, fontSize = 16.sp, lineHeight = 24.sp)

                    // A.3 — tradução contextual discreta (PT sob o inglês; original intacto)
                    if (bubble.isTranslating) {
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider(color = Border, thickness = 1.dp)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Traduzindo… (máx. ~12s)",
                            color = Muted,
                            fontSize = 12.sp,
                            fontStyle = FontStyle.Italic
                        )
                    } else if (!bubble.translationPt.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider(color = Border, thickness = 1.dp)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Tradução",
                            color = Purple.copy(alpha = 0.85f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            bubble.translationPt!!,
                            color = Muted,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            fontStyle = FontStyle.Italic
                        )
                    } else if (!bubble.translationError.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider(color = Border, thickness = 1.dp)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            bubble.translationError!!,
                            color = Red.copy(alpha = 0.9f),
                            fontSize = 12.sp
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                    ) {
                        FilledTonalButton(
                            onClick = onTranslate,
                            enabled = !bubble.isTranslating && bubble.translationPt.isNullOrBlank(),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = Purple.copy(alpha = 0.12f),
                                contentColor = Purple
                            ),
                            modifier = Modifier.height(32.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                        ) {
                            Text("🇧🇷", fontSize = 12.sp)
                            Spacer(Modifier.width(4.dp))
                            Text(
                                when {
                                    bubble.isTranslating -> "…"
                                    !bubble.translationPt.isNullOrBlank() -> "OK"
                                    !bubble.translationError.isNullOrBlank() -> "Tentar de novo"
                                    else -> "Traduzir"
                                },
                                fontSize = 12.sp
                            )
                        }
                        FilledTonalButton(
                            onClick = onListen,
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = Accent.copy(alpha = 0.1f),
                                contentColor = Accent
                            ),
                            modifier = Modifier.height(32.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                        ) {
                            Icon(Icons.Default.VolumeUp, contentDescription = "Listen", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Ouvir", fontSize = 12.sp)
                        }
                    }
                }
            }
            if (bubble.vocabulary.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Box(Modifier.clip(RoundedCornerShape(16.dp)).background(Accent.copy(alpha=0.05f)).border(1.dp, Accent.copy(alpha=0.2f), RoundedCornerShape(16.dp)).padding(12.dp).fillMaxWidth()) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("💡", fontSize = 12.sp)
                            Spacer(Modifier.width(4.dp))
                            Text("Vocabulary Highlight", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(6.dp))
                        bubble.vocabulary.forEach { v ->
                            Text("• $v", color = Accent, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(bottom = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

fun parseMarkdownToAnnotatedString(text: String): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end != -1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        append(text[i])
                        i++
                    }
                }
                text.startsWith("*", i) -> {
                    val end = text.indexOf("*", i + 1)
                    if (end != -1 && end > i + 1) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append(text[i])
                        i++
                    }
                }
                else -> {
                    append(text[i])
                    i++
                }
            }
        }
    }
}

@Composable
private fun TypingIndicator() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(16.dp), color = Accent, strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text("Elias is thinking...", color = Muted, fontSize = 13.sp)
    }
}

@Composable
private fun QuizPanel(
    quiz: QuizQuestion?, answered: Boolean, chosen: Int, result: Boolean?,
    onGenerate: () -> Unit, onChoose: (Int) -> Unit, onSubmit: () -> Unit, onClose: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = Surface),
        border = BorderStroke(1.dp, Gold), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🧠 Vocab Quiz", color = Gold, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("✕", color = Muted) }
            }
            if (quiz == null) {
                TextButton(onClick = onGenerate) {
                    Text("Generate Quiz Question →", color = Accent)
                }
            } else {
                Text(quiz.question, color = TextMain, fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 6.dp))
                val letters = listOf("A", "B", "C", "D")
                quiz.options.forEachIndexed { i, opt ->
                    val bg = when {
                        answered && i == quiz.correctIndex -> Green.copy(alpha = 0.15f)
                        answered && i == chosen && i != quiz.correctIndex -> Red.copy(alpha = 0.15f)
                        i == chosen -> Accent.copy(alpha = 0.15f)
                        else -> Color.Transparent
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                        .clip(RoundedCornerShape(8.dp)).background(bg)
                        .clickable(enabled = !answered) { onChoose(i) }
                        .padding(8.dp, 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = chosen == i, onClick = { if (!answered) onChoose(i) },
                            enabled = !answered, colors = RadioButtonDefaults.colors(selectedColor = Accent))
                        Text("${letters[i]}. $opt", color = TextMain, fontSize = 13.sp)
                    }
                }
                if (!answered) {
                    Button(onClick = onSubmit, enabled = chosen >= 0,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                        Text("Submit Answer")
                    }
                } else {
                    Text(if (result == true) "🎉 Correct! +${GameConstants.QUIZ_COINS}🪙 +${GameConstants.QUIZ_XP}XP"
                         else "❌ Not quite.", color = if (result == true) Green else Red,
                        fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("💡 ${quiz.explanation}", color = Muted, fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp))
                    TextButton(onClick = { onGenerate() }) { Text("Next Question →", color = Accent) }
                }
            }
        }
    }
}
