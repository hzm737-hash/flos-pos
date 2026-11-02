package cz.flos.pos.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cz.flos.core.CzechBankAccount
import cz.flos.core.PaperWidth
import cz.flos.core.PrinterCharset
import cz.flos.pos.print.PairedPrinter
import cz.flos.pos.print.PrinterType

@Composable
fun SettingsScreen(vm: PosViewModel) {
    val s by vm.settings.collectAsState()
    var devices by remember { mutableStateOf<List<PairedPrinter>?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) devices = vm.printer.bluetooth.pairedDevices()
    }
    fun loadDevices() {
        if (vm.printer.bluetooth.hasPermission()) devices = vm.printer.bluetooth.pairedDevices()
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Section("Jazyk / Language")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Language.entries.forEach { l ->
                    FilterChip(selected = s.language == l, onClick = { vm.updateSettings { it.copy(language = l) } }, label = { Text(l.label) })
                }
            }

            Section(tr("Provozovna"))
            Field(tr("Název provozovny"), s.businessName) { v -> vm.updateSettings { it.copy(businessName = v) } }
            Field(tr("Adresa – ulice"), s.address1) { v -> vm.updateSettings { it.copy(address1 = v) } }
            Field(tr("Adresa – město a PSČ"), s.address2) { v -> vm.updateSettings { it.copy(address2 = v) } }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(tr("IČO"), s.ico, Modifier.weight(1f), KeyboardType.Number) { v -> vm.updateSettings { it.copy(ico = v) } }
                Field(tr("DIČ"), s.dic, Modifier.weight(1f)) { v -> vm.updateSettings { it.copy(dic = v) } }
            }
            SwitchRow(tr("Plátce DPH"), s.vatPayer) { v -> vm.updateSettings { it.copy(vatPayer = v) } }
            Field(tr("Patička účtenky"), s.footer) { v -> vm.updateSettings { it.copy(footer = v) } }
            Field(tr("Jméno obsluhy"), s.cashier) { v -> vm.updateSettings { it.copy(cashier = v) } }

            Section(tr("QR platba"))
            val iban = s.iban
            OutlinedTextField(
                value = s.bankAccount,
                onValueChange = { v -> vm.updateSettings { it.copy(bankAccount = v) } },
                label = { Text(tr("Číslo účtu (např. 19-2000145399/0800) nebo IBAN")) },
                singleLine = true,
                isError = s.bankAccount.isNotBlank() && iban == null,
                supportingText = {
                    Text(
                        when {
                            s.bankAccount.isBlank() -> tr("Bez čísla účtu nelze tisknout QR platbu.")
                            iban == null -> tr("Neplatné číslo účtu.")
                            else -> tr("IBAN: ") + CzechBankAccount.formatIban(iban)
                        }
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Section(tr("Tiskárna"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrinterType.entries.forEach { t ->
                    FilterChip(selected = s.printerType == t, onClick = { vm.updateSettings { it.copy(printerType = t) } }, label = { Text(tr(t.label)) })
                }
            }
            if (s.printerType == PrinterType.BLUETOOTH) {
                Text(
                    if (s.printerAddress.isBlank()) tr("Není vybrána žádná tiskárna.") else tr("Vybraná tiskárna: %s (%s)", s.printerName, s.printerAddress),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    tr("Tiskárnu nejdřív spárujte v nastavení Bluetooth v Androidu, potom ji zde vyberte."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { loadDevices() }) { Icon(Icons.Filled.Bluetooth, null); Text(tr("  Vybrat spárovanou tiskárnu")) }
                devices?.let { list ->
                    if (list.isEmpty()) Text(tr("Žádná spárovaná zařízení. Zapněte Bluetooth a spárujte tiskárnu."))
                    list.forEach { d ->
                        FilterChip(
                            selected = d.address == s.printerAddress,
                            onClick = { vm.updateSettings { it.copy(printerAddress = d.address, printerName = d.name) } },
                            label = { Text("${d.name}  ${d.address}") },
                        )
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field(tr("IP adresa tiskárny (např. 192.168.1.50)"), s.printerHost, Modifier.weight(2f), KeyboardType.Uri) { v ->
                        vm.updateSettings { it.copy(printerHost = v.trim()) }
                    }
                    NumberField(tr("Port"), s.printerPort, 1..65535, Modifier.weight(1f)) { p -> vm.updateSettings { it.copy(printerPort = p) } }
                }
                Text(
                    tr("Tiskárna musí být ve stejné Wi-Fi síti jako telefon nebo tablet. IP adresu vytiskne většina tiskáren na samotestu (podržte tlačítko FEED při zapnutí). Port bývá 9100."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(tr("Šířka papíru"), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PaperWidth.entries.forEach { p ->
                    FilterChip(selected = s.paperWidth == p, onClick = { vm.updateSettings { it.copy(paperWidth = p) } }, label = { Text("${p.mm} mm") })
                }
            }
            Text(tr("Znaková sada (diakritika)"), style = MaterialTheme.typography.labelLarge)
            Column {
                PrinterCharset.entries.forEach { c ->
                    FilterChip(selected = s.charset == c, onClick = { vm.updateSettings { it.copy(charset = c) } }, label = { Text(tr(c.label)) })
                }
            }
            SwitchRow(tr("Tisknout účtenku automaticky po platbě"), s.autoPrint) { v -> vm.updateSettings { it.copy(autoPrint = v) } }
            Button(onClick = { vm.testPrint() }, enabled = vm.printer.isConfigured(s)) {
                Icon(Icons.Filled.Print, null); Text(tr("  Zkušební tisk"))
            }

            EetSettings(vm, s)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(tr("Flos 0.1.0"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
}

@Composable
internal fun Field(
    label: String,
    value: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    keyboard: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard), modifier = modifier,
    )
}

@Composable
internal fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Spacer(Modifier.padding(4.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
