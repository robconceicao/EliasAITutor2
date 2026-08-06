package com.roberto.eliasaitutor.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roberto.eliasaitutor.program.ProgramDates
import com.roberto.eliasaitutor.program.UserProgramState

private val Bg = Color(0xFF0d0f14)
private val Surface = Color(0xFF161922)
private val TextMain = Color(0xFFE8EAF0)
private val Muted = Color(0xFF7a8099)
private val Accent = Color(0xFF4f8ef7)
private val Green = Color(0xFF10B981)
private val Gold = Color(0xFFf7c94f)

/**
 * Configurações do Programa — Fase 3 polish (Task Final v1.0).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgramSettingsScreen(
    state: UserProgramState,
    onBack: () -> Unit,
    onSave: (startDate: String, mode: String, reminder: String?, goal: Int) -> Unit,
    onRetakePlacement: () -> Unit = {},
) {
    var startDate by remember { mutableStateOf(state.startDate) }
    var mode by remember { mutableStateOf(state.weekMode) }
    var reminder by remember { mutableStateOf(state.reminderTime ?: "19:00") }
    var reminderEnabled by remember { mutableStateOf(state.reminderTime != null) }
    var goal by remember { mutableIntStateOf(state.dailyGoalMinutes.coerceAtLeast(5)) }
    var error by remember { mutableStateOf<String?>(null) }

    val targetBr = remember(startDate) {
        if (startDate.matches(Regex("""\d{4}-\d{2}-\d{2}"""))) {
            ProgramDates.targetDateBr(startDate)
        } else "—"
    }
    val startBr = remember(startDate) {
        if (startDate.matches(Regex("""\d{4}-\d{2}-\d{2}"""))) {
            ProgramDates.startDateBr(startDate)
        } else startDate
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        TopAppBar(
            title = {
                Column {
                    Text("Configurações", color = TextMain, fontWeight = FontWeight.Bold)
                    Text("Programa · 26 semanas · C1", color = Muted, fontSize = 11.sp)
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = TextMain)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg)
        )

        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Mission card
            Card(
                colors = CardDefaults.cardColors(containerColor = Surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Meta de fluência", color = Accent, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "C1 com pronúncia General American",
                        color = TextMain,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "Início: $startBr  →  Meta: $targetBr",
                        color = Green,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    Text(
                        "A meta é sempre 6 meses após a data de início (não uma data fixa).",
                        color = Muted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            // Fase 5 — status do tutor adaptativo (somente leitura)
            Spacer(Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (state.heldBack) Color(0xFF2A1A0A) else Surface
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Tutor adaptativo",
                        color = if (state.heldBack) Gold else Accent,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Semana ${state.currentWeek}/26 · ${if (state.heldBack) "em revisão" else "em curso"}",
                        color = TextMain,
                        fontSize = 14.sp
                    )
                    Text(
                        "Dias de calendário pausados: ${state.totalPausedDays}",
                        color = Muted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    if (state.heldBack) {
                        Text(
                            "O Elias retém o avanço até o checkpoint (quiz + prática + CEFR).",
                            color = Gold,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    Text(
                        "O nível nunca é perguntado — vem da semana do programa.",
                        color = Muted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }

            // Nivelamento — o início do programa não é fixo na Semana 1
            Spacer(Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = Surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Nivelamento", color = Accent, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (state.placementDone) {
                            "Semana inicial: ${state.startWeek}" +
                                (state.placementLevel?.let { " · nível $it" } ?: "")
                        } else {
                            "Ainda não realizado — o programa está começando na Semana 1."
                        },
                        color = TextMain,
                        fontSize = 14.sp
                    )
                    Text(
                        "O início do programa não é fixo: o teste coloca você na semana " +
                            "correspondente ao seu nível real.",
                        color = Muted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = onRetakePlacement, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            if (state.placementDone) "Refazer nivelamento" else "Fazer nivelamento",
                            color = Accent
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            SectionTitle("Data de início")
            OutlinedTextField(
                value = startDate,
                onValueChange = {
                    startDate = it.trim()
                    error = null
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("YYYY-MM-DD") },
                supportingText = {
                    Text("Ex.: 2026-07-12 · recalcula a meta C1 automaticamente")
                },
                colors = fieldColors()
            )

            Spacer(Modifier.height(16.dp))
            SectionTitle("Avanço de semana")
            Text(
                "Auto: calcula a semana pela data de início. Manual: você escolhe a semana (útil em revisão).",
                color = Muted,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = mode == "auto",
                    onClick = { mode = "auto" },
                    label = { Text("Automático") }
                )
                FilterChip(
                    selected = mode == "manual",
                    onClick = { mode = "manual" },
                    label = { Text("Manual") }
                )
            }
            if (mode == "manual") {
                Text(
                    "No painel principal use −1 / +1 sem. para mover a semana. Avance só com domínio real.",
                    color = Gold,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            Spacer(Modifier.height(16.dp))
            SectionTitle("Meta diária de conversação")
            Text(
                "30 minutos com Elias são sagrados no método. Ajuste se precisar.",
                color = Muted,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(20, 30, 45).forEach { m ->
                    FilterChip(
                        selected = goal == m,
                        onClick = { goal = m },
                        label = {
                            Text(if (m == 30) "30 min ★" else "$m min")
                        }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = goal.toString(),
                onValueChange = {
                    goal = it.filter { c -> c.isDigit() }.take(3).toIntOrNull()?.coerceIn(5, 180) ?: goal
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Minutos (5–180)") },
                colors = fieldColors()
            )

            Spacer(Modifier.height(16.dp))
            SectionTitle("Lembrete diário")
            Card(
                colors = CardDefaults.cardColors(containerColor = Surface),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Notificação de prática", color = TextMain, fontWeight = FontWeight.Medium)
                            Text(
                                "Lembra a conversação do dia (não atrapalha se a meta já foi batida).",
                                color = Muted,
                                fontSize = 11.sp
                            )
                        }
                        Switch(
                            checked = reminderEnabled,
                            onCheckedChange = { reminderEnabled = it }
                        )
                    }
                    if (reminderEnabled) {
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = reminder,
                            onValueChange = { reminder = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Horário HH:mm") },
                            placeholder = { Text("19:00") },
                            colors = fieldColors()
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            SectionTitle("Sobre o coaching")
            Card(
                colors = CardDefaults.cardColors(containerColor = Surface),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("Pronúncia Avançada Máxima", color = Accent, fontWeight = FontWeight.SemiBold)
                    Text(
                        "IPA · Shadowing · Schwa · Linking · Elisão · Entonação",
                        color = TextMain,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Text(
                        "Dia ideal: 90 min de estudo estruturado + 30 min de conversa com Elias. " +
                            "O nível nunca é perguntado — vem da semana + desempenho real.",
                        color = Muted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            if (error != null) {
                Spacer(Modifier.height(12.dp))
                Text(error!!, color = Color(0xFFEF4444), fontSize = 13.sp)
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    if (!startDate.matches(Regex("""\d{4}-\d{2}-\d{2}"""))) {
                        error = "Use a data no formato YYYY-MM-DD"
                        return@Button
                    }
                    if (reminderEnabled && !reminder.matches(Regex("""\d{1,2}:\d{2}"""))) {
                        error = "Horário do lembrete inválido (use HH:mm)"
                        return@Button
                    }
                    error = null
                    onSave(
                        startDate,
                        mode,
                        if (reminderEnabled) reminder else null,
                        goal.coerceIn(5, 180)
                    )
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Salvar configurações", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        color = TextMain,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextMain,
    unfocusedTextColor = TextMain,
    focusedBorderColor = Accent,
    unfocusedBorderColor = Muted.copy(alpha = 0.4f),
    focusedLabelColor = Accent,
    unfocusedLabelColor = Muted,
    cursorColor = Accent,
)
