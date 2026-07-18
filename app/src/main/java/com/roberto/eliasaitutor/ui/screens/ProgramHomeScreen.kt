package com.roberto.eliasaitutor.ui.screens

import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roberto.eliasaitutor.model.PronunciationFocus
import com.roberto.eliasaitutor.program.ProgramDates
import com.roberto.eliasaitutor.program.ProgramPhaseUi
import com.roberto.eliasaitutor.program.ProgramSessionType
import com.roberto.eliasaitutor.program.ProgramViewModel
import com.roberto.eliasaitutor.ui.theme.EliasTokens
import java.time.LocalDate

private val Bg = EliasTokens.Bg
private val Surface = EliasTokens.Surface
private val Accent = EliasTokens.Accent
private val Muted = EliasTokens.Muted
private val TextMain = EliasTokens.TextMain

@Composable
fun ProgramHomeScreen(
    programVm: ProgramViewModel,
    userId: String,
    onStartChat: (week: Int, title: String, lexis: String, grammar: String, phase: Int, sessionType: String, goalMinutes: Int, level: String) -> Unit,
    onOpenProgress: () -> Unit = {},
) {
    val ui by programVm.ui.collectAsState()
    val practice by programVm.practice.collectAsState()
    val drill by programVm.drill.collectAsState()
    val feedback by programVm.feedback.collectAsState()
    val feedbackStatus by programVm.feedbackStatus.collectAsState()
    val context = LocalContext.current

    var showSettings by remember { mutableStateOf(false) }
    var showQuickPick by remember { mutableStateOf(false) }
    var showOnboarding by remember { mutableStateOf(false) }
    var showWeeklyQuiz by remember { mutableStateOf(false) }
    val checkpointMsg by programVm.checkpointMsg.collectAsState()

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not — schedule still attempted */ }

    LaunchedEffect(ui.onboarded, ui.loading) {
        if (!ui.loading && !ui.onboarded) showOnboarding = true
    }

    LaunchedEffect(practice?.goalReachedNotified) {
        if (practice?.goalReachedNotified == true) {
            Toast.makeText(context, "Meta diária atingida! Pode continuar.", Toast.LENGTH_SHORT).show()
            try {
                @Suppress("DEPRECATION")
                (context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator)
                    ?.vibrate(80)
            } catch (_: Exception) {}
        }
    }

    when {
        showWeeklyQuiz -> {
            WeeklyQuizScreen(
                programVm = programVm,
                onClose = { showWeeklyQuiz = false },
            )
            return
        }
        drill != null && drill?.done != true -> {
            ChunksDrillScreen(programVm)
            return
        }
        feedback != null -> {
            SessionFeedbackScreen(
                feedback = feedback!!,
                onDismiss = { programVm.clearFeedback() },
            )
            return
        }
        feedbackStatus == "failed" -> {
            AlertDialog(
                onDismissRequest = { programVm.clearFeedback() },
                title = { Text("Relatório indisponível") },
                text = { Text("Não foi possível gerar o relatório de correção. A sessão foi salva.") },
                confirmButton = {
                    TextButton(onClick = { programVm.clearFeedback() }) { Text("OK") }
                },
            )
        }
        showOnboarding -> {
            ProgramOnboardingDialog(
                onConfirm = { date, mode ->
                    if (Build.VERSION.SDK_INT >= 33) {
                        notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                    programVm.completeOnboarding(date, mode)
                    showOnboarding = false
                }
            )
        }
        showSettings -> {
            ProgramSettingsScreen(
                state = ui.state,
                onBack = { showSettings = false },
                onSave = { start, mode, reminder, goal ->
                    programVm.setStartDate(start)
                    programVm.setWeekMode(mode)
                    programVm.setReminderTime(reminder)
                    programVm.setDailyGoal(goal)
                    showSettings = false
                }
            )
            return
        }
        showQuickPick -> {
            AlertDialog(
                onDismissRequest = { showQuickPick = false },
                title = { Text("Conversa rápida com Elias") },
                text = {
                    Text("Escolha a meta. Relatório completo de pronúncia exige ≥10 min.")
                },
                confirmButton = {
                    TextButton(onClick = {
                        showQuickPick = false
                        programVm.startConversationSession(
                            ProgramSessionType.QUICK, 5,
                        ) { week, title, lexis, grammar, phase, level ->
                            onStartChat(
                                week, title, lexis, grammar, phase,
                                ProgramSessionType.QUICK.apiValue, 5, level,
                            )
                        }
                    }) { Text("5 min") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showQuickPick = false
                        programVm.startConversationSession(
                            ProgramSessionType.QUICK, 10,
                        ) { week, title, lexis, grammar, phase, level ->
                            onStartChat(
                                week, title, lexis, grammar, phase,
                                ProgramSessionType.QUICK.apiValue, 10, level,
                            )
                        }
                    }) { Text("10 min") }
                },
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .verticalScroll(rememberScrollState())
    ) {
        // Fase 4 — hero wireframe
        val weekPreview = ui.week
        val journeyFrac = ((ui.state.currentWeek - 1).coerceAtLeast(0) / 25f).coerceIn(0f, 1f)
        Box(
            Modifier
                .fillMaxWidth()
                .background(EliasTokens.HeroBrush)
                .padding(16.dp)
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "ELIAS · FLUÊNCIA",
                            color = Accent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.5.sp
                        )
                        Text(
                            "Programa 6 meses",
                            color = TextMain,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                        val start = ui.state.startDate
                        val targetBr = if (start.isNotBlank()) {
                            ProgramDates.targetDateBr(start)
                        } else {
                            "6 meses após o início"
                        }
                        Text(
                            "C1 · General American · meta $targetBr",
                            color = Muted,
                            fontSize = 12.sp
                        )
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Configurações", tint = TextMain)
                    }
                }
                Spacer(Modifier.height(14.dp))
                val programDay = when {
                    ui.state.programDay > 0 -> ui.state.programDay
                    ui.state.startDate.isNotBlank() -> ProgramDates.programDay(ui.state.startDate)
                    else -> 1
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        color = EliasTokens.Teal.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Text(
                            "Dia $programDay",
                            color = EliasTokens.Teal,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                    Surface(
                        color = Accent.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Text(
                            "Semana ${ui.state.currentWeek}/26",
                            color = Accent,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                    Surface(
                        color = EliasTokens.Purple.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Text(
                            "Nível ${weekPreview?.level ?: "—"}",
                            color = EliasTokens.Purple,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                    if (ui.state.heldBack) {
                        Surface(
                            color = EliasTokens.Orange.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Text(
                                "Revisão",
                                color = EliasTokens.Orange,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
                if (ui.state.progressHint.isNotBlank() || ui.state.nextWeekLocked) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        ui.state.progressHint.ifBlank {
                            "Complete o Quiz da Semana ${ui.state.currentWeek} (≥70%) para desbloquear a próxima aula."
                        },
                        color = if (ui.state.nextWeekLocked) EliasTokens.Orange else Muted,
                        fontSize = 12.sp,
                        fontWeight = if (ui.state.nextWeekLocked) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text("Jornada A1 → C1", color = Muted, fontSize = 11.sp)
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { journeyFrac },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = EliasTokens.Teal,
                    trackColor = EliasTokens.Border,
                )
            }
        }

        Column(Modifier.padding(horizontal = 16.dp)) {

        if (ui.offline || ui.error != null) {
            Surface(
                color = Color(0xFF3D2E00),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        ui.error ?: "Offline — exibindo cache local",
                        color = Color(0xFFFFD54F),
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    // A.5: every error state must offer a visible retry
                    TextButton(onClick = { programVm.refresh() }) {
                        Text("Tentar novamente", color = Color(0xFFFFD54F))
                    }
                }
            }
        }

        if (ui.loading) {
            Column(
                Modifier.fillMaxWidth().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(color = Accent)
                Spacer(Modifier.height(12.dp))
                Text("Carregando programa…", color = Muted, fontSize = 13.sp)
                Text("máx. 10s", color = Muted, fontSize = 11.sp)
            }
            return
        }

        val week = ui.week
        val phase = ProgramPhaseUi.info(week?.phase ?: ui.state.currentWeek.let {
            when {
                it <= 6 -> 1
                it <= 13 -> 2
                it <= 20 -> 3
                else -> 4
            }
        })
        val phaseColor = Color(phase.color)

        // Week card
        Card(
            colors = CardDefaults.cardColors(containerColor = Surface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = phaseColor.copy(alpha = 0.2f), shape = RoundedCornerShape(6.dp)) {
                        Text(
                            "Fase ${week?.phase ?: "—"} · ${phase.name}",
                            color = phaseColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("Nível ${week?.level ?: ""}", color = Muted, fontSize = 12.sp)
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Semana ${ui.state.currentWeek}",
                    color = Accent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    week?.title ?: "Semana ${ui.state.currentWeek}",
                    color = TextMain,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text("Tema: ${week?.lexis ?: "—"}", color = Muted, fontSize = 13.sp)
                Text("Gramática: ${week?.grammar ?: "—"}", color = Muted, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Modo Pronúncia Avançada Máxima · IPA · schwa · linking · elisão · entonação",
                    color = Accent.copy(alpha = 0.9f),
                    fontSize = 12.sp
                )

                if (ui.state.weekMode == "manual") {
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { programVm.shiftWeek(-1) },
                            enabled = ui.state.currentWeek > 1
                        ) { Text("−1 sem.") }
                        OutlinedButton(
                            onClick = { programVm.shiftWeek(1) },
                            enabled = ui.state.currentWeek < 26
                        ) { Text("+1 sem.") }
                    }
                } else {
                    val startBr = ProgramDates.startDateBr(ui.state.startDate)
                    val targetBr = ProgramDates.targetDateBr(ui.state.startDate)
                    Text(
                        "Avanço automático · início $startBr · meta C1 $targetBr",
                        color = Muted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }

        // Daily progress
        val goal = ui.state.dailyGoalMinutes.coerceAtLeast(1)
        val today = ui.progress.todayMinutes
        val frac = (today.toFloat() / goal).coerceIn(0f, 1f)
        Card(
            colors = CardDefaults.cardColors(containerColor = Surface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Conversação: ${today} / ${goal} min", color = TextMain, fontWeight = FontWeight.SemiBold)
                    Text("🔥 ${ui.progress.streak} dias", color = Color(0xFFFF8A65))
                }
                Text(
                    "Meta sagrada: 30 min de conversa ativa com Elias",
                    color = Muted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { frac },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = Accent,
                    trackColor = Color(0xFF2A2E3A),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Dia ideal: 90 min estudo (Anki · teoria · Feynman · input) + 30 min conversa",
                    color = Muted,
                    fontSize = 11.sp
                )
            }
        }

        // B.4 review mode: FULL practice round (conversation + chunks), quiz only at the end
        if (ui.state.heldBack) {
            Spacer(Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1A0A)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Modo revisão",
                        color = Color(0xFFFFB74D),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Text(
                        "O Elias pediu para reforçar a semana ${ui.state.currentWeek} antes de avançar." +
                            (ui.state.reviewSince?.let { " Desde $it." } ?: "") +
                            " Calendário pausado (${ui.state.totalPausedDays} dias).",
                        color = Color(0xFFFFE0B2),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 6.dp, bottom = 8.dp)
                    )
                    val topics = ui.state.deficientTopics.orEmpty()
                    if (topics.isNotEmpty()) {
                        Text("Pendências:", color = Color(0xFFFFCC80), fontWeight = FontWeight.SemiBold)
                        topics.take(8).forEach { t ->
                            Text("· $t", color = Color(0xFFFFE0B2), fontSize = 12.sp)
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(
                        "Ordem da rodada de revisão (prática completa — não só o quiz):",
                        color = Color(0xFFFFE0B2),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "1) Conversa temática da semana → 2) Chunks/IPA → 3) Quiz → 4) Checkpoint",
                        color = Muted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                    )
                    // Step 1 — themed conversation (same week topics)
                    Button(
                        onClick = {
                            programVm.startConversationSession(
                                ProgramSessionType.THEMED,
                                ui.state.dailyGoalMinutes,
                            ) { week, title, lexis, grammar, phase, level ->
                                onStartChat(
                                    week, title, lexis, grammar, phase,
                                    ProgramSessionType.THEMED.apiValue,
                                    ui.state.dailyGoalMinutes,
                                    level,
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                    ) {
                        Text("1 · Conversa temática (revisão)", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
                    // Step 2 — chunks drill
                    OutlinedButton(
                        onClick = { programVm.startChunksDrill() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("2 · Chunks da semana (IPA / shadowing)", color = Color(0xFFFFB74D))
                    }
                    Spacer(Modifier.height(8.dp))
                    // Step 3 — quiz only after practice (still available here at end of round)
                    OutlinedButton(
                        onClick = {
                            programVm.loadWeekQuiz()
                            showWeeklyQuiz = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("3 · Quiz semanal (após a prática)", color = Color(0xFFFFB74D))
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { programVm.runCheckpoint() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5D4037))
                    ) {
                        Text("4 · Checkpoint de prontidão", color = Color.White)
                    }
                }
            }
        }

        // Primary CTA — wireframe: Iniciar Sessão de Hoje
        Spacer(Modifier.height(12.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = Surface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (ui.state.heldBack) "Praticar de novo (mesma semana)" else "Iniciar sessão de hoje",
                    color = TextMain,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Text(
                    "Semana ${ui.state.currentWeek} · Nível ${week?.level ?: "—"} · " +
                        "${ui.state.dailyGoalMinutes} min · sem perguntar nível",
                    color = Muted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )
                Button(
                    onClick = {
                        programVm.startConversationSession(
                            ProgramSessionType.THEMED,
                            ui.state.dailyGoalMinutes,
                        ) { week, title, lexis, grammar, phase, level ->
                            onStartChat(
                                week, title, lexis, grammar, phase,
                                ProgramSessionType.THEMED.apiValue,
                                ui.state.dailyGoalMinutes,
                                level,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Chat, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Iniciar sessão", fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // Quiz + checkpoint (Tutor Adaptativo B.4 / B.6)
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    programVm.loadWeekQuiz()
                    showWeeklyQuiz = true
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Quiz semanal", color = Accent, fontSize = 13.sp)
            }
            Button(
                onClick = { programVm.runCheckpoint() },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF374151))
            ) {
                Text("Checkpoint", color = TextMain, fontSize = 13.sp)
            }
        }
        if (!checkpointMsg.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Surface(
                color = Color(0xFF1E293B),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(checkpointMsg!!, color = TextMain, fontSize = 13.sp)
                    TextButton(onClick = { programVm.clearCheckpointMsg() }) {
                        Text("OK", color = Accent)
                    }
                }
            }
        }
        if (ui.state.totalPausedDays > 0 && !ui.state.heldBack) {
            Text(
                "Calendário ajustado: ${ui.state.totalPausedDays} dia(s) de revisão já contabilizados.",
                color = Muted,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        Spacer(Modifier.height(12.dp))
        Text("Foco de pronúncia de hoje", color = TextMain, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Spacer(Modifier.height(6.dp))
        // Daily highlight (same source as session kickoff)
        val focusOfDay = remember { PronunciationFocus.focusOfDay() }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            PronunciationFocus.TAGS.forEach { tag ->
                val selected = tag == focusOfDay
                Surface(
                    color = if (selected) Accent.copy(alpha = 0.35f) else Accent.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text(
                        tag,
                        color = if (selected) TextMain else Accent,
                        fontSize = 10.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
        Text(
            "Hoje: $focusOfDay · ${PronunciationFocus.coachingTip(focusOfDay)}",
            color = Muted,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
        )
        ProgramActionButton(
            icon = { Icon(Icons.Default.RecordVoiceOver, null) },
            label = "Drill rápido (5–10 min)",
            subtitle = "Chunks · IPA · shadowing intensivo",
            onClick = { programVm.startChunksDrill() }
        )
        ProgramActionButton(
            icon = { Icon(Icons.Default.Bolt, null) },
            label = "Conversa rápida",
            subtitle = "5 ou 10 min · mesmo modo PROGRAM",
            onClick = { showQuickPick = true }
        )
        ProgramActionButton(
            icon = { Icon(Icons.Default.ShowChart, null) },
            label = "Progresso do programa",
            subtitle = "Streak, calendário e jornada 1→26",
            onClick = onOpenProgress
        )

        if (practice != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Sessão ativa: ${practice!!.elapsedSeconds / 60}:${(practice!!.elapsedSeconds % 60).toString().padStart(2, '0')} / ${practice!!.goalMinutes} min",
                color = Accent,
                fontSize = 13.sp
            )
        }

        if (drill?.done == true) {
            LaunchedEffect(Unit) {
                Toast.makeText(context, "Drill de chunks concluído!", Toast.LENGTH_SHORT).show()
                programVm.closeDrill()
            }
        }
        Spacer(Modifier.height(24.dp))
        } // content column under hero (Fase 4)
    }
}

@Composable
private fun ProgramActionButton(
    icon: @Composable () -> Unit,
    label: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Surface, contentColor = TextMain),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                CompositionLocalProvider(LocalContentColor provides Accent) { icon() }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(label, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(subtitle, color = Muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ProgramOnboardingDialog(onConfirm: (String, String) -> Unit) {
    var mode by remember { mutableStateOf("auto") }
    val today = remember { LocalDate.now().toString() }
    AlertDialog(
        onDismissRequest = { /* must complete */ },
        title = { Text("Fluência em Inglês em 6 Meses") },
        text = {
            Column {
                val targetBr = ProgramDates.targetDateBr(today)
                Text(
                    "Elias será seu tutor, mentor e coach de pronúncia (General American) até C1.",
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Meta de fluência: 6 meses após o início → até $targetBr (se começar hoje).",
                    fontSize = 12.sp,
                    color = Muted
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Diário: 90 min estudo + 30 min conversação. O nível vem da semana + desempenho — nunca é perguntado.",
                    fontSize = 12.sp,
                    color = Muted
                )
                Spacer(Modifier.height(12.dp))
                Text("Data de início: $today", fontSize = 13.sp)
                Text("Meta C1: $targetBr", fontSize = 13.sp, color = Accent)
                Spacer(Modifier.height(8.dp))
                Row {
                    FilterChip(
                        selected = mode == "auto",
                        onClick = { mode = "auto" },
                        label = { Text("Semana auto") }
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = mode == "manual",
                        onClick = { mode = "manual" },
                        label = { Text("Semana manual") }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(today, mode) }) { Text("Começar com Elias") }
        }
    )
}
