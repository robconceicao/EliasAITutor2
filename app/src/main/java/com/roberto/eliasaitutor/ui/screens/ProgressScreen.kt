package com.roberto.eliasaitutor.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roberto.eliasaitutor.data.GameConstants
import com.roberto.eliasaitutor.model.SentimentEntry
import com.roberto.eliasaitutor.program.ProgramViewModel
import com.roberto.eliasaitutor.ui.components.RadarChart
import com.roberto.eliasaitutor.ui.theme.EliasTokens
import com.roberto.eliasaitutor.viewmodel.EliasViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Bg      = EliasTokens.Bg
private val Surface = EliasTokens.Surface
private val Border  = EliasTokens.Border
private val Accent  = EliasTokens.Accent
private val Gold    = EliasTokens.Gold
private val Green   = EliasTokens.Green
private val Red     = EliasTokens.Red
private val Muted   = EliasTokens.Muted
private val Purple  = EliasTokens.Purple

@Composable
fun ProgressScreen(vm: EliasViewModel, programVm: ProgramViewModel? = null) {
    val profile   by vm.profile.collectAsState()
    val isLoading by vm.isLoading.collectAsState()
    val programUiState = programVm?.ui?.collectAsState()?.value

    val lvl5Xp  = GameConstants.LEVEL_THRESHOLDS[5]!!
    val lvl10Xp = GameConstants.LEVEL_THRESHOLDS[10]!!

    Column(Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState()).padding(16.dp)) {

        Text("Progresso", color = EliasTokens.TextMain, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Streak · programa · XP · erros · soft skills", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))

        // ── Streak first (Task Final Fase 2) ───────────────────────────────
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1a1410)),
            border = BorderStroke(1.dp, Gold), shape = RoundedCornerShape(16.dp)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("🔥 Streak diário", color = Gold, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(
                        "${profile.streak} dia${if (profile.streak != 1) "s" else ""} seguidos",
                        color = EliasTokens.TextMain, fontSize = 22.sp, fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Constância > intensidade · +${GameConstants.STREAK_BONUS_COINS} coins/dia",
                        color = Muted, fontSize = 12.sp
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🛡️", fontSize = 28.sp)
                    Text("${profile.streakFreezeCount}", color = Gold, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("Shields", color = Muted, fontSize = 10.sp)
                }
            }
        }

        // Fase 5 — held_back / dias pausados do programa
        if (programUiState != null) {
            val ps = programUiState.state
            Spacer(Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (ps.heldBack) Color(0xFF2A1A0A) else Surface
                ),
                border = BorderStroke(
                    1.dp,
                    if (ps.heldBack) EliasTokens.Orange.copy(alpha = 0.5f)
                    else EliasTokens.Teal.copy(alpha = 0.35f)
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Programa · 26 semanas",
                        color = if (ps.heldBack) EliasTokens.Orange else EliasTokens.Teal,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("Semana", color = Muted, fontSize = 11.sp)
                            Text(
                                "${ps.currentWeek}",
                                color = EliasTokens.TextMain,
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text("Dias pausados", color = Muted, fontSize = 11.sp)
                            Text(
                                "${ps.totalPausedDays}",
                                color = EliasTokens.TextMain,
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text("Status", color = Muted, fontSize = 11.sp)
                            Text(
                                if (ps.heldBack) "Revisão" else "Em curso",
                                color = if (ps.heldBack) EliasTokens.Orange else Green,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                    }
                    if (ps.heldBack && !ps.deficientTopics.isNullOrEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Pendências: ${ps.deficientTopics!!.take(3).joinToString(" · ")}",
                            color = Muted,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Key metrics ────────────────────────────────────────────────────
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard("Level",    "${profile.level}",           "🎯", Modifier.weight(1f))
            MetricCard("Total XP", "${profile.xp}",             "⚡", Modifier.weight(1f))
            MetricCard("Coins",    "${profile.coins}",          "🪙", Modifier.weight(1f))
            MetricCard("Msgs",     "${profile.messagesCount}",  "💬", Modifier.weight(1f))
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = Border)
        Spacer(Modifier.height(16.dp))

        // ── XP progress bar toward next level ─────────────────────────────
        Text("⚡ XP Progress", color = Color(0xFFe8eaf0), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        val (cur, next) = when {
            profile.xp < lvl5Xp  -> profile.xp to lvl5Xp
            profile.xp < lvl10Xp -> (profile.xp - lvl5Xp) to (lvl10Xp - lvl5Xp)
            else                  -> lvl10Xp to lvl10Xp
        }
        val pct = if (next > 0) cur.toFloat() / next else 1f
        LinearProgressIndicator(
            progress = { pct.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(10.dp),
            color = Accent, trackColor = Border,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("$cur XP", color = Muted, fontSize = 11.sp)
            Text("$next XP (Level ${if (profile.level < 5) 5 else 10})", color = Muted, fontSize = 11.sp)
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = Border)
        Spacer(Modifier.height(16.dp))

        // ── Soft Skills Radar ──────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🧠 Soft Skills Radar", color = Color(0xFFe8eaf0), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(6.dp))
            Badge(containerColor = Purple.copy(alpha = 0.25f)) {
                Text("DeepSeek AI", color = Purple, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RadarChart(
                labels = listOf("Confidence", "Clarity", "Posture"),
                values = listOf(profile.confidence.toFloat(), profile.clarity.toFloat(), profile.posture.toFloat()),
                modifier = Modifier.size(220.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SkillBar("Confidence", profile.confidence)
                SkillBar("Clarity",    profile.clarity)
                SkillBar("Posture",    profile.posture)
            }
        }
        if (profile.softSkillsSummary.isNotEmpty()) {
            Text(profile.softSkillsSummary, color = Muted, fontSize = 12.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(top = 4.dp))
        }
        Button(onClick = { vm.analyzeSoftSkills() }, enabled = !isLoading,
            colors = ButtonDefaults.buttonColors(containerColor = Purple)) {
            if (isLoading) CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
            else Text("🔄 Refresh Soft Skills")
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = Border)
        Spacer(Modifier.height(16.dp))

        // ── Sentiment History ──────────────────────────────────────────────
        Text("😊 Recent Mood Timeline", color = Color(0xFFe8eaf0), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        if (profile.sentimentHistory.isEmpty()) {
            Text("Send messages to see your emotional patterns here.", color = Muted, fontSize = 13.sp)
        } else {
            profile.sentimentHistory.takeLast(8).reversed().forEach { entry ->
                SentimentRow(entry)
                Spacer(Modifier.height(4.dp))
            }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = Border)
        Spacer(Modifier.height(16.dp))

        // ── Level Roadmap ──────────────────────────────────────────────────
        Text("🗺️ Level Roadmap", color = Color(0xFFe8eaf0), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        GameConstants.LEVEL_THRESHOLDS.toSortedMap().forEach { (lvl, xpReq) ->
            val achieved = profile.xp >= xpReq
            val barPct   = if (xpReq > 0) (profile.xp.toFloat() / xpReq).coerceIn(0f, 1f) else 1f
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (achieved) "✅" else "🔒", fontSize = 20.sp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Level $lvl${if (achieved) "  — REACHED" else "  — $xpReq XP required"}",
                        color = if (achieved) Green else Color(0xFFe8eaf0), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    LinearProgressIndicator(
                        progress = { barPct },
                        modifier = Modifier.fillMaxWidth().height(6.dp).padding(top = 3.dp),
                        color = if (achieved) Green else Accent, trackColor = Border,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = Border)
        Spacer(Modifier.height(16.dp))

        // ── Common errors (aggregated) ─────────────────────────────────────
        Text("📝 Erros comuns", color = Color(0xFFe8eaf0), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Padrões recorrentes das suas conversas — foque neles no próximo drill",
            color = Muted,
            fontSize = 12.sp
        )
        Spacer(Modifier.height(8.dp))
        val commonErrors = remember(profile.errorLog) {
            profile.errorLog
                .map { it.error.trim() }
                .filter { it.isNotBlank() }
                .groupingBy { it.lowercase().take(80) }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .take(8)
        }
        if (commonErrors.isEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Surface),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "🎉 Nenhum erro recorrente ainda. Continue praticando com Elias!",
                    color = Green,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(14.dp)
                )
            }
        } else {
            commonErrors.forEachIndexed { i, (key, count) ->
                val sample = profile.errorLog.find {
                    it.error.lowercase().take(80) == key
                }?.error ?: key
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1a1210)),
                    border = BorderStroke(1.dp, Color(0xFF4a2020)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = Red.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                "×$count",
                                color = Red,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "#${i + 1} · $sample",
                                color = Color(0xFFe8eaf0),
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Histórico recente", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            profile.errorLog.takeLast(5).reversed().forEach { entry ->
                Text(
                    "· ${entry.timestamp.take(10)} — ${entry.error}",
                    color = Muted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        
        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = Border)
        Spacer(Modifier.height(16.dp))

        // ── PDF Report ─────────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("📄 Download Soft Skills Report", color = Color(0xFFe8eaf0), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(6.dp))
            Badge(containerColor = Purple.copy(alpha = 0.25f)) {
                Text("DeepSeek AI", color = Purple, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Personalized PDF with progress stats, soft skills radar, DeepSeek coaching narrative, and grammar mistake log.", 
            color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
        Spacer(Modifier.height(8.dp))
        
        val context = androidx.compose.ui.platform.LocalContext.current
        val coroutineScope = rememberCoroutineScope()
        var isGeneratingPdf by remember { mutableStateOf(false) }
        
        Button(
            onClick = {
                isGeneratingPdf = true
                coroutineScope.launch(Dispatchers.IO) {
                    try {
                        val narrative = vm.generatePdfNarrative(profile)
                        val pdfBytes = com.roberto.eliasaitutor.ui.components.PdfGenerator.generatePdfReport(context, profile, narrative)
                        
                        // Save to cache dir and share
                        val file = java.io.File(context.cacheDir, "elias_report_${System.currentTimeMillis()}.pdf")
                        file.writeBytes(pdfBytes)
                        
                        val uri = androidx.core.content.FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.provider",
                            file
                        )
                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "application/pdf"
                            putExtra(android.content.Intent.EXTRA_STREAM, uri)
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        
                        withContext(Dispatchers.Main) {
                            isGeneratingPdf = false
                            context.startActivity(android.content.Intent.createChooser(intent, "Share Report"))
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            isGeneratingPdf = false
                            android.widget.Toast.makeText(context, "Error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }, 
            enabled = !isGeneratingPdf,
            colors = ButtonDefaults.buttonColors(containerColor = Accent),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isGeneratingPdf) {
                CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("DeepSeek is writing narrative...")
            } else {
                Text("📥 Generate PDF Report")
            }
        }

        Spacer(Modifier.height(80.dp))
    }
}

@Composable
private fun MetricCard(label: String, value: String, emoji: String, modifier: Modifier = Modifier) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1a1e29)),
        border = BorderStroke(1.dp, Color(0xFF2d4070)), shape = RoundedCornerShape(16.dp),
        modifier = modifier) {
        Column(Modifier.padding(14.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(emoji, fontSize = 22.sp)
            Spacer(Modifier.height(4.dp))
            Text(value, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(label, color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SkillBar(label: String, value: Int) {
    val color = when { value >= 70 -> Green; value >= 45 -> Gold; else -> Red }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Muted, fontSize = 12.sp)
            Text("$value", color = Color(0xFFe8eaf0), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        LinearProgressIndicator(
            progress = { value / 100f },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = color, trackColor = Border,
        )
    }
}

@Composable
private fun SentimentRow(entry: SentimentEntry) {
    val (emoji, color) = when (entry.detected) {
        "frustrated"   -> "😤" to Red
        "enthusiastic" -> "🤩" to Green
        "confused"     -> "🤔" to Gold
        "bored"        -> "😐" to Muted
        else           -> "😊" to Muted
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = Surface),
        border = BorderStroke(1.dp, Border),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(IntrinsicSize.Min)) {
            // Sentiment Indicator Bar
            Box(Modifier.width(4.dp).fillMaxHeight().background(color))
            
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(emoji, fontSize = 20.sp)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(entry.detected.replaceFirstChar { it.uppercase() }, color = color,
                        fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(entry.timestamp.take(16).replace("T", " "), color = Muted, fontSize = 10.sp)
                    if (entry.cue.isNotEmpty())
                        Text(entry.cue.take(70), color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
                }
            }
        }
    }
}
