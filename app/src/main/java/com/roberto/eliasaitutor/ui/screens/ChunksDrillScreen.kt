package com.roberto.eliasaitutor.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roberto.eliasaitutor.program.ProgramViewModel

private val Bg = Color(0xFF0d0f14)
private val Surface = Color(0xFF161922)
private val Accent = Color(0xFF4f8ef7)
private val Muted = Color(0xFF7a8099)
private val TextMain = Color(0xFFE8EAF0)

@Composable
fun ChunksDrillScreen(programVm: ProgramViewModel) {
    val drill by programVm.drill.collectAsState()
    val d = drill ?: return
    val chunk = d.chunks.getOrNull(d.index)

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
                "Shadowing + IPA · ${d.index + 1}/${d.chunks.size}",
                color = TextMain,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
            IconButton(onClick = { programVm.closeDrill() }) {
                Icon(Icons.Default.Close, null, tint = Muted)
            }
        }

        LinearProgressIndicator(
            progress = { (d.index + 1f) / d.chunks.size.coerceAtLeast(1) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            color = Accent,
            trackColor = Color(0xFF2A2E3A),
        )

        if (chunk == null) {
            Text("Sem chunks para esta semana.", color = Muted)
            return
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Surface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(chunk.en, color = TextMain, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                if (chunk.ipa.isNotBlank()) {
                    Text(chunk.ipa, color = Accent, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    Text("IPA · foque schwa /ə/ e linking", color = Muted, fontSize = 11.sp)
                }
                Spacer(Modifier.height(16.dp))
                Text(chunk.pt, color = TextMain, fontSize = 16.sp)
                if (chunk.use.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(chunk.use, color = Muted, fontSize = 13.sp)
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    "1) Ouça o modelo  2) Shadowing (repita junto)  3) Próximo",
                    color = Muted,
                    fontSize = 12.sp
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { programVm.playCurrentChunk() },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Surface)
            ) {
                Icon(Icons.Default.VolumeUp, null, tint = Accent)
                Spacer(Modifier.width(8.dp))
                Text("Ouvir", color = TextMain)
            }
            Button(
                onClick = { programVm.nextChunk() },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Accent)
            ) {
                Text(if (d.index + 1 >= d.chunks.size) "Concluir" else "Próximo")
            }
        }
    }
}
