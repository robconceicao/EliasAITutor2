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
import com.roberto.eliasaitutor.model.PronunciationFocus
import com.roberto.eliasaitutor.ui.theme.EliasTokens
import com.roberto.eliasaitutor.viewmodel.EliasViewModel
import java.io.File

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
fun ShadowingScreen(vm: EliasViewModel) {
    val context = LocalContext.current
    val phrase by vm.shadowPhrase.collectAsState()
    val score by vm.shadowScore.collectAsState()
    val feedback by vm.shadowFeedback.collectAsState()
    val transcript by vm.shadowTranscript.collectAsState()
    val isLoading by vm.isLoading.collectAsState()
    val isIaSpeaking by vm.isIaSpeaking.collectAsState()
    val canAdvance by vm.echoCanAdvance.collectAsState()
    val passThreshold = vm.echoPassThreshold

    var isRecording by remember { mutableStateOf(false) }
    var isPlayingElias by remember { mutableStateOf(false) }
    var isPlayingYou by remember { mutableStateOf(false) }
    // Prefer real TTS state when Elias is playing via backend stream
    val eliasActive = isPlayingElias || isIaSpeaking
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var lastAudioFile by remember { mutableStateOf<File?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            recorder?.let {
                try {
                    it.stop()
                } catch (e: Exception) {}
                try {
                    it.release()
                } catch (e: Exception) {}
            }
        }
    }

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
            "Echo Mode",
            color = EliasTokens.TextMain,
            fontSize = 26.sp,
            fontWeight = FontWeight.ExtraBold
        )
        Text(
            "Imite · IPA · shadowing · aquisição natural",
            color = Muted,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
        val focus = remember { PronunciationFocus.focusOfDay() }
        Spacer(Modifier.height(10.dp))
        Surface(
            color = Purple.copy(alpha = 0.15f),
            shape = RoundedCornerShape(20.dp)
        ) {
            Text(
                "Foco de hoje: $focus",
                color = Purple,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }
        Text(
            PronunciationFocus.coachingTip(focus),
            color = Muted,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp, start = 8.dp, end = 8.dp)
        )

        Spacer(Modifier.height(24.dp))

        if (phrase.isEmpty()) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Button(
                    onClick = { vm.generateShadowPhrase() },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.height(56.dp).fillMaxWidth(0.85f)
                ) {
                    Icon(Icons.Default.Refresh, null)
                    Spacer(Modifier.width(12.dp))
                    Text("Frase do dia + IPA", fontWeight = FontWeight.Bold)
                }
            }
        } else {
            // English phrase card
            Card(
                colors = CardDefaults.cardColors(containerColor = EliasTokens.SurfaceElevated),
                border = BorderStroke(1.dp, Accent.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "FRASE",
                        color = Accent,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "\"$phrase\"",
                        color = EliasTokens.TextMain,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        lineHeight = 32.sp
                    )
                }
            }

            // IPA card — Fase 4 wireframe highlight
            val ipa by vm.shadowIpa.collectAsState()
            if (ipa.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(EliasTokens.IpaBrush, RoundedCornerShape(20.dp))
                            .border(1.dp, Purple.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
                            .padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "IPA",
                            color = Purple,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 3.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            ipa,
                            color = EliasTokens.TextMain,
                            fontSize = 22.sp,
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 30.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Schwa /ə/ · linking · ritmo · elisão",
                            color = Muted,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "1 Ouça  →  2 Grave  →  3 Compare",
                color = Muted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(16.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 1. Listen to Elias
                val eliasBorder = if (eliasActive) Accent else Border
                val eliasBg     = if (eliasActive) Accent.copy(alpha = 0.2f) else Surface
                
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
                    Text("Elias", color = if (eliasActive) Accent else Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
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
                                    val rec = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                                        MediaRecorder(context)
                                    } else {
                                        @Suppress("DEPRECATION")
                                        MediaRecorder()
                                    }
                                    recorder = rec.apply {
                                        setAudioSource(MediaRecorder.AudioSource.MIC)
                                        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                                        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                                        setAudioEncodingBitRate(128_000)
                                        setAudioSamplingRate(44_100)
                                        setOutputFile(file.absolutePath)
                                        prepare()
                                        start()
                                    }
                                    isRecording = true
                                } else {
                                    try {
                                        recorder?.apply {
                                            stop()
                                            release()
                                        }
                                    } catch (_: Exception) {
                                        try { recorder?.release() } catch (_: Exception) {}
                                    }
                                    recorder = null
                                    isRecording = false
                                    lastAudioFile?.let { vm.submitShadowingAudio(it, phrase) }
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
                score!! >= passThreshold -> Gold
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
                            progress = { score!! / 100f },
                            color = scoreColor,
                            trackColor = scoreColor.copy(alpha = 0.1f),
                            strokeWidth = 6.dp,
                            modifier = Modifier.size(70.dp)
                        )
                        Text(
                            "${score!!}%",
                            color = scoreColor,
                            fontWeight = FontWeight.Black,
                            fontSize = 18.sp
                        )
                    }
                    
                    Spacer(Modifier.width(20.dp))
                    
                    Column {
                        Text(
                            when {
                                score!! >= 85 -> "Excelente! 🏆"
                                canAdvance -> "Aprovado — pode avançar 🌊"
                                else -> "Ainda não — pratique de novo 💪"
                            },
                            color = scoreColor,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Acerto: ${score!!}% · Mínimo: $passThreshold%",
                            color = Muted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 2.dp)
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
                        if (transcript.isNotBlank()) {
                            Text(
                                "ASR: “$transcript”",
                                color = Accent.copy(alpha = 0.85f),
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            // Advance only if pronunciation meets tolerance (task v3.1)
            if (canAdvance) {
                Button(
                    onClick = { vm.generateShadowPhrase() },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    Text("Próxima frase →", color = Color.White, fontWeight = FontWeight.Bold)
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    Text(
                        "Grave de novo para atingir $passThreshold% e desbloquear a próxima frase.",
                        color = Red,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            // Clear score so user can re-record same phrase
                            // Keep phrase; only reset score UI via re-record
                        },
                        enabled = false,
                        border = BorderStroke(1.dp, Border),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Próxima frase bloqueada", color = Muted)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Use o microfone acima para tentar outra vez nesta frase.",
                        color = Muted,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center
                    )
                }
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