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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roberto.eliasaitutor.program.ProgramDates
import com.roberto.eliasaitutor.program.ProgramPhaseUi
import com.roberto.eliasaitutor.program.ProgramSessionType
import com.roberto.eliasaitutor.program.ProgramViewModel
import java.time.LocalDate

private val Bg = Color(0xFF0d0f14)
private val Surface = Color(0xFF161922)
private val Accent = Color(0xFF4f8ef7)
private val Muted = Color(0xFF7a8099)
private val TextMain = Color(0xFFE8EAF0)

@Composable
fun ProgramHomeScreen(
    programVm: ProgramViewModel,
    userId: String,
    onStartChat: (week: Int, title: String, lexis: String, grammar: String, phase: Int, sessionType: String, goalMinutes: Int) -> Unit,
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
                        ) { week, title, lexis, grammar, phase ->
                            onStartChat(
                                week, title, lexis, grammar, phase,
                                ProgramSessionType.QUICK.apiValue, 5,
                            )
                        }
                    }) { Text("5 min") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showQuickPick = false
                        programVm.startConversationSession(
                            ProgramSessionType.QUICK, 10,
                        ) { week, title, lexis, grammar, phase ->
                            onStartChat(
                                week, title, lexis, grammar, phase,
                                ProgramSessionType.QUICK.apiValue, 10,
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
            .padding(16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Elias · Programa", color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                val start = ui.state.startDate
                val targetBr = if (start.isNotBlank()) {
                    ProgramDates.targetDateBr(start)
                } else {
                    "6 meses após o início"
                }
                Text(
                    "Fluência C1 · General American · meta até $targetBr",
                    color = Muted,
                    fontSize = 11.sp
                )
            }
            IconButton(onClick = { showSettings = true }) {
                Icon(Icons.Default.Settings, contentDescription = "Configurações", tint = Muted)
            }
        }

        // Pronunciation pillars (aligned with Elias master prompt)
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf("IPA", "Shadowing", "Schwa", "Linking", "Elisão", "Entonação").forEach { tag ->
                Surface(
                    color = Accent.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text(
                        tag,
                        color = Accent,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }

        if (ui.offline) {
            Surface(
                color = Color(0xFF3D2E00),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            ) {
                Text(
                    "Offline — exibindo cache local",
                    color = Color(0xFFFFD54F),
                    modifier = Modifier.padding(12.dp),
                    fontSize = 13.sp
                )
            }
        }

        if (ui.loading) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Accent)
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
                    week?.title ?: "Carregando…",
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

        // Primary CTA — wireframe: Iniciar Sessão de Hoje
        Spacer(Modifier.height(12.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = Surface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Iniciar sessão de hoje",
                    color = TextMain,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Text(
                    "Semana ${ui.state.currentWeek} · ${ui.state.dailyGoalMinutes} min · TTS streaming · sem perguntar nível",
                    color = Muted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )
                Button(
                    onClick = {
                        programVm.startConversationSession(
                            ProgramSessionType.THEMED,
                            ui.state.dailyGoalMinutes,
                        ) { week, title, lexis, grammar, phase ->
                            onStartChat(
                                week, title, lexis, grammar, phase,
                                ProgramSessionType.THEMED.apiValue,
                                ui.state.dailyGoalMinutes,
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

        Spacer(Modifier.height(12.dp))
        Text("Foco de pronúncia de hoje", color = TextMain, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Spacer(Modifier.height(6.dp))
        // tags already above — drill CTA
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
