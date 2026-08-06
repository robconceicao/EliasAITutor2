package com.roberto.eliasaitutor.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roberto.eliasaitutor.program.PlacementPayload
import com.roberto.eliasaitutor.program.PlacementResult
import com.roberto.eliasaitutor.program.ProgramViewModel
import com.roberto.eliasaitutor.ui.theme.EliasTokens

private val Bg = EliasTokens.Bg
private val Surface = EliasTokens.Surface
private val Accent = EliasTokens.Accent
private val Muted = EliasTokens.Muted
private val TextMain = EliasTokens.TextMain
private val Green = EliasTokens.Green

/**
 * Teste de nivelamento — define em qual das 26 semanas o programa começa.
 * O início não é fixo na Semana 1: quem já fala A2/B1 entra direto no nível certo.
 */
@Composable
fun PlacementScreen(
    programVm: ProgramViewModel,
    onClose: () -> Unit,
) {
    val payload by programVm.placement.collectAsState()
    val result by programVm.placementResult.collectAsState()
    val loading by programVm.placementLoading.collectAsState()

    LaunchedEffect(Unit) {
        if (payload == null && result == null) programVm.loadPlacement()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .padding(16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Teste de nivelamento",
                color = TextMain,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
            TextButton(onClick = {
                programVm.clearPlacement()
                onClose()
            }) {
                Text("Fechar", color = Muted)
            }
        }

        Spacer(Modifier.height(8.dp))

        when {
            result != null -> PlacementResultCard(result!!) {
                programVm.clearPlacement()
                onClose()
            }

            loading && payload == null -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator(color = Accent) }

            payload == null -> {
                Text(
                    "Não consegui carregar o teste. Verifique a conexão com o backend.",
                    color = Muted,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { programVm.loadPlacement() },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) { Text("Tentar novamente", color = Color.White) }
            }

            else -> PlacementForm(
                payload = payload!!,
                submitting = loading,
                onSubmit = { programVm.submitPlacement(it) },
                onBeginner = { programVm.submitPlacement(null) },
            )
        }
    }
}

@Composable
private fun PlacementResultCard(result: PlacementResult, onDone: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Você começa na Semana ${result.startWeek}",
                color = Green,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Nível estimado: ${result.level}",
                color = TextMain,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (result.total > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "${result.correctCount}/${result.total} acertos · ${result.scorePercent}%",
                    color = Muted,
                    fontSize = 13.sp
                )
            }
            if (result.summary.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(result.summary, color = TextMain, fontSize = 14.sp)
            }

            if (result.tiers.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                result.tiers.forEach { t ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "${if (t.passed) "✓" else "•"} ${t.level}",
                            color = if (t.passed) Green else Muted,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text("${t.correct}/${t.total}", color = Muted, fontSize = 13.sp)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "A contagem dos 6 meses começa hoje, a partir desta semana.",
                color = Muted,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Accent)
            ) { Text("Começar o programa", color = Color.White) }
        }
    }
}

@Composable
private fun PlacementForm(
    payload: PlacementPayload,
    submitting: Boolean,
    onSubmit: (List<Int>) -> Unit,
    onBeginner: () -> Unit,
) {
    val answers = remember(payload.questions.size) {
        mutableStateListOf(*Array(payload.questions.size) { -1 })
    }
    val scroll = rememberScrollState()

    Column(Modifier.fillMaxSize()) {
        Text(
            "${payload.questions.size} questões, do A1 ao C1. Não chute: responder errado " +
                "coloca você numa semana mais fácil, e isso é melhor do que pular a base.",
            color = Muted,
            fontSize = 13.sp
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onBeginner, enabled = !submitting) {
            Text("Nunca estudei inglês — começar da Semana 1", color = Accent, fontSize = 13.sp)
        }
        Spacer(Modifier.height(4.dp))

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            var lastLevel = ""
            payload.questions.forEachIndexed { qi, q ->
                if (q.level != lastLevel) {
                    lastLevel = q.level
                    Text(
                        "Nível ${q.level}",
                        color = Accent,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Card(
                    colors = CardDefaults.cardColors(containerColor = Surface),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "${qi + 1}. ${q.question}",
                            color = TextMain,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        q.options.forEachIndexed { oi, opt ->
                            val selected = answers.getOrNull(qi) == oi
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = selected,
                                        onClick = { answers[qi] = oi },
                                        role = Role.RadioButton
                                    )
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selected,
                                    onClick = { answers[qi] = oi },
                                    colors = RadioButtonDefaults.colors(selectedColor = Accent)
                                )
                                Text(opt, color = TextMain, fontSize = 13.sp)
                            }
                        }
                        TextButton(onClick = { answers[qi] = -2 }) {
                            Text("Não sei", color = Muted, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        val allAnswered = answers.all { it != -1 }
        Button(
            onClick = { onSubmit(answers.toList()) },
            enabled = allAnswered && !submitting,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Accent)
        ) {
            if (submitting) {
                CircularProgressIndicator(
                    Modifier.size(18.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
            } else {
                Text("Descobrir minha semana inicial", color = Color.White)
            }
        }
    }
}
