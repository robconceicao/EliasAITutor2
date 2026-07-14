package com.roberto.eliasaitutor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Store
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.roberto.eliasaitutor.program.ProgramViewModel
import com.roberto.eliasaitutor.ui.screens.*
import com.roberto.eliasaitutor.viewmodel.EliasViewModel

private val Bg      = Color(0xFF0d0f14)
private val Surface = Color(0xFF161922)
private val Accent  = Color(0xFF4f8ef7)
private val Muted   = Color(0xFF7a8099)

class MainActivity : ComponentActivity() {
    private val eliasVm: EliasViewModel by viewModels { EliasViewModel.Factory(application) }
    private val programVm: ProgramViewModel by viewModels { ProgramViewModel.Factory(application) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val openProgram = intent?.getBooleanExtra("open_program", false) == true
        setContent {
            EliasApp(eliasVm, programVm, initialTab = if (openProgram) 0 else 1)
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}

@Composable
fun EliasApp(
    vm: EliasViewModel,
    programVm: ProgramViewModel,
    initialTab: Int = 1,
) {
    var currentTab by remember { mutableIntStateOf(initialTab) }
    var programSubScreen by remember { mutableStateOf("home") } // home | progress
    var showEndSessionConfirm by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val toastMsg by vm.toastMessage.collectAsState()
    val profile by vm.profile.collectAsState()
    val practice by programVm.practice.collectAsState()
    val bubbles by vm.chatBubbles.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    // D1 — pause timer when app backgrounds
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> programVm.onAppForeground()
                Lifecycle.Event.ON_PAUSE -> programVm.onAppBackground()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(toastMsg) {
        toastMsg?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            vm.clearToast()
        }
    }

    val tabs = listOf(
        TabItem("Programa", Icons.Default.School),
        TabItem("Immersion", Icons.Default.Hearing),
        TabItem("Chat", Icons.AutoMirrored.Filled.Chat),
        TabItem("Echo", Icons.Default.GraphicEq),
        TabItem("Progress", Icons.AutoMirrored.Filled.ShowChart),
        TabItem("Store", Icons.Default.Store)
    )

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = Surface, contentColor = Muted) {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = currentTab == index,
                        onClick = {
                            // Leaving Chat during free mode is fine; PROGRAM session
                            // stays active until Encerrar (timer continues on other tabs).
                            // If user opens Chat while no practice is running but context
                            // is still PROGRAM, reset to FREE so level chips work again.
                            if (index == 2 && practice == null &&
                                vm.chatContext.value.type ==
                                com.roberto.eliasaitutor.model.ChatType.PROGRAM
                            ) {
                                vm.endProgramSession()
                            }
                            currentTab = index
                            if (index == 0) programSubScreen = "home"
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.title) },
                        label = { Text(tab.title, fontSize = 9.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Accent,
                            selectedTextColor = Accent,
                            unselectedIconColor = Muted,
                            unselectedTextColor = Muted,
                            indicatorColor = Accent.copy(alpha = 0.1f)
                        )
                    )
                }
            }
        },
        containerColor = Bg
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding).fillMaxSize()) {
            when (currentTab) {
                0 -> {
                    if (programSubScreen == "progress") {
                        ProgramProgressScreen(programVm) { programSubScreen = "home" }
                    } else {
                        ProgramHomeScreen(
                            programVm = programVm,
                            userId = profile.userId.ifBlank { "local_user" },
                            onStartChat = { week, title, lexis, grammar, phase, sessionType, _ ->
                                val uid = profile.userId.ifBlank { "local_user" }
                                vm.beginProgramSession(
                                    week = week,
                                    title = title,
                                    lexis = lexis,
                                    grammar = grammar,
                                    phase = phase,
                                    sessionType = sessionType,
                                    userId = uid,
                                )
                                currentTab = 2
                            },
                            onOpenProgress = { programSubScreen = "progress" },
                        )
                    }
                }
                1 -> ImmersionScreen(vm)
                2 -> ChatScreen(vm)
                3 -> ShadowingScreen(vm)
                4 -> ProgressScreen(vm)
                5 -> StoreScreen(vm)
            }

            // F4 — session timer overlay on chat when program practice is active
            if (practice != null && currentTab == 2) {
                ProgramSessionTimerBar(
                    elapsedSeconds = practice!!.elapsedSeconds,
                    goalMinutes = practice!!.goalMinutes,
                    goalReached = practice!!.goalReachedNotified,
                    week = practice!!.week,
                    onEnd = { showEndSessionConfirm = true }
                )
            }

            if (showEndSessionConfirm && practice != null) {
                val mm = practice!!.elapsedSeconds / 60
                val ss = practice!!.elapsedSeconds % 60
                AlertDialog(
                    onDismissRequest = { showEndSessionConfirm = false },
                    title = { Text("Encerrar sessão?") },
                    text = {
                        Text(
                            "Tempo: %d:%02d / %d min. O relatório de correção será gerado se a sessão for longa o suficiente."
                                .format(mm, ss, practice!!.goalMinutes)
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showEndSessionConfirm = false
                                val transcript = bubbles.joinToString("\n") { b ->
                                    val role = if (b.isUser) "Student" else "Tutor"
                                    "$role: ${b.message}"
                                }
                                programVm.endConversationSession(transcript) {
                                    vm.endProgramSession()
                                }
                                currentTab = 0 // back to Programa for feedback report
                            }
                        ) { Text("Encerrar", color = Color(0xFFEF4444)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showEndSessionConfirm = false }) {
                            Text("Continuar")
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ProgramSessionTimerBar(
    elapsedSeconds: Int,
    goalMinutes: Int,
    goalReached: Boolean,
    week: Int,
    onEnd: () -> Unit,
) {
    val mm = elapsedSeconds / 60
    val ss = elapsedSeconds % 60
    Surface(
        color = Surface,
        tonalElevation = 4.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "PROGRAM · Semana $week",
                    color = if (goalReached) Color(0xFF10B981) else Accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "%d:%02d / %d min".format(mm, ss, goalMinutes),
                    color = Color(0xFFE8EAF0),
                    fontSize = 14.sp
                )
                Text(
                    if (goalReached) "Meta atingida — continue se quiser"
                    else "Pronúncia Máxima · relatório ≥10 min",
                    color = Muted,
                    fontSize = 10.sp
                )
            }
            TextButton(onClick = onEnd) {
                Icon(Icons.Default.Stop, contentDescription = null, tint = Color(0xFFEF4444))
                Spacer(Modifier.width(4.dp))
                Text("Encerrar", color = Color(0xFFEF4444))
            }
        }
    }
}

data class TabItem(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
