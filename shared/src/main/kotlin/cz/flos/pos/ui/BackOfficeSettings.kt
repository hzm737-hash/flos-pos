package cz.flos.pos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cz.flos.pos.LocalPlatform
import cz.flos.pos.data.AppSettings
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Pairing the till with the Flos back office, synchronisation status, and the loyalty and customer display options. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackOfficeSettings(vm: PosViewModel, s: AppSettings) {
    val state by vm.syncState.collectAsState()
    Section(tr("Back office"))
    if (!s.paired) {
        Text(
            tr("V back office otevřete Pokladny → Přidat pokladnu a opište sem adresu a osmimístný kód. Pokladna si pak sama stáhne produkty, obsluhu a údaje pro EET a bude posílat účtenky. Bez připojení funguje dál a co nestihla odeslat, pošle později."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        var url by remember { mutableStateOf(s.boUrl.ifBlank { "https://flos.gnocco.cz" }) }
        var code by remember { mutableStateOf("") }
        OutlinedTextField(
            value = url, onValueChange = { url = it.trim() }, singleLine = true, label = { Text(tr("Adresa back office")) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = code, onValueChange = { code = it.filter(Char::isLetterOrDigit).take(8).uppercase() }, singleLine = true,
            label = { Text(tr("Párovací kód")) }, modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { vm.pairBackOffice(url, code) }, enabled = url.isNotBlank() && code.length == 8) { Text(tr("Spárovat")) }
    } else {
        Text(tr("Spárováno jako %s s %s", s.boDeviceName.ifBlank { "—" }, s.boUrl), style = MaterialTheme.typography.bodyMedium)
        val last = if (s.boLastSync > 0) DateTimeFormatter.ofPattern("d. M. HH:mm").format(Instant.ofEpochMilli(s.boLastSync).atZone(ZoneId.systemDefault())) else "—"
        Text(tr("Poslední synchronizace: %s. Pokladna se synchronizuje sama každých 5 minut.", last),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        state?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        var confirm by remember { mutableStateOf(false) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.syncNow() }) { Text(tr("Synchronizovat teď")) }
            OutlinedButton(onClick = { confirm = true }) { Text(tr("Odpojit")) }
        }
        Text(
            tr("Kategorie a produkty můžete upravovat v pokladně i v back office, platí vždy poslední změna. Produkty, které vznikly v pokladně ještě před spárováním, pošlete do back office jednou tímto tlačítkem."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = { vm.uploadLocalCatalog() }) { Text(tr("Nahrát produkty z pokladny do back office")) }
        if (s.on(cz.flos.pos.data.AdvFeature.APP_UPDATES)) AppUpdateRow(vm)
        if (confirm) AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(tr("Odpojit od back office?")) },
            text = { Text(tr("Produkty a účtenky zůstanou v pokladně. Pro nové spárování budete potřebovat nový kód.")) },
            confirmButton = { Button(onClick = { vm.unpairBackOffice(); confirm = false }) { Text(tr("Odpojit")) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(tr("Zrušit")) } },
        )
    }

    Section(tr("Zákazníci a věrnostní body"))
    NumberField(tr("Kč za 1 bod (0 = body vypnuté)"), s.loyaltyCrownsPerPoint, 0..100_000, Modifier.fillMaxWidth()) { v ->
        vm.updateSettings { it.copy(loyaltyCrownsPerPoint = v) }
    }

    Section(tr("Zákaznický displej"))
    SwitchRow(tr("Ukazovat zákazníkovi košík a QR platbu na druhém displeji"), s.customerDisplay) { v -> vm.updateSettings { it.copy(customerDisplay = v) } }
    Text(
        tr("Na Windows se otevře druhé okno, které přetáhnete na monitor otočený k zákazníkovi. Na Androidu se použije druhý displej (např. pokladna se zákaznickým displejem nebo monitor přes HDMI)."),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A newer app build from the back office: what is new and a button that downloads and installs it over this app. */
@Composable
private fun AppUpdateRow(vm: PosViewModel) {
    val update by vm.appUpdate.collectAsState()
    val platform = LocalPlatform.current
    Section(tr("Aktualizace aplikace"))
    if (platform.updatePlatform == null) {
        Text(tr("Tato pokladna dostává aktualizace jinak (Google Play nebo instalační program pro Windows)."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Text(tr("Nainstalovaná verze: %s", platform.appVersionName.ifBlank { "—" }), style = MaterialTheme.typography.bodySmall)
    val u = update
    if (u == null) {
        Text(tr("Pokladna se dívá na novou verzi každých 30 minut."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { vm.checkAppUpdateNow() }) { Text(tr("Zkontrolovat aktualizaci")) }
    } else {
        Text(tr("Nová verze %s (%d MB)", u.versionName, ((u.size + 524_287) / 1_048_576).toInt()), style = MaterialTheme.typography.bodyMedium)
        if (u.notes.isNotBlank()) Text(u.notes, style = MaterialTheme.typography.bodySmall)
        Text(tr("Po stažení klepněte na soubor a zvolte Instalovat. Aplikace se aktualizuje a všechna data zůstanou. Telefon se může zeptat, zda smí prohlížeč instalovat aplikace."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { vm.installAppUpdate() }) { Text(tr("Stáhnout a nainstalovat")) }
    }
}

/**
 * Switching who serves: with the staff from the back office the PIN is checked on the till (works offline),
 * and the same PIN clocks in or out. Without a back office the name is just typed.
 */
@Composable
internal fun PinDialog(vm: PosViewModel, onDismiss: () -> Unit) {
    val s by vm.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(s.cashier) }
    var wrong by remember { mutableStateOf(false) }
    val withStaff = s.boStaff.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Obsluha")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (withStaff) {
                    Text(tr("Zadejte svůj PIN z back office."), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = pin, onValueChange = { pin = it.filter(Char::isDigit).take(8); wrong = false }, singleLine = true,
                        label = { Text(tr("PIN")) }, isError = wrong,
                        supportingText = { if (wrong) Text(tr("Neplatný PIN")) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (s.paired) TextButton(onClick = { vm.clockInOut(pin); onDismiss() }, enabled = pin.length >= 4) {
                        Text(tr("Zapsat příchod / odchod"))
                    }
                } else {
                    Text(tr("Po spárování s back office se obsluha přihlašuje PINem."), style = MaterialTheme.typography.bodySmall)
                    Field(tr("Jméno obsluhy"), name) { name = it }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = if (withStaff) pin.length >= 4 else name.isNotBlank(),
                onClick = {
                    if (withStaff) scope.launch { if (vm.loginWithPin(pin)) onDismiss() else wrong = true }
                    else { vm.updateSettings { it.copy(cashier = name.trim()) }; onDismiss() }
                },
            ) { Text(tr("Přihlásit")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Zrušit")) } },
    )
}
