package com.roberto.eliasaitutor.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import com.roberto.eliasaitutor.program.ProgramPhaseUi
import com.roberto.eliasaitutor.program.ProgramViewModel
import com.roberto.eliasaitutor.ui.theme.EliasTokens

private val Bg = EliasTokens.Bg
private val Surface = EliasTokens.Surface
private val Accent = EliasTokens.Accent
private val Muted = EliasTokens.Muted
private val TextMain = EliasTokens.TextMain

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgramProgressScreen(
    programVm: ProgramViewModel,
    onBack: () -> Unit,
) {
    val ui by programVm.ui.collectAsState()
    val p = ui.progress
    val week = ui.state.currentWeek

    Column(Modifier.fillMaxSize().background(Bg)) {
        TopAppBar(
            title = { Text("Progresso", color = TextMain) },
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
            Text(
                "Constância do programa · meta sagrada 30 min/dia",
                color = Muted,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("Streak", "${p.streak} dias", Modifier.weight(1f))
                StatCard("Recorde", "${p.bestStreak} dias", Modifier.weight(1f))
                StatCard("Hoje", "${p.todayMinutes} min", Modifier.weight(1f))
            }

            // Fase 5 — tutor adaptativo / calendário pausável
            Spacer(Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (ui.state.heldBack) Color(0xFF2A1A0A) else Surface
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        if (ui.state.heldBack) "Modo revisão ativo" else "Tutor adaptativo",
                        color = if (ui.state.heldBack) EliasTokens.Orange else EliasTokens.Teal,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Semana atual: ${ui.state.currentWeek}/26",
                        color = TextMain,
                        fontSize = 13.sp
                    )
                    Text(
                        "Dias pausados (revisão): ${ui.state.totalPausedDays}",
                        color = Muted,
                        fontSize = 12.sp
                    )
                    if (ui.state.heldBack) {
                        Text(
                            "held_back = true" +
                                (ui.state.reviewSince?.let { " · desde $it" } ?: ""),
                            color = EliasTokens.Orange,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        val topics = ui.state.deficientTopics.orEmpty()
                        if (topics.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text("Pendências:", color = Muted, fontSize = 11.sp)
                            topics.take(5).forEach {
                                Text("· $it", color = EliasTokens.TextDim, fontSize = 11.sp)
                            }
                        }
                    } else if (ui.state.totalPausedDays > 0) {
                        Text(
                            "Calendário já descontou ${ui.state.totalPausedDays} dia(s) de revisão.",
                            color = Muted,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    } else {
                        Text(
                            "Sem retenção — avance com quiz + prática de qualidade.",
                            color = Muted,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            val goal = ui.state.dailyGoalMinutes.coerceAtLeast(1)
            val frac = (p.todayMinutes.toFloat() / goal).coerceIn(0f, 1f)
            Card(
                colors = CardDefaults.cardColors(containerColor = Surface),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Meta de hoje", color = TextMain, fontWeight = FontWeight.SemiBold)
                        Text("${p.todayMinutes}/$goal min", color = Accent, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { frac },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                        color = Accent,
                        trackColor = Color(0xFF2A2E3A),
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Text("Jornada 1 → 26", color = TextMain, fontWeight = FontWeight.SemiBold)
            Text(
                "Avanço só com domínio real (gramática · vocabulário · pronúncia)",
                color = Muted,
                fontSize = 11.sp
            )
            Spacer(Modifier.height(8.dp))
            JourneyBar(currentWeek = week)

            Spacer(Modifier.height(20.dp))
            Text("Últimos 30 dias", color = TextMain, fontWeight = FontWeight.SemiBold)
            Text("Minutos de conversação por dia", color = Muted, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(7),
                modifier = Modifier.fillMaxWidth().height(160.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                userScrollEnabled = false
            ) {
                items(p.days.takeLast(30)) { day ->
                    val intensity = (day.minutes / 30f).coerceIn(0f, 1f)
                    Box(
                        Modifier
                            .aspectRatio(1f)
                            .background(
                                if (day.minutes > 0) Accent.copy(alpha = 0.25f + 0.75f * intensity)
                                else Color(0xFF2A2E3A),
                                RoundedCornerShape(4.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (day.minutes > 0) {
                            Text("${day.minutes}", color = TextMain, fontSize = 8.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "Dica: abra Progress (aba) para ver erros comuns das conversas com Elias.",
                color = Muted,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
    ) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, color = TextMain, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(label, color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun JourneyBar(currentWeek: Int) {
    Column {
        Row(Modifier.fillMaxWidth().height(12.dp)) {
            for (phase in 1..4) {
                val phaseColor = Color(ProgramPhaseUi.info(phase).color)
                val weeksInPhase = when (phase) {
                    1 -> 6
                    2 -> 7
                    3 -> 7
                    else -> 6
                }
                val startWeek = when (phase) {
                    1 -> 1
                    2 -> 7
                    3 -> 14
                    else -> 21
                }
                val filled = when {
                    currentWeek >= startWeek + weeksInPhase - 1 -> 1f
                    currentWeek < startWeek -> 0f
                    else -> (currentWeek - startWeek + 1).toFloat() / weeksInPhase
                }
                Box(
                    Modifier
                        .weight(weeksInPhase.toFloat())
                        .fillMaxHeight()
                        .padding(horizontal = 1.dp)
                        .background(Color(0xFF2A2E3A), RoundedCornerShape(4.dp))
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(filled)
                            .background(phaseColor, RoundedCornerShape(4.dp))
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("F1 A1", "F2 A2", "F3 B1", "F4 C1").forEach {
                Text(it, color = Muted, fontSize = 10.sp)
            }
        }
        Text("Semana atual: $currentWeek / 26", color = Accent, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
    }
}
