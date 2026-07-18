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
import com.roberto.eliasaitutor.program.ProgramQuizPayload
import com.roberto.eliasaitutor.program.ProgramViewModel
import com.roberto.eliasaitutor.program.QuizSubmitResult

import com.roberto.eliasaitutor.ui.theme.EliasTokens

private val Bg = EliasTokens.Bg
private val Surface = EliasTokens.Surface
private val Accent = EliasTokens.Accent
private val Muted = EliasTokens.Muted
private val TextMain = EliasTokens.TextMain
private val Green = EliasTokens.Green
private val Red = EliasTokens.Red

@Composable
fun WeeklyQuizScreen(
    programVm: ProgramViewModel,
    onClose: () -> Unit,
) {
    val quiz by programVm.weekQuiz.collectAsState()
    val result by programVm.quizResult.collectAsState()
    val loading by programVm.quizLoading.collectAsState()

    LaunchedEffect(Unit) {
        if (quiz == null) programVm.loadWeekQuiz()
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
                "Quiz semanal",
                color = TextMain,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
            TextButton(onClick = {
                programVm.clearWeekQuiz()
                onClose()
            }) {
                Text("Fechar", color = Muted)
            }
        }

        Spacer(Modifier.height(8.dp))

        when {
            loading && quiz == null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Accent)
                }
            }
            quiz == null -> {
                Text("Quiz não disponível.", color = Muted)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { programVm.loadWeekQuiz() }) {
                    Text("Tentar novamente")
                }
            }
            result != null -> {
                QuizResultCard(result!!, quiz!!) {
                    programVm.clearWeekQuiz()
                    onClose()
                }
            }
            else -> {
                QuizQuestionsForm(
                    quiz = quiz!!,
                    submitting = loading,
                    onSubmit = { answers -> programVm.submitWeekQuiz(answers) }
                )
            }
        }
    }
}

@Composable
private fun QuizResultCard(
    result: QuizSubmitResult,
    quiz: ProgramQuizPayload,
    onDone: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (result.passed) "Aprovado!" else "Ainda não passou",
                color = if (result.passed) Green else Red,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Total: ${result.scorePercent}% · ${result.correctCount}/${result.total} acertos",
                color = TextMain,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            // Dual section scores (task v3.1)
            if (result.vocabularyTotal > 0 || result.pronunciationTotal > 0) {
                Text(
                    "📚 Vocabulário: ${result.vocabularyScore}%",
                    color = TextMain,
                    fontSize = 14.sp
                )
                Text(
                    "🗣️ Pronúncia: ${result.pronunciationScore}%",
                    color = TextMain,
                    fontSize = 14.sp
                )
            }
            Text(
                "Mínimo para liberar próxima aula: ${result.passingScorePercent}%",
                color = Muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                if (result.passed || result.canAdvance) {
                    "Nota atingida — quando quiser, rode o checkpoint semanal para avançar de semana."
                } else {
                    "Nota abaixo de ${result.passingScorePercent}%. Revise vocabulário e pronúncia da semana e refaça o quiz."
                },
                color = Muted,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onDone, colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                Text("OK", color = Color.White)
            }
        }
    }
}

@Composable
private fun QuizQuestionsForm(
    quiz: ProgramQuizPayload,
    submitting: Boolean,
    onSubmit: (List<Int>) -> Unit,
) {
    val answers = remember(quiz.week) {
        mutableStateListOf(*Array(quiz.questions.size) { -1 })
    }
    val scroll = rememberScrollState()

    Column(Modifier.fillMaxSize()) {
        val vocabCount = quiz.questions.count {
            it.section.equals("vocabulary", true) || it.section.isBlank()
        }
        val pronCount = quiz.questions.count { it.section.equals("pronunciation", true) }
        Text(
            "Semana ${quiz.week} · ${quiz.questions.size} questões · mín. ${quiz.passingScorePercent}%",
            color = Muted,
            fontSize = 13.sp
        )
        Text(
            "📚 Vocabulário: $vocabCount  ·  🗣️ Pronúncia: $pronCount",
            color = Accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(12.dp))

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            var lastSection = ""
            quiz.questions.forEachIndexed { qi, q ->
                val section = when {
                    q.section.equals("pronunciation", true) -> "pronunciation"
                    else -> "vocabulary"
                }
                if (section != lastSection) {
                    lastSection = section
                    Text(
                        if (section == "pronunciation") "🗣️ Pronúncia"
                        else "📚 Vocabulário / gramática",
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
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        val allAnswered = answers.all { it >= 0 }
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
                Text("Enviar respostas", color = Color.White)
            }
        }
    }
}
