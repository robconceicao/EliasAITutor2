package com.roberto.eliasaitutor.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roberto.eliasaitutor.viewmodel.EliasViewModel

private val Bg      = Color(0xFF0d0f14)
private val Surface = Color(0xFF161922)
private val Border  = Color(0xFF252a35)
private val Accent  = Color(0xFF4f8ef7)
private val Green   = Color(0xFF3ecf8e)
private val Muted   = Color(0xFF7a8099)

@Composable
fun ImmersionScreen(vm: EliasViewModel) {
    var currentLevel by remember { mutableIntStateOf(1) }
    var step by remember { mutableIntStateOf(0) }
    
    val levelsData = mapOf(
        1 to listOf(
            ImmersionTask("I am drinking a glass of cold water.", "🚰", listOf("🚰", "🍎", "🚗", "🏠")),
            ImmersionTask("The blue car is moving fast.", "🚙", listOf("🚗", "🚙", "🛵", "🚲")),
            ImmersionTask("I desperately need a cup of coffee.", "☕", listOf("🍵", "🥤", "☕", "🍺")),
            ImmersionTask("It's starting to rain, I need an umbrella.", "☂️", listOf("☀️", "☂️", "❄️", "🌬️")),
            ImmersionTask("I am reading a very interesting book.", "📖", listOf("📖", "💻", "📱", "📺")),
            ImmersionTask("The cat is sleeping on the sofa.", "🐱", listOf("🐶", "🐱", "🐭", "🐹"))
        ),
        2 to listOf(
            ImmersionTask("He is running very fast in the park.", "🏃", listOf("🏃", "🚶", "🛌", "🧘")),
            ImmersionTask("They are eating a delicious pizza.", "🍕", listOf("🍔", "🍟", "🍕", "🥗")),
            ImmersionTask("The baby is crying because he is hungry.", "😭", listOf("😂", "😭", "😴", "🥳")),
            ImmersionTask("We are dancing at the party!", "💃", listOf("💃", "🏃", "🛌", "🧘")),
            ImmersionTask("She is swimming in the ocean.", "🏊", listOf("🚴", "🚣", "🏊", "🚶")),
            ImmersionTask("The sun is shining in the sky.", "☀️", listOf("☁️", "🌧️", "☀️", "❄️"))
        ),
        3 to listOf(
            ImmersionTask("I am very happy today!", "😊", listOf("😡", "😢", "😊", "😴")),
            ImmersionTask("It is very cold outside.", "❄️", listOf("🔥", "❄️", "☁️", "☀️")),
            ImmersionTask("The pizza is very hot!", "🔥", listOf("❄️", "🔥", "🌬️", "☔")),
            ImmersionTask("I am so tired, I need to sleep.", "😴", listOf("😴", "🥳", "🏃", "🚴")),
            ImmersionTask("This music is very loud.", "🔊", listOf("🔇", "🔉", "🔊", "🎵")),
            ImmersionTask("I am hungry, I want a burger.", "🍔", listOf("🍕", "🍟", "🍔", "🥗"))
        )
    )

    val immersionData = levelsData[currentLevel] ?: levelsData[1]!!

    Column(
        Modifier.fillMaxSize().background(Bg).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header with Progress
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("🎧 Immersion (Level $currentLevel)", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                Text("Silent Period: Sound-Action Bond", color = Muted, fontSize = 12.sp)
            }
            if (step < immersionData.size) {
                Text("${step + 1}/${immersionData.size}", color = Accent, fontWeight = FontWeight.Bold)
            }
        }

        LinearProgressIndicator(
            progress = if (immersionData.isEmpty()) 0f else step.toFloat() / immersionData.size,
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp).clip(RoundedCornerShape(4.dp)),
            color = Accent,
            trackColor = Border
        )

        Spacer(Modifier.height(32.dp))

        if (step < immersionData.size) {
            val current = immersionData[step]
            
            Box(contentAlignment = Alignment.Center) {
                Surface(
                    Modifier.size(120.dp),
                    shape = RoundedCornerShape(40.dp),
                    color = Accent.copy(alpha = 0.05f),
                    border = BorderStroke(2.dp, Accent.copy(alpha = 0.3f))
                ) {}
                IconButton(
                    onClick = { vm.speakText(current.phrase) },
                    modifier = Modifier.size(100.dp).clip(RoundedCornerShape(32.dp)).background(Accent.copy(alpha = 0.1f))
                ) {
                    Icon(Icons.Default.PlayArrow, "Listen", tint = Accent, modifier = Modifier.size(56.dp))
                }
            }
            
            Spacer(Modifier.height(48.dp))
            
            Text("WHAT DID YOU HEAR?", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            Spacer(Modifier.height(24.dp))
            
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    OptionCard(current.options[0], current.icon) { step++ }
                    OptionCard(current.options[1], current.icon) { step++ }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    OptionCard(current.options[2], current.icon) { step++ }
                    OptionCard(current.options[3], current.icon) { step++ }
                }
            }
        } else {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Surface),
                    border = BorderStroke(1.dp, Green.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🌟 Acquisition Complete!", color = Green, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text("Level $currentLevel finished! Your brain is mapping English sounds to reality.", color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 20.sp)
                        Spacer(Modifier.height(32.dp))
                        Button(
                            onClick = { 
                                if (currentLevel < levelsData.size) {
                                    currentLevel++
                                    step = 0
                                } else {
                                    currentLevel = 1
                                    step = 0
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Accent),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Refresh, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (currentLevel < levelsData.size) "Level Up Immersion" else "Restart Journey")
                        }
                    }
                }
            }
        }
        
        Spacer(Modifier.weight(1f))
        Text("TIP: Close your eyes while listening to visualize the action. \nThis activates the motor cortex for better retention.", color = Muted.copy(alpha = 0.5f), fontSize = 10.sp, textAlign = TextAlign.Center, lineHeight = 14.sp)
    }
}

@Composable
fun OptionCard(icon: String, correctIcon: String, onCorrect: () -> Unit) {
    var isError by remember { mutableStateOf(false) }
    val borderColor = if (isError) Red else Border
    
    Box(
        Modifier
            .size(100.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Surface)
            .border(2.dp, borderColor, RoundedCornerShape(20.dp))
            .clickable { 
                if (icon == correctIcon) {
                    onCorrect()
                } else {
                    isError = true
                }
            }
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(icon, fontSize = 40.sp)
    }
    
    // Reset error state if icon changes
    LaunchedEffect(icon) { isError = false }
}

private val Red = Color(0xFFf76f6f)

data class ImmersionTask(val phrase: String, val icon: String, val options: List<String>)
