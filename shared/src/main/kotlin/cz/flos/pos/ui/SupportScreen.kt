package cz.flos.pos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cz.flos.pos.DonationOption

/**
 * "Podpořit Flos": voluntary donations. Flos stays free and a donation unlocks nothing.
 * On Android the amounts are Google Play in-app products, so Play handles the payment.
 */
@Composable
fun SupportScreen(vm: PosViewModel) {
    val platform = vm.platform
    var options by remember { mutableStateOf<List<DonationOption>?>(null) }
    var thanked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { options = runCatching { platform.donationOptions() }.getOrDefault(emptyList()) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(Icons.Rounded.Favorite, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
        Text(tr("Podpořit Flos"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            tr("Flos je zdarma a všechny funkce zůstanou zdarma. Pokud vám pomáhá, můžete jeho další vývoj podpořit dobrovolným příspěvkem. Příspěvek nic neodemyká a nic se nemění."),
            textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 520.dp),
        )
        if (thanked) Text(tr("Děkujeme za podporu!"), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        val list = options
        when {
            list == null -> Text(tr("Načítání…"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            list.isEmpty() -> Text(tr("Příspěvky jsou dostupné ve verzi Flos z Google Play."),
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            else -> {
                list.forEach { option ->
                    Button(
                        onClick = {
                            platform.donate(option) { ok ->
                                if (ok) thanked = true else vm.notify(tr("Platba nebyla dokončena"))
                            }
                        },
                        modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(),
                    ) { Text("${option.title} · ${option.price}") }
                }
                Text(tr("Platbu zpracovává Google Play. Flos nevidí údaje o vaší kartě."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
    }
}
