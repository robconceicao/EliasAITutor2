package com.roberto.eliasaitutor.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roberto.eliasaitutor.viewmodel.EliasViewModel
import kotlinx.coroutines.delay

private val Bg = Color(0xFF0d0f14)
private val Surface = Color(0xFF161922)
private val Border = Color(0xFF252a35)
private val Accent = Color(0xFF4f8ef7)
private val Green = Color(0xFF3ecf8e)
private val Muted = Color(0xFF7a8099)
private val Red = Color(0xFFf76f6f)
private val Gold = Color(0xFFf7c94f)
private val TextMain = Color(0xFFE8EAF0)

/**
 * Immersion — silent period: hear English, map sound → meaning (Task Final Fase 2).
 */
@Composable
fun ImmersionScreen(vm: EliasViewModel) {
    var currentLevel by remember { mutableIntStateOf(1) }
    var step by remember { mutableIntStateOf(0) }
    var score by remember { mutableIntStateOf(0) }
    var feedback by remember { mutableStateOf<String?>(null) }
    // Drive play UI from real TTS state (Opus stream), not an immediate callback
    val isPlaying by vm.isIaSpeaking.collectAsState()

    val levelsData = remember {
        mapOf(
            1 to listOf(
                ImmersionTask("I am drinking a glass of cold water.", "🚰", listOf("🚰", "🍎", "🚗", "🏠")),
                ImmersionTask("The blue car is moving fast.", "🚙", listOf("🚗", "🚙", "🛵", "🚲")),
                ImmersionTask("I desperately need a cup of coffee.", "☕", listOf("🍵", "🥤", "☕", "🍺")),
                ImmersionTask("It's starting to rain, I need an umbrella.", "☂️", listOf("☀️", "☂️", "❄️", "🌬️")),
                ImmersionTask("I am reading a very interesting book.", "📖", listOf("📖", "💻", "📱", "📺")),
                ImmersionTask("The cat is sleeping on the sofa.", "🐱", listOf("🐶", "🐱", "🐭", "🐹")),
            ),
            2 to listOf(
                ImmersionTask("He is running very fast in the park.", "🏃", listOf("🏃", "🚶", "🛌", "🧘")),
                ImmersionTask("They are eating a delicious pizza.", "🍕", listOf("🍔", "🍟", "🍕", "🥗")),
                ImmersionTask("The baby is crying because he is hungry.", "😭", listOf("😂", "😭", "😴", "🥳")),
                ImmersionTask("We are dancing at the party!", "💃", listOf("💃", "🏃", "🛌", "🧘")),
                ImmersionTask("She is swimming in the ocean.", "🏊", listOf("🚴", "🚣", "🏊", "🚶")),
                ImmersionTask("The sun is shining in the sky.", "☀️", listOf("☁️", "🌧️", "☀️", "❄️")),
            ),
            3 to listOf(
                ImmersionTask("I am very happy today!", "😊", listOf("😡", "😢", "😊", "😴")),
                ImmersionTask("It is very cold outside.", "❄️", listOf("🔥", "❄️", "☁️", "☀️")),
                ImmersionTask("The pizza is very hot!", "🔥", listOf("❄️", "🔥", "🌬️", "☔")),
                ImmersionTask("I am so tired, I need to sleep.", "😴", listOf("😴", "🥳", "🏃", "🚴")),
                ImmersionTask("This music is very loud.", "🔊", listOf("🔇", "🔉", "🔊", "🎵")),
                ImmersionTask("I am hungry, I want a burger.", "🍔", listOf("🍕", "🍟", "🍔", "🥗")),
            ),
        )
    }

    val immersionData = levelsData[currentLevel] ?: levelsData[1]!!
    val total = immersionData.size
    val inProgress = step < total

    // Auto-play phrase when step changes (isPlaying tracks iaStateFlow via ViewModel)
    LaunchedEffect(currentLevel, step) {
        if (inProgress) {
            vm.speakText(immersionData[step].phrase)
        }
    }

    LaunchedEffect(feedback) {
        if (feedback != null) {
            delay(700)
            feedback = null
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Immersion", color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Período silencioso · som → ação",
                    color = Muted,
                    fontSize = 12.sp
                )
            }
            Surface(
                color = Accent.copy(alpha = 0.15f),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text(
                    if (inProgress) "${step + 1}/$total" else "✓",
                    color = Accent,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Level chips
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..3).forEach { lvl ->
                val selected = currentLevel == lvl
                FilterChip(
                    selected = selected,
                    onClick = {
                        currentLevel = lvl
                        step = 0
                        score = 0
                        feedback = null
                    },
                    label = { Text("Nível $lvl") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Accent.copy(alpha = 0.25f),
                        selectedLabelColor = Accent,
                        containerColor = Surface,
                        labelColor = Muted,
                    )
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = { if (total == 0) 0f else step.toFloat() / total },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = Accent,
            trackColor = Border,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Acertos: $score", color = Green, fontSize = 12.sp)
            Text("Ouça → escolha o emoji", color = Muted, fontSize = 12.sp)
        }

        Spacer(Modifier.height(28.dp))

        if (inProgress) {
            val current = immersionData[step]

            // Listen orb
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(140.dp)
                    .background(
                        Brush.radialGradient(
                            listOf(Accent.copy(alpha = 0.25f), Color.Transparent)
                        ),
                        CircleShape
                    )
            ) {
                Surface(
                    onClick = {
                        vm.speakText(current.phrase)
                    },
                    modifier = Modifier.size(100.dp),
                    shape = CircleShape,
                    color = Accent.copy(alpha = if (isPlaying) 0.35f else 0.15f),
                    border = BorderStroke(2.dp, Accent.copy(alpha = 0.5f))
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (isPlaying) Icons.Default.VolumeUp else Icons.Default.PlayArrow,
                            contentDescription = "Ouvir",
                            tint = Accent,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                if (isPlaying) "Ouvindo Elias…" else "Toque para ouvir de novo",
                color = Muted,
                fontSize = 13.sp
            )

            Spacer(Modifier.height(20.dp))
            Text(
                "O QUE VOCÊ OUVIU?",
                color = Accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp
            )
            Spacer(Modifier.height(16.dp))

            // 2x2 options
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                immersionData[step].options.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { opt ->
                            ImmersionOptionCard(
                                icon = opt,
                                correctIcon = current.icon,
                                enabled = feedback == null,
                                onResult = { correct ->
                                    if (correct) {
                                        score++
                                        feedback = "ok"
                                        step++
                                    } else {
                                        feedback = "err"
                                    }
                                }
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = feedback != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Text(
                    if (feedback == "ok") "✓ Correto!" else "✗ Tente de novo — ouça outra vez",
                    color = if (feedback == "ok") Green else Red,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        } else {
            // Level complete
            Card(
                colors = CardDefaults.cardColors(containerColor = Surface),
                border = BorderStroke(1.dp, Green.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier.padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("🌟", fontSize = 40.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Nível $currentLevel completo!",
                        color = Green,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Acertos: $score / $total\nSeu cérebro mapeou sons → significado.",
                        color = Muted,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = {
                            if (currentLevel < levelsData.size) {
                                currentLevel++
                            } else {
                                currentLevel = 1
                            }
                            step = 0
                            score = 0
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Accent),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Icon(Icons.Default.Refresh, null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (currentLevel < levelsData.size) "Próximo nível"
                            else "Recomeçar jornada"
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "Dica: feche os olhos ao ouvir e visualize a ação.\nIsso ativa o córtex motor e melhora a retenção.",
            color = Muted.copy(alpha = 0.7f),
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            lineHeight = 15.sp
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ImmersionOptionCard(
    icon: String,
    correctIcon: String,
    enabled: Boolean,
    onResult: (Boolean) -> Unit,
) {
    var flash by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(icon) { flash = null }

    val borderColor = when (flash) {
        true -> Green
        false -> Red
        null -> Border
    }

    Box(
        Modifier
            .size(110.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Surface)
            .border(2.dp, borderColor, RoundedCornerShape(20.dp))
            .clickable(enabled = enabled) {
                val ok = icon == correctIcon
                flash = ok
                onResult(ok)
            },
        contentAlignment = Alignment.Center
    ) {
        Text(icon, fontSize = 44.sp)
    }
}

data class ImmersionTask(val phrase: String, val icon: String, val options: List<String>)
