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
import cz.flos.pos.LocalPlatform
import cz.flos.pos.PrinterDevice
import cz.flos.pos.print.PrinterType

@Composable
fun SettingsScreen(vm: PosViewModel) {
    val s by vm.settings.collectAsState()
    val platform = LocalPlatform.current
    var devices by remember(s.printerType) { mutableStateOf<List<PrinterDevice>?>(null) }
    val askPermission = platform.rememberPrinterPermission { devices = platform.localPrinters(s.printerType) }
    // A Bluetooth test print first asks for any missing permission, then prints.
    val askThenTest = platform.rememberPrinterPermission { vm.testPrint() }
    fun loadDevices() {
        if (platform.canUsePrinters()) devices = platform.localPrinters(s.printerType) else askPermission()
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

            Section(tr("Stoly"))
            Field(tr("Názvy stolů oddělené čárkou (prázdné = bez mapy stolů)"), s.tables) { v -> vm.updateSettings { it.copy(tables = v) } }
            Text(tr("Stolů: %d", s.tableList.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            PricingSettings(vm, s)

            AdminSettings(vm, s)

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
                platform.printerTypes.forEach { t ->
                    FilterChip(selected = s.printerType == t, onClick = { vm.updateSettings { it.copy(printerType = t) } }, label = { Text(platform.printerTypeLabel(t)) })
                }
            }
            if (s.printerType != PrinterType.NETWORK) {
                Text(
                    if (s.printerAddress.isBlank()) tr("Není vybrána žádná tiskárna.") else tr("Vybraná tiskárna: %s (%s)", s.printerName, s.printerAddress),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    platform.printerHelp(s.printerType),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { loadDevices() }) { Icon(Icons.Filled.Print, null); Text(tr("  Vybrat tiskárnu")) }
                devices?.let { list ->
                    if (list.isEmpty()) Text(tr("Nenalezena žádná tiskárna."))
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
            SwitchRow(tr("Otevírat pokladní zásuvku při platbě hotově"), s.openDrawer) { v -> vm.updateSettings { it.copy(openDrawer = v) } }
            SwitchRow(tr("Rychlý QR kód (kreslí ho tiskárna)"), s.printerNativeQr) { v -> vm.updateSettings { it.copy(printerNativeQr = v) } }
            Text(tr("QR kód se tiskne mnohem rychleji. Zkuste zkušební tisk: když QR kód chybí, tiskárna to neumí a nechte vypnuté."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { if (s.printerType == PrinterType.BLUETOOTH && !platform.canUsePrinters()) askThenTest() else vm.testPrint() }, enabled = vm.printer.isConfigured(s)) {
                    Icon(Icons.Filled.Print, null); Text(tr("  Zkušební tisk"))
                }
                OutlinedButton(onClick = { vm.openDrawer() }, enabled = vm.printer.isConfigured(s)) { Text(tr("Otevřít zásuvku")) }
            }

            BackOfficeSettings(vm, s)
            EetSettings(vm, s)
            BankSettings(vm, s)
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
