package com.roberto.eliasaitutor.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.roberto.eliasaitutor.viewmodel.EliasViewModel
import java.io.File

private val Bg      = Color(0xFF0d0f14)
private val Surface = Color(0xFF161922)
private val Border  = Color(0xFF252a35)
private val Accent  = Color(0xFF4f8ef7)
private val Gold    = Color(0xFFf7c94f)
private val Green   = Color(0xFF3ecf8e)
private val Red     = Color(0xFFf76f6f)
private val Muted   = Color(0xFF7a8099)

@Composable
fun ShadowingScreen(vm: EliasViewModel) {
    val context = LocalContext.current
    val phrase by vm.shadowPhrase.collectAsState()
    val score by vm.shadowScore.collectAsState()
    val feedback by vm.shadowFeedback.collectAsState()
    val isLoading by vm.isLoading.collectAsState()

    var isRecording by remember { mutableStateOf(false) }
    var isPlayingElias by remember { mutableStateOf(false) }
    var isPlayingYou by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var lastAudioFile by remember { mutableStateOf<File?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            // Handle permission denied
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
        Text(
            "🌊 Echo Mode",
            color = Color(0xFFe8eaf0),
            fontSize = 24.sp,
            fontWeight = FontWeight.ExtraBold
        )
        Text(
            "Natural acquisition through imitation.",
            color = Muted,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(32.dp))

        if (phrase.isEmpty()) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Button(
                    onClick = { vm.generateShadowPhrase() },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.height(56.dp).fillMaxWidth(0.7f)
                ) {
                    Icon(Icons.Default.Refresh, null)
                    Spacer(Modifier.width(12.dp))
                    Text("Get Daily Phrase", fontWeight = FontWeight.Bold)
                }
            }
        } else {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1a1e29)),
                border = BorderStroke(1.dp, Accent.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("LISTEN & REPEAT", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "\"$phrase\"",
                        color = Color(0xFFe8eaf0),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        lineHeight = 32.sp
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 1. Listen to Elias
                val eliasBorder = if (isPlayingElias) Accent else Border
                val eliasBg     = if (isPlayingElias) Accent.copy(alpha = 0.2f) else Surface
                
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(
                        onClick = { 
                            isPlayingElias = true
                            vm.speakText(phrase) { isPlayingElias = false }
                        },
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(eliasBg)
                            .border(2.dp, eliasBorder, CircleShape)
                    ) {
                        Icon(Icons.Default.PlayArrow, "Listen", tint = Accent, modifier = Modifier.size(32.dp))
                    }
                    Text("Elias", color = if (isPlayingElias) Accent else Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                }

                // 2. Record Yourself
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(
                        onClick = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            } else {
                                if (!isRecording) {
                                    val file = File(context.cacheDir, "echo_${System.currentTimeMillis()}.m4a")
                                    lastAudioFile = file
                                    recorder = MediaRecorder().apply {
                                        setAudioSource(MediaRecorder.AudioSource.MIC)
                                        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                                        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                                        setOutputFile(file.absolutePath)
                                        prepare()
                                        start()
                                    }
                                    isRecording = true
                                } else {
                                    recorder?.apply {
                                        stop()
                                        release()
                                    }
                                    recorder = null
                                    isRecording = false
                                    lastAudioFile?.let { vm.submitShadowingAudio(it) }
                                }
                            }
                        },
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(if (isRecording) Red.copy(alpha = 0.2f) else Accent.copy(alpha = 0.1f))
                            .border(2.dp, if (isRecording) Red else Accent, CircleShape)
                    ) {
                        Icon(
                            if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                            "Record",
                            tint = if (isRecording) Red else Accent,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    Text(if (isRecording) "Stop" else "Record", color = if (isRecording) Red else Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                }

                // 3. Listen to Yourself (Echo)
                val youBorder = if (isPlayingYou) Gold else (if (lastAudioFile != null) Border else Border.copy(alpha = 0.5f))
                val youBg     = if (isPlayingYou) Gold.copy(alpha = 0.2f) else (if (lastAudioFile != null) Surface else Surface.copy(alpha = 0.5f))

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(
                        onClick = { 
                            lastAudioFile?.let { 
                                isPlayingYou = true
                                vm.playLocalFile(it) { isPlayingYou = false }
                            } 
                        },
                        enabled = lastAudioFile != null && !isRecording,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(youBg)
                            .border(2.dp, youBorder, CircleShape)
                    ) {
                        Icon(Icons.Default.GraphicEq, "Echo", tint = if (lastAudioFile != null) Gold else Muted)
                    }
                    Text("You", color = if (isPlayingYou) Gold else Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }

            if (isRecording) {
                Text("Listening to you...", color = Red, fontSize = 13.sp, modifier = Modifier.padding(top = 16.dp), fontWeight = FontWeight.Medium)
            }
        }

        Spacer(Modifier.height(32.dp))

        if (isLoading) {
            CircularProgressIndicator(color = Accent, strokeWidth = 3.dp)
        }

        if (score != null && !isRecording) {
            val scoreColor = when {
                score!! >= 85 -> Green
                score!! >= 65 -> Gold
                else -> Red
            }
            
            Card(
                colors = CardDefaults.cardColors(containerColor = Surface),
                border = BorderStroke(1.dp, scoreColor.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth(0.9f),
                shape = RoundedCornerShape(20.dp)
            ) {
                Row(
                    Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            progress = score!! / 100f,
                            color = scoreColor,
                            trackColor = scoreColor.copy(alpha = 0.1f),
                            strokeWidth = 6.dp,
                            modifier = Modifier.size(70.dp)
                        )
                        Text("$score", color = scoreColor, fontWeight = FontWeight.Black, fontSize = 20.sp)
                    }
                    
                    Spacer(Modifier.width(20.dp))
                    
                    Column {
                        Text(
                            if (score!! >= 85) "Native Level! 🏆" else if (score!! >= 65) "Great Echo! 🌊" else "Keep Trying! 💪",
                            color = scoreColor,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (feedback.isNotEmpty()) {
                            Text(
                                feedback,
                                color = Muted,
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { vm.generateShadowPhrase() },
                colors = ButtonDefaults.buttonColors(containerColor = Surface),
                border = BorderStroke(1.dp, Border),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Next Phrase →", color = Accent)
            }
        }
        
        Spacer(Modifier.height(32.dp))
        
        Text(
            "TIP: Listen to Elias first, then repeat. Compare your 'Echo' to his voice to improve intuitively.",
            color = Muted.copy(alpha = 0.7f),
            fontSize = 10.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 8.dp)
        )
    }
}