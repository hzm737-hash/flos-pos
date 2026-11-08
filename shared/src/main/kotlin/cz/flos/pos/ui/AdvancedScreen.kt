package cz.flos.pos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cz.flos.core.VatRate
import cz.flos.pos.LocalPlatform
import cz.flos.pos.data.AdvFeature
import cz.flos.pos.data.AppSettings
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Pokročilé nastavení: the optional features of Flos v2, each with its own switch (all off at first),
 * their settings, and backup / restore of the whole till.
 */
@Composable
fun AdvancedScreen(vm: PosViewModel) {
    val s by vm.settings.collectAsState()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tr("Volitelné funkce. Každou zapnete nebo vypnete zvlášť; vypnutá funkce pokladnu nijak nemění."),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            BackupSection(vm)

            Section(tr("Funkce"))
            AdvFeature.entries.forEach { f ->
                FeatureCard(vm, s, f)
            }
        }
    }
}

@Composable
private fun FeatureCard(vm: PosViewModel, s: AppSettings, f: AdvFeature) {
    val on = s.on(f)
    Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr(f.label), fontWeight = FontWeight.Bold)
                    Text(tr(f.hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.padding(4.dp))
                Switch(checked = on, onCheckedChange = { v ->
                    vm.updateSettings { it.copy(advanced = (if (v) it.advancedSet + f.name else it.advancedSet - f.name).sorted().joinToString(",")) }
                })
            }
            if (on) when (f) {
                AdvFeature.ADDONS, AdvFeature.NOTES -> if (f == AdvFeature.ADDONS) AddonsSettings(vm, s, showNotes = false)
                    else OutlinedTextField(
                        s.quickNotes, { v -> vm.updateSettings { it.copy(quickNotes = v) } },
                        label = { Text(tr("Rychlé poznámky (oddělené čárkou)")) }, minLines = 2, modifier = Modifier.fillMaxWidth(),
                    )
                AdvFeature.SERVICE_CHARGE -> {
                    NumberField(tr("Servisní poplatek v %"), s.serviceChargePercent, 0..100, Modifier.fillMaxWidth()) { p ->
                        vm.updateSettings { it.copy(serviceChargePercent = p) }
                    }
                    if (s.vatPayer) {
                        Text(tr("Sazba DPH poplatku"), style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            VatRate.entries.forEach { r ->
                                FilterChip(selected = s.serviceChargeVat == r.percent, onClick = { vm.updateSettings { it.copy(serviceChargeVat = r.percent) } },
                                    label = { Text(r.label) })
                            }
                        }
                    }
                }
                AdvFeature.EUR_CASH -> OutlinedTextField(
                    s.eurCashRate, { v -> vm.updateSettings { it.copy(eurCashRate = v) } },
                    label = { Text(tr("Vlastní kurz pro hotovost (Kč za 1 EUR)")) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text(tr("Prázdné = kurz ČNB (nebo kurz z Platby z banky).")) },
                )
                AdvFeature.WAITER_TABLES -> OutlinedTextField(
                    s.waiterTables, { v -> vm.updateSettings { it.copy(waiterTables = v) } },
                    label = { Text(tr("Stoly obsluhy")) }, minLines = 2, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Eva: 1, 2, 3\nPetr: 4, 5, Terasa 1") },
                    supportingText = { Text(tr("Každý řádek jeden číšník: jméno, dvojtečka a jeho stoly. Jméno jako v Obsluze.")) },
                )
                AdvFeature.STORNO_REASON -> AuditLog(vm)
                else -> {}
            }
        }
    }
}

@Composable
private fun AuditLog(vm: PosViewModel) {
    val log by vm.auditLog.collectAsState()
    var all by remember { mutableStateOf(false) }
    val fmt = remember { DateTimeFormatter.ofPattern("d.M.yyyy H:mm") }
    Text(tr("Záznam (%d)", log.size), style = MaterialTheme.typography.labelLarge)
    if (log.isEmpty()) Text(tr("Zatím nic."), style = MaterialTheme.typography.bodySmall)
    (if (all) log else log.take(20)).forEach { n ->
        Column(Modifier.padding(vertical = 4.dp)) {
            Text(Instant.ofEpochMilli(n.createdAt).atZone(ZoneId.systemDefault()).format(fmt) + (n.author.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(n.text, style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider()
    }
    if (!all && log.size > 20) TextButton(onClick = { all = true }) { Text(tr("Zobrazit vše")) }
}

@Composable
private fun BackupSection(vm: PosViewModel) {
    val platform = LocalPlatform.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var restoreReady by remember { mutableStateOf(false) }
    val save = platform.rememberFileSaver("application/zip") { ok -> status = if (ok) tr("Záloha uložena.") else tr("Záloha nebyla uložena.") }
    val pick = platform.rememberFilePicker { bytes ->
        scope.launch {
            val error = vm.stageRestore(bytes)
            if (error == null) restoreReady = true else status = error
        }
    }

    Section(tr("Záloha a obnova"))
    Text(tr("Záloha obsahuje vše: účtenky, produkty, zákazníky, nastavení včetně bankovního účtu a certifikát EET. Uložte ji mimo telefon (Disk Google, e-mail, počítač)."),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            status = tr("Připravuji zálohu…")
            scope.launch {
                runCatching { vm.createBackup() }
                    .onSuccess { save("flos-zaloha-" + LocalDate.now() + ".zip", it) }
                    .onFailure { status = tr("Zálohu se nepodařilo vytvořit: %s", it.message ?: "") }
            }
        }) { Icon(Icons.Rounded.Backup, null); Text(tr("  Zálohovat")) }
        OutlinedButton(onClick = pick) { Icon(Icons.Rounded.Restore, null); Text(tr("  Obnovit ze zálohy")) }
    }
    status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

    if (restoreReady) AlertDialog(
        onDismissRequest = {},
        title = { Text(tr("Záloha je připravena")) },
        text = { Text(tr("Obnoví se při dalším spuštění: data na tomto zařízení se nahradí zálohou (dosavadní databáze se uloží vedle jako flos.db.before-restore). Zavřete Flos a otevřete ho znovu.")) },
        confirmButton = { Button(onClick = { kotlin.system.exitProcess(0) }) { Text(tr("Zavřít Flos")) } },
        dismissButton = { TextButton(onClick = { restoreReady = false }) { Text(tr("Později")) } },
    )
}
