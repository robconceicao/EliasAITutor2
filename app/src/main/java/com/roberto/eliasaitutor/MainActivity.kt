package com.roberto.eliasaitutor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Store
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.roberto.eliasaitutor.ui.screens.*
import com.roberto.eliasaitutor.viewmodel.EliasViewModel

private val Bg      = Color(0xFF0d0f14)
private val Surface = Color(0xFF161922)
private val Accent  = Color(0xFF4f8ef7)
private val Muted   = Color(0xFF7a8099)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val viewModel: EliasViewModel by viewModels { EliasViewModel.Factory(application) }

        setContent {
            EliasApp(viewModel)
        }
    }
}

@Composable
fun EliasApp(vm: EliasViewModel) {
    var currentTab by remember { mutableIntStateOf(0) }
    val tabs = listOf(
        TabItem("Chat", Icons.Default.Chat),
        TabItem("Shadowing", Icons.Default.GraphicEq),
        TabItem("Progress", Icons.Default.ShowChart),
        TabItem("Store", Icons.Default.Store)
    )

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = Surface, contentColor = Muted) {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = currentTab == index,
                        onClick = { currentTab = index },
                        icon = { Icon(tab.icon, contentDescription = tab.title) },
                        label = { Text(tab.title) },
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
        Surface(modifier = Modifier.padding(innerPadding), color = Bg) {
            when (currentTab) {
                0 -> ChatScreen(vm)
                1 -> ShadowingScreen(vm)
                2 -> ProgressScreen(vm)
                3 -> StoreScreen(vm)
            }
        }
    }
}

data class TabItem(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)