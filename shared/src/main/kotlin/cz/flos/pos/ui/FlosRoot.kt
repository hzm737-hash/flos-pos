package cz.flos.pos.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.EventSeat
import androidx.compose.material.icons.rounded.RequestQuote
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.TableView
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.SoupKitchen
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.DeliveryDining
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.TableRestaurant
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.PointOfSale
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Favorite
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.dp
import cz.flos.pos.LocalPlatform

enum class Screen(val label: String, val icon: ImageVector, val onPhoneBar: Boolean) {
    SELL("Prodej", Icons.Rounded.PointOfSale, true),
    TABLES("Stoly", Icons.Rounded.TableRestaurant, true),
    ORDERS("Objednávky", Icons.Rounded.DeliveryDining, true),
    HISTORY("Účtenky", Icons.Rounded.ReceiptLong, true),
    PRODUCTS("Produkty", Icons.Rounded.Inventory2, false),
    REPORT("Uzávěrka", Icons.Rounded.Insights, false),
    STATS("Statistiky", Icons.Rounded.BarChart, false),
    RECEIPT_STATS("Statistiky účtenek", Icons.Rounded.QueryStats, false),
    KITCHEN("Kuchyně", Icons.Rounded.SoupKitchen, false),
    CUSTOMERS("Zákazníci", Icons.Rounded.Groups, false),
    RESERVATIONS("Rezervace", Icons.Rounded.EventSeat, false),
    DOCUMENTS("Nabídky a zakázky", Icons.Rounded.RequestQuote, false),
    WAITERS("Obsluha", Icons.Rounded.Badge, false),
    PRODUCT_TOOLS("Excel a štítky", Icons.Rounded.TableView, false),
    SETTINGS("Nastavení", Icons.Rounded.Settings, false),
    ADVANCED("Pokročilé nastavení", Icons.Rounded.Tune, false),
    SUPPORT("Podpořit Flos", Icons.Rounded.Favorite, false),
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
            // A phone has room for five items: the four used most, and "More" for the rest.
            if (!wide) NavigationBar {
                Screen.entries.filter { it.onPhoneBar }.forEach { s ->
                    NavigationBarItem(
                        selected = screen == s,
                        onClick = { screen = s },
                        icon = { NavIcon(s, openOrders.size) },
                        label = { Text(tr(s.label), maxLines = 1) },
                    )
                }
                var more by remember { mutableStateOf(false) }
                val inMore = !screen.onPhoneBar
                NavigationBarItem(
                    selected = inMore,
                    onClick = { more = true },
                    icon = {
                        Box {
                            Icon(if (inMore) screen.icon else Icons.Rounded.MoreHoriz, contentDescription = null)
                            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                                Screen.entries.filter { !it.onPhoneBar }.forEach { s ->
                                    DropdownMenuItem(
                                        text = { Text(tr(s.label)) },
                                        leadingIcon = { Icon(s.icon, contentDescription = null) },
                                        onClick = { screen = s; more = false },
                                    )
                                }
                            }
                        }
                    },
                    label = { Text(if (inMore) tr(screen.label) else tr("Více"), maxLines = 1) },
                )
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
            // Ten screens do not fit the height of a landscape tablet, so the rail scrolls.
            if (wide) NavigationRail(Modifier.verticalScroll(rememberScrollState())) {
                Screen.entries.forEach { s ->
                    NavigationRailItem(
                        selected = screen == s,
                        onClick = { screen = s },
                        icon = { NavIcon(s, openOrders.size) },
                        label = { Text(tr(s.label)) },
                    )
                }
            }
            val needs = when (screen) {
                Screen.SETTINGS, Screen.ADVANCED -> cz.flos.core.Permission.SETTINGS
                Screen.PRODUCTS, Screen.PRODUCT_TOOLS -> cz.flos.core.Permission.PRODUCTS
                Screen.STATS, Screen.RECEIPT_STATS -> cz.flos.core.Permission.STATISTICS
                Screen.REPORT -> cz.flos.core.Permission.CLOSE_SHIFT
                Screen.DOCUMENTS -> cz.flos.core.Permission.DOCUMENTS
                else -> null
            }
            if (needs != null && settings.permissions.isNotEmpty() && !vm.can(needs)) NoPermission(tr(needs.label)) else
            when (screen) {
                Screen.SELL -> SellScreen(vm)
                Screen.TABLES -> TablesScreen(vm, onOpen = { screen = Screen.SELL })
                Screen.STATS -> StatsScreen(vm)
                Screen.RECEIPT_STATS -> ReceiptStatsScreen(vm)
                Screen.ORDERS -> OrdersScreen(vm, onEdit = { screen = Screen.SELL })
                Screen.PRODUCTS -> ProductsScreen(vm)
                Screen.HISTORY -> HistoryScreen(vm, onEditOrder = { screen = Screen.SELL })
                Screen.REPORT -> ReportScreen(vm)
                Screen.SETTINGS -> SettingsScreen(vm)
                Screen.KITCHEN -> KitchenScreen(vm)
                Screen.CUSTOMERS -> CustomersScreen(vm)
                Screen.RESERVATIONS -> ReservationsScreen(vm, onSeat = { screen = Screen.SELL })
                Screen.DOCUMENTS -> DocumentsScreen(vm, onSell = { screen = Screen.SELL })
                Screen.WAITERS -> WaitersScreen(vm)
                Screen.PRODUCT_TOOLS -> ProductToolsScreen(vm)
                Screen.ADVANCED -> AdvancedScreen(vm)
                Screen.SUPPORT -> SupportScreen(vm)
            }
        }
        }
    }
    // An order from a table waiting for the staff (Pokročilé nastavení → Objednávky z QR).
    TableOrderDialog(vm)
}
}

/** Shown instead of a screen the current role may not open. */
@Composable
private fun NoPermission(what: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(tr("Nemáte oprávnění: %s. Přepněte obsluhu na vedoucího nebo majitele (PIN).", what), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
