package com.roberto.eliasaitutor.ui.screens

import androidx.compose.animation.animateContentSize
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
import androidx.compose.material.icons.filled.Send
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

private val Bg      = Color(0xFF0d0f14)
private val Surface = Color(0xFF161922)
private val Border  = Color(0xFF252a35)
private val Accent  = Color(0xFF4f8ef7)
private val Gold    = Color(0xFFf7c94f)
private val Green   = Color(0xFF3ecf8e)
private val Red     = Color(0xFFf76f6f)
private val Muted   = Color(0xFF7a8099)
private val Purple  = Color(0xFFa855f7)

@Composable
fun ChatScreen(vm: EliasViewModel) {
    val profile       by vm.profile.collectAsState()
    val bubbles       by vm.chatBubbles.collectAsState()
    val isLoading     by vm.isLoading.collectAsState()
    val scenario      by vm.selectedScenario.collectAsState()
    val quiz          by vm.quiz.collectAsState()
    val quizAnswered  by vm.quizAnswered.collectAsState()

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

        // ── Scenario selector ──────────────────────────────────────────────
        var scenarioExpanded by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Scenario:", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.width(8.dp))
            Box {
                OutlinedButton(onClick = { scenarioExpanded = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent),
                    border = BorderStroke(1.dp, Border)) {
                    Text(scenario, fontSize = 13.sp)
                }
                DropdownMenu(scenarioExpanded, { scenarioExpanded = false },
                    Modifier.background(Surface)) {
                    GameConstants.SCENARIOS.forEach { (name, data) ->
                        val locked = profile.level < data.first && name !in profile.unlockedScenarios
                        DropdownMenuItem(
                            text = { Text("$name ${if (locked) "🔒" else ""}", color = if (locked) Muted else MaterialTheme.colorScheme.onSurface) },
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
            
            // Connection Status Badge
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

        Spacer(Modifier.height(8.dp))

        // ── Chat messages ──────────────────────────────────────────────────
        LazyColumn(state = listState, modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {

            if (bubbles.isEmpty()) {
                item {
                    LevelSelectionBox { level ->
                        vm.sendMessage(level)
                    }
                }
            }
            items(bubbles) { bubble ->
                if (bubble.isUser) UserBubble(bubble.message)
                else EliasBubble(bubble, vm)
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

        // ── Quiz panel ─────────────────────────────────────────────────────
        if (showQuiz) {
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
            Spacer(Modifier.height(8.dp))
        }

        // ── Input row ──────────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextField(
                value = inputText, onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type in English...", color = Muted, fontSize = 14.sp) },
                colors = TextFieldDefaults.colors(
                    unfocusedContainerColor = Color(0xFF1E2638), focusedContainerColor = Color(0xFF1E2638),
                    unfocusedIndicatorColor = Color.Transparent, focusedIndicatorColor = Color.Transparent,
                    unfocusedTextColor = Color(0xFFe8eaf0), focusedTextColor = Color(0xFFe8eaf0),
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
               modifier = Modifier.background(if (isRecording) Red.copy(alpha = 0.2f) else Color.Transparent, CircleShape)
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
            }, enabled = !isLoading) {
                Icon(Icons.Default.Send, contentDescription = "Send", tint = Accent)
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
                color = Color(0xFFe8eaf0), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("To get started, tell me your current English level:",
                color = Muted, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            Text("💡 Tip: Elias speaks 100% English. If you don't understand something, press the Mic and say: 'Elias, não entendi, traduz pra mim?'",
                color = Gold, fontSize = 12.sp, lineHeight = 16.sp)
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
            .clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
            .background(Accent)
            .padding(14.dp, 12.dp)) {
            Text(text, color = Color.White, fontSize = 15.sp, lineHeight = 22.sp)
        }
    }
}

@Composable
private fun EliasBubble(bubble: UiChatBubble, vm: EliasViewModel) {
    val sentimentColor = when (bubble.sentiment) {
        "frustrated"   -> Red
        "enthusiastic" -> Green
        "confused"     -> Gold
        else           -> Border
    }
    Column(Modifier.fillMaxWidth().widthIn(max = 320.dp)) {
        Box(Modifier.clip(RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp))
            .background(Color(0xFF1E2638))
            .border(1.dp, sentimentColor.copy(alpha = 0.5f), RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp))
            .padding(16.dp, 14.dp)) {
            val parsedMessage = parseMarkdownToAnnotatedString(bubble.message)
            Text(parsedMessage, color = Color(0xFFe8eaf0), fontSize = 15.sp, lineHeight = 22.sp)
        }
        if (bubble.vocabulary.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF161922)).padding(12.dp).fillMaxWidth()) {
                Column {
                    Text("📚 VOCABULARY", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    bubble.vocabulary.forEach { v ->
                        Text("• $v", color = Accent, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(bottom = 4.dp))
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
                Text(quiz.question, color = Color(0xFFe8eaf0), fontSize = 14.sp,
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
                        Text("${letters[i]}. $opt", color = Color(0xFFe8eaf0), fontSize = 13.sp)
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