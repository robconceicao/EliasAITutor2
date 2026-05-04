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
import com.roberto.eliasaitutor.ui.components.RadarChart
import com.roberto.eliasaitutor.viewmodel.EliasViewModel

private val Bg      = Color(0xFF0d0f14)
private val Surface = Color(0xFF161922)
private val Border  = Color(0xFF252a35)
private val Accent  = Color(0xFF4f8ef7)
private val Gold    = Color(0xFFf7c94f)
private val Green   = Color(0xFF3ecf8e)
private val Red     = Color(0xFFf76f6f)
private val Muted   = Color(0xFF7a8099)
private val Purple  = Color(0xFFa855f7)

@Composable
fun ProgressScreen(vm: EliasViewModel) {
    val profile   by vm.profile.collectAsState()
    val isLoading by vm.isLoading.collectAsState()

    val lvl5Xp  = GameConstants.LEVEL_THRESHOLDS[5]!!
    val lvl10Xp = GameConstants.LEVEL_THRESHOLDS[10]!!

    Column(Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState()).padding(16.dp)) {

        Text("📈 Your Progress", color = Color(0xFFe8eaf0), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        // ── Key metrics ────────────────────────────────────────────────────
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard("Level",    "${profile.level}",           "🎯", Modifier.weight(1f))
            MetricCard("Total XP", "${profile.xp}",             "⚡", Modifier.weight(1f))
            MetricCard("Coins",    "${profile.coins}",          "🪙", Modifier.weight(1f))
            MetricCard("Messages", "${profile.messagesCount}",  "💬", Modifier.weight(1f))
        }

        Spacer(Modifier.height(16.dp))

        // ── Streak card ────────────────────────────────────────────────────
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1a1410)),
            border = BorderStroke(1.dp, Gold), shape = RoundedCornerShape(14.dp)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("🔥 Daily Streak", color = Gold, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("${profile.streak} day${if (profile.streak != 1) "s" else ""} in a row",
                        color = Color(0xFFe8eaf0), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("+${GameConstants.STREAK_BONUS_COINS} coins awarded each consecutive day",
                        color = Muted, fontSize = 12.sp)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🛡️", fontSize = 28.sp)
                    Text("${profile.streakFreezeCount}", color = Gold, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("Shields", color = Muted, fontSize = 10.sp)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Divider(color = Border)
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
        Divider(color = Border)
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
        Divider(color = Border)
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
        Divider(color = Border)
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
        Divider(color = Border)
        Spacer(Modifier.height(16.dp))

        // ── Grammar Mistake Log ────────────────────────────────────────────
        Text("📝 Grammar Mistakes to Fix", color = Color(0xFFe8eaf0), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        if (profile.errorLog.isEmpty()) {
            Text("🎉 No grammar mistakes logged yet. Keep it up!", color = Green, fontSize = 13.sp)
        } else {
            profile.errorLog.takeLast(15).reversed().forEach { entry ->
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1a1210)),
                    border = BorderStroke(1.dp, Color(0xFF4a2020)), shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Column(Modifier.padding(10.dp)) {
                        Text(entry.timestamp.take(10), color = Muted, fontSize = 11.sp)
                        Text(entry.error, color = Red, fontSize = 13.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(80.dp))
    }
}

@Composable
private fun MetricCard(label: String, value: String, emoji: String, modifier: Modifier = Modifier) {
    Card(colors = CardDefaults.cardColors(containerColor = Surface),
        border = BorderStroke(1.dp, Border), shape = RoundedCornerShape(12.dp),
        modifier = modifier) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(emoji, fontSize = 18.sp)
            Text(value, color = Accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(label, color = Muted, fontSize = 10.sp)
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