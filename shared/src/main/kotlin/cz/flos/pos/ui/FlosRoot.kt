package cz.flos.pos.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeliveryDining
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.PointOfSale
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.dp
import cz.flos.pos.LocalPlatform

enum class Screen(val label: String, val icon: ImageVector) {
    SELL("Prodej", Icons.Rounded.PointOfSale),
    ORDERS("Objednávky", Icons.Rounded.DeliveryDining),
    HISTORY("Účtenky", Icons.Rounded.ReceiptLong),
    PRODUCTS("Produkty", Icons.Rounded.Inventory2),
    REPORT("Uzávěrka", Icons.Rounded.Insights),
    SETTINGS("Nastavení", Icons.Rounded.Settings),
}

/** Navigation icon with the number of unpaid orders on the Orders tab. */
@Composable
private fun NavIcon(s: Screen, openOrders: Int) {
    if (s == Screen.ORDERS && openOrders > 0) {
        BadgedBox(badge = { Badge { Text("$openOrders") } }) { Icon(s.icon, contentDescription = null) }
    } else {
        Icon(s.icon, contentDescription = null)
    }
}

private val LocalWide = compositionLocalOf { false }

/** True on tablets, desktop windows and phones in landscape: the layout then shows panels side by side. */
@Composable
fun isWide(): Boolean = LocalWide.current

/** Entry point shared by Android and Windows. */
@Composable
fun FlosScreen(vm: PosViewModel) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalPlatform provides vm.platform, LocalWide provides (maxWidth >= 600.dp)) {
            FlosTheme { FlosRoot(vm) }
        }
    }
}

@Composable
private fun FlosRoot(vm: PosViewModel) {
    var screen by rememberSaveable { mutableStateOf(Screen.SELL) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    val wide = isWide()
    val settings by vm.settings.collectAsState()
    val openOrders by vm.openOrders.collectAsState()
    // Set before anything below is composed, and re-key the tree so every tr() call re-runs on a switch.
    I18n.language = settings.language
    key(settings.language) {

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (!wide) NavigationBar {
                Screen.entries.forEach { s ->
                    NavigationBarItem(
                        selected = screen == s,
                        onClick = { screen = s },
                        icon = { NavIcon(s, openOrders.size) },
                        label = { Text(tr(s.label)) },
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
                        icon = { NavIcon(s, openOrders.size) },
                        label = { Text(tr(s.label)) },
                    )
                }
            }
            when (screen) {
                Screen.SELL -> SellScreen(vm)
                Screen.ORDERS -> OrdersScreen(vm, onEdit = { screen = Screen.SELL })
                Screen.PRODUCTS -> ProductsScreen(vm)
                Screen.HISTORY -> HistoryScreen(vm, onEditOrder = { screen = Screen.SELL })
                Screen.REPORT -> ReportScreen(vm)
                Screen.SETTINGS -> SettingsScreen(vm)
            }
        }
        }
    }
}
}
