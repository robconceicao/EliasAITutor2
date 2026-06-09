package com.roberto.eliasaitutor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Store
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
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
    val context = LocalContext.current
    val toastMsg by vm.toastMessage.collectAsState()
    
    LaunchedEffect(toastMsg) {
        toastMsg?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            vm.clearToast()
        }
    }

    val tabs = listOf(
        TabItem("Immersion", Icons.Default.Hearing),
        TabItem("Chat", Icons.Default.Chat),
        TabItem("Echo Mode", Icons.Default.GraphicEq),
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
                        label = { Text(tab.title, fontSize = 10.sp) },
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
                0 -> ImmersionScreen(vm)
                1 -> ChatScreen(vm)
                2 -> ShadowingScreen(vm)
                3 -> ProgressScreen(vm)
                4 -> StoreScreen(vm)
            }
        }
    }
}

data class TabItem(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
