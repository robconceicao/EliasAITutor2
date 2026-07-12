package com.roberto.eliasaitutor.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roberto.eliasaitutor.program.UserProgramState

private val Bg = Color(0xFF0d0f14)
private val TextMain = Color(0xFFE8EAF0)
private val Muted = Color(0xFF7a8099)
private val Accent = Color(0xFF4f8ef7)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgramSettingsScreen(
    state: UserProgramState,
    onBack: () -> Unit,
    onSave: (startDate: String, mode: String, reminder: String?, goal: Int) -> Unit,
) {
    var startDate by remember { mutableStateOf(state.startDate) }
    var mode by remember { mutableStateOf(state.weekMode) }
    var reminder by remember { mutableStateOf(state.reminderTime ?: "19:00") }
    var reminderEnabled by remember { mutableStateOf(state.reminderTime != null) }
    var goal by remember { mutableStateOf(state.dailyGoalMinutes.toString()) }

    Column(Modifier.fillMaxSize().background(Bg)) {
        TopAppBar(
            title = { Text("Configurações do Programa", color = TextMain) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = TextMain)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg)
        )
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text("Data de início (YYYY-MM-DD)", color = Muted, fontSize = 12.sp)
            OutlinedTextField(
                value = startDate,
                onValueChange = { startDate = it },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                singleLine = true
            )

            Text("Modo de avanço da semana", color = Muted, fontSize = 12.sp)
            Row(Modifier.padding(vertical = 8.dp)) {
                FilterChip(selected = mode == "auto", onClick = { mode = "auto" }, label = { Text("Auto") })
                Spacer(Modifier.width(8.dp))
                FilterChip(selected = mode == "manual", onClick = { mode = "manual" }, label = { Text("Manual") })
            }

            Text("Meta diária (minutos)", color = Muted, fontSize = 12.sp)
            OutlinedTextField(
                value = goal,
                onValueChange = { goal = it.filter { c -> c.isDigit() }.take(3) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                singleLine = true
            )

            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Lembrete diário", color = TextMain)
                Switch(checked = reminderEnabled, onCheckedChange = { reminderEnabled = it })
            }
            if (reminderEnabled) {
                Text("Horário (HH:mm)", color = Muted, fontSize = 12.sp)
                OutlinedTextField(
                    value = reminder,
                    onValueChange = { reminder = it },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    singleLine = true,
                    placeholder = { Text("19:00") }
                )
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    onSave(
                        startDate,
                        mode,
                        if (reminderEnabled) reminder else null,
                        goal.toIntOrNull() ?: 30
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Accent)
            ) {
                Text("Salvar")
            }
        }
    }
}
