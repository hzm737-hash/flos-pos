package cz.flos.pos.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

enum class Screen(val label: String, val icon: ImageVector) {
    SELL("Prodej", Icons.Filled.PointOfSale),
    PRODUCTS("Produkty", Icons.Filled.Inventory),
    HISTORY("Účtenky", Icons.Filled.Receipt),
    REPORT("Uzávěrka", Icons.Filled.Assessment),
    SETTINGS("Nastavení", Icons.Filled.Settings),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { FlosTheme { FlosRoot() } }
    }
}

/** True on tablets and on phones in landscape: the layout then shows panels side by side. */
@Composable
fun isWide(): Boolean = LocalConfiguration.current.screenWidthDp >= 600

@Composable
fun FlosRoot(vm: PosViewModel = viewModel()) {
    var screen by rememberSaveable { mutableStateOf(Screen.SELL) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    val wide = isWide()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (!wide) NavigationBar {
                Screen.entries.forEach { s ->
                    NavigationBarItem(
                        selected = screen == s,
                        onClick = { screen = s },
                        icon = { Icon(s.icon, contentDescription = null) },
                        label = { Text(s.label) },
                    )
                }
            }
        },
    ) { padding ->
        val busy by vm.busy.collectAsState()
        Column(Modifier.fillMaxSize().padding(padding)) {
        busy?.let { text ->
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
        }
        Row(Modifier.fillMaxSize()) {
            if (wide) NavigationRail {
                Screen.entries.forEach { s ->
                    NavigationRailItem(
                        selected = screen == s,
                        onClick = { screen = s },
                        icon = { Icon(s.icon, contentDescription = null) },
                        label = { Text(s.label) },
                    )
                }
            }
            when (screen) {
                Screen.SELL -> SellScreen(vm)
                Screen.PRODUCTS -> ProductsScreen(vm)
                Screen.HISTORY -> HistoryScreen(vm)
                Screen.REPORT -> ReportScreen(vm)
                Screen.SETTINGS -> SettingsScreen(vm)
            }
        }
        }
    }
}
