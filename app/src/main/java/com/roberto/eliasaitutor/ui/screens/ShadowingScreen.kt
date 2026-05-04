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
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var audioFile by remember { mutableStateOf<File?>(null) }

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
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "🎤 Shadowing Practice",
            color = Color(0xFFe8eaf0),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Listen to Elias, then repeat for a clarity score.",
            color = Muted,
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(24.dp))

        if (phrase.isEmpty()) {
            Button(
                onClick = { vm.generateShadowPhrase() },
                colors = ButtonDefaults.buttonColors(containerColor = Accent)
            ) {
                Icon(Icons.Default.Refresh, null)
                Spacer(Modifier.width(8.dp))
                Text("Generate Phrase")
            }
        } else {
            Card(
                colors = CardDefaults.cardColors(containerColor = Surface),
                border = BorderStroke(1.dp, Border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("SAY THIS PHRASE:", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "\"$phrase\"",
                        color = Color(0xFFe8eaf0),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        lineHeight = 26.sp
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // Hear Elias
                IconButton(
                    onClick = { /* TTS Logic handled in VM or Screen */ },
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Surface)
                        .border(1.dp, Border, CircleShape)
                ) {
                    Icon(Icons.Default.PlayArrow, "Listen", tint = Accent)
                }

                // Record
                IconButton(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            if (!isRecording) {
                                // Start recording
                                val file = File(context.cacheDir, "shadow_audio.3gp")
                                audioFile = file
                                recorder = MediaRecorder().apply {
                                    setAudioSource(MediaRecorder.AudioSource.MIC)
                                    setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                                    setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                                    setOutputFile(file.absolutePath)
                                    prepare()
                                    start()
                                }
                                isRecording = true
                            } else {
                                // Stop recording
                                recorder?.apply {
                                    stop()
                                    release()
                                }
                                recorder = null
                                isRecording = false
                                
                                // Call VM to score (In a real app, send the file bytes)
                                // vm.scoreShadowing(phrase, "[Transcription Placeholder]")
                            }
                        }
                    },
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(if (isRecording) Red else Surface)
                        .border(1.dp, if (isRecording) Red else Border, CircleShape)
                ) {
                    Icon(if (isRecording) Icons.Default.Stop else Icons.Default.Mic, "Record", tint = if (isRecording) Color.White else Accent)
                }
            }

            if (isRecording) {
                Text("Recording...", color = Red, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }

        Spacer(Modifier.height(32.dp))

        if (score != null) {
            val scoreColor = when {
                score!! >= 80 -> Green
                score!! >= 50 -> Gold
                else -> Red
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = Surface),
                border = BorderStroke(2.dp, scoreColor),
                modifier = Modifier.width(180.dp)
            ) {
                Column(
                    Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "$score",
                        color = scoreColor,
                        fontSize = 48.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text("CLARITY SCORE", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (score!! >= 80) "Excellent! 🌟" else if (score!! >= 50) "Good! 👍" else "Try Again 💪",
                        color = scoreColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            
            if (feedback.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    feedback,
                    color = Color(0xFFe8eaf0),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { vm.generateShadowPhrase() },
                colors = ButtonDefaults.buttonColors(containerColor = Surface),
                border = BorderStroke(1.dp, Border)
            ) {
                Text("Next Phrase →", color = Accent)
            }
        }
    }
}