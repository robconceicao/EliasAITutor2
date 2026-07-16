package com.roberto.eliasaitutor.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roberto.eliasaitutor.program.SessionFeedback

private val Bg = Color(0xFF0d0f14)
private val Surface = Color(0xFF161922)
private val Accent = Color(0xFF4f8ef7)
private val Muted = Color(0xFF7a8099)
private val TextMain = Color(0xFFE8EAF0)
private val Green = Color(0xFF10B981)
private val Red = Color(0xFFEF4444)
private val Purple = Color(0xFF8B5CF6)

@Composable
fun SessionFeedbackScreen(
    feedback: SessionFeedback,
    onDismiss: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            "Relatório do Elias",
            color = TextMain,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Pronúncia Avançada Máxima · IPA · schwa · linking · elisão · entonação",
            color = Muted,
            fontSize = 12.sp
        )
        Spacer(Modifier.height(8.dp))
        Surface(
            color = Accent.copy(alpha = 0.15f),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                "CEFR estimado: ${feedback.cefrEstimate.ifBlank { "—" }}",
                color = Accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
        if (feedback.weekAlignment.isNotBlank()) {
            Text(
                feedback.weekAlignment,
                color = Muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        Spacer(Modifier.height(16.dp))

        if (feedback.strengths.isNotEmpty()) {
            Text("Pontos fortes", color = Green, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            feedback.strengths.forEach { s ->
                Text("• $s", color = TextMain, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
            }
            Spacer(Modifier.height(12.dp))
        }

        if (feedback.mistakes.isNotEmpty()) {
            Text("Erros principais", color = TextMain, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
        }

        feedback.mistakes.forEachIndexed { i, m ->
            Card(
                colors = CardDefaults.cardColors(containerColor = Surface),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("Erro ${i + 1}", color = Muted, fontSize = 11.sp)
                    Text(m.said, color = Red, fontSize = 14.sp)
                    Text(
                        "→ ${m.correct}",
                        color = Green,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    if (m.ipa.isNotBlank()) {
                        Text(
                            "IPA: ${m.ipa}",
                            color = Purple,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    if (m.mouthTip.isNotBlank()) {
                        Text(
                            "Boca/língua/ar: ${m.mouthTip}",
                            color = Muted,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    if (m.note.isNotBlank()) {
                        Text(
                            m.note,
                            color = Muted,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }

        if (feedback.pronunciationFocus.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = Surface),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "Pronúncia (GA)",
                        color = Purple,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        feedback.pronunciationFocus,
                        color = TextMain,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }

        if (feedback.discourseFocus.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = Surface),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "Discurso · fluência · registro (C1)",
                        color = Accent,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        feedback.discourseFocus,
                        color = TextMain,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }

        val recovery = feedback.recoveryPlan
        if (recovery != null && (recovery.priority.isNotBlank() || recovery.dailyDrills.isNotEmpty())) {
            Spacer(Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1A0A)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "Plano de recuperação",
                        color = Color(0xFFFFB74D),
                        fontWeight = FontWeight.SemiBold
                    )
                    if (recovery.priority.isNotBlank()) {
                        Text(
                            "Prioridade: ${recovery.priority}",
                            color = TextMain,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    recovery.dailyDrills.forEach { d ->
                        Text("• $d", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                    if (recovery.successCriteria.isNotBlank()) {
                        Text(
                            "Critério: ${recovery.successCriteria}",
                            color = Muted,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
        }

        if (feedback.betterPhrases.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("Formas mais naturais", color = TextMain, fontWeight = FontWeight.SemiBold)
            feedback.betterPhrases.forEach { p ->
                Text(
                    "• $p",
                    color = Muted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        if (feedback.nextFocus.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = Surface),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("Próximo foco", color = Accent, fontWeight = FontWeight.SemiBold)
                    Text(feedback.nextFocus, color = TextMain, fontSize = 14.sp)
                }
            }
        }

        if (feedback.motivation.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2E1A)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("Motivação", color = Green, fontWeight = FontWeight.SemiBold)
                    Text(feedback.motivation, color = TextMain, fontSize = 14.sp)
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Accent)
        ) {
            Text("Continuar o programa")
        }
    }
}
