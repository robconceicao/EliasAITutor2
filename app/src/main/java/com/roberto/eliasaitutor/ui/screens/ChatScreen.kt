package com.roberto.eliasaitutor.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roberto.eliasaitutor.data.GameConstants
import com.roberto.eliasaitutor.model.*
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

    val listState     = rememberLazyListState()
    val scope         = rememberCoroutineScope()

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
            if (bonus > 0) Text("+$bonus XP", color = Green, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(8.dp))

        // ── Chat messages ──────────────────────────────────────────────────
        LazyColumn(state = listState, modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {

            if (bubbles.isEmpty()) {
                item {
                    EliasWelcomeBubble()
                }
            }
            items(bubbles) { bubble ->
                if (bubble.isUser) UserBubble(bubble.message)
                else EliasBubble(bubble)
            }
            if (isLoading) {
                item { TypingIndicator() }
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = inputText, onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type in English...", color = Muted) },
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = Border, focusedBorderColor = Accent,
                    unfocusedContainerColor = Surface, focusedContainerColor = Surface,
                    unfocusedTextColor = Color(0xFFe8eaf0), focusedTextColor = Color(0xFFe8eaf0),
                ),
                shape = RoundedCornerShape(12.dp), maxLines = 3,
            )
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
private fun EliasWelcomeBubble() {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp))
        .background(Surface).border(1.dp, Border, RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp))
        .padding(12.dp)) {
        Text("👋 Hey! I'm Elias — your American English tutor from San Diego. " +
            "Jump right in and I'll correct mistakes and teach vocabulary as we go. " +
            "This is a totally safe space! 😊",
            color = Color(0xFFe8eaf0), fontSize = 14.sp, lineHeight = 20.sp)
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(Modifier.widthIn(max = 280.dp)
            .clip(RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp))
            .background(Color(0xFF1e2a45)).border(1.dp, Color(0xFF2d4070), RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp))
            .padding(10.dp, 8.dp)) {
            Text("👤 $text", color = Color(0xFFe8eaf0), fontSize = 14.sp)
        }
    }
}

@Composable
private fun EliasBubble(bubble: UiChatBubble) {
    val sentimentColor = when (bubble.sentiment) {
        "frustrated"   -> Red
        "enthusiastic" -> Green
        "confused"     -> Gold
        else           -> Border
    }
    Column(Modifier.fillMaxWidth().widthIn(max = 320.dp)) {
        Box(Modifier.clip(RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp))
            .background(Surface).border(1.dp, sentimentColor, RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp))
            .padding(10.dp, 8.dp)) {
            Text("🎓 ${bubble.message}", color = Color(0xFFe8eaf0), fontSize = 14.sp, lineHeight = 20.sp)
        }
        if (bubble.vocabulary.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text("📚 VOCABULARY", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            bubble.vocabulary.forEach { v ->
                Text("• $v", color = Accent, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp))
            }
        }
        if (bubble.mistakes.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text("⚠ CORRECTIONS", color = Red, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            bubble.mistakes.forEach { m ->
                if (m.raw.isNotEmpty()) {
                    Text("• ${m.raw}", color = Red, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp))
                } else {
                    Row {
                        Text("✗ ${m.wrong}", color = Red, fontSize = 12.sp)
                        Text(" → ", color = Muted, fontSize = 12.sp)
                        Text("✓ ${m.right}", color = Green, fontSize = 12.sp)
                    }
                    if (m.rule.isNotEmpty()) Text("  Rule: ${m.rule}", color = Muted, fontSize = 11.sp)
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