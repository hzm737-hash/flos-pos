package cz.flos.pos.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cz.flos.core.eet.EetEnvironment
import cz.flos.pos.data.AppSettings
import java.text.DateFormat

/** EET 2.0 section of the settings screen. */
@Composable
fun EetSettings(vm: PosViewModel, s: AppSettings) {
    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> pickedUri = uri }
    val cert = remember(s.eetCertPassword, pickedUri, vm.eet.hasCertificate()) { vm.eet.certificate() }

    Section("EET 2.0 – evidence tržeb")
    SwitchRow("Evidovat tržby v EET", s.eetEnabled) { v -> vm.updateSettings { it.copy(eetEnabled = v) } }
    if (!s.eetEnabled) {
        Text(
            "Zapněte, pokud máte povinnost evidovat tržby (od 1. 1. 2027). Budete potřebovat pokladní certifikát z portálu " +
                "MOJE daně a označení evidenční jednotky přidělené v DIS+.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Text("Prostředí", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EetEnvironment.entries.forEach { e ->
            FilterChip(selected = s.eetEnvironment == e, onClick = { vm.updateSettings { it.copy(eetEnvironment = e) } }, label = { Text(e.label) })
        }
    }
    SwitchRow("Ověřovací režim (zprávy se kontrolují, ale neevidují)", s.eetVerification) { v ->
        vm.updateSettings { it.copy(eetVerification = v) }
    }
    Field("EIČ / DIČ poplatníka (např. CZ12345678)", s.eetEic.ifBlank { s.dic }) { v -> vm.updateSettings { it.copy(eetEic = v.trim()) } }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Field("Označení evidenční jednotky", s.eetIdJednotky, Modifier.weight(1f), KeyboardType.Number) { v ->
            vm.updateSettings { it.copy(eetIdJednotky = v.filter(Char::isDigit).take(9)) }
        }
        Field("Označení pokladny", s.eetIdPokl, Modifier.weight(1f)) { v -> vm.updateSettings { it.copy(eetIdPokl = v.take(20)) } }
    }
    NumberField("Mezní doba odezvy (sekundy, nejméně 2)", s.eetTimeoutMs / 1000, 2..30, Modifier.fillMaxWidth()) { sec ->
        vm.updateSettings { it.copy(eetTimeoutMs = sec * 1000) }
    }

    Text("Pokladní certifikát", style = MaterialTheme.typography.labelLarge)
    Text(
        when {
            cert != null -> "Nahrán: ${cert.eic}, platí do ${DateFormat.getDateInstance().format(cert.validUntil)}" +
                if (!cert.isValidAt()) " – PLATNOST VYPRŠELA" else ""
            vm.eet.hasCertificate() -> "Certifikát je nahrán, ale heslo nesouhlasí. Nahrajte ho znovu."
            else -> "Zatím není nahrán. Vygenerujte ho v portálu MOJE daně (Správa pokladních certifikátů EET) a uložte soubor .p12 do zařízení."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = if (cert == null || !cert.isValidAt()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
    if (cert != null && cert.eic != null && cert.eic != s.effectiveEic) {
        Text("Pozor: EIČ v certifikátu (${cert.eic}) se liší od zadaného EIČ (${s.effectiveEic}).", color = MaterialTheme.colorScheme.error)
    }
    OutlinedButton(onClick = { picker.launch(arrayOf("application/x-pkcs12", "application/octet-stream", "*/*")) }) {
        Icon(Icons.Filled.Key, null); Text("  Nahrát certifikát (.p12)")
    }
    Button(onClick = { vm.testEet() }, enabled = cert != null) {
        Icon(Icons.Filled.Verified, null); Text("  Ověřit spojení s EET")
    }
    Text(
        "Při výpadku internetu se účtenka vytiskne bez POK a tržba se odešle automaticky, jakmile bude spojení. " +
            "Zákon vyžaduje odeslání nejpozději do 48 hodin od tržby; aplikace upozorní na tržby, které čekají déle než 24 hodin.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    pickedUri?.let { uri ->
        var password by remember(uri) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pickedUri = null },
            title = { Text("Heslo k certifikátu") },
            text = {
                OutlinedTextField(
                    value = password, onValueChange = { password = it }, label = { Text("Heslo") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            },
            confirmButton = { Button(onClick = { vm.importCertificate(uri, password); pickedUri = null }) { Text("Nahrát") } },
            dismissButton = { TextButton(onClick = { pickedUri = null }) { Text("Zrušit") } },
        )
    }
}

/** A number field that lets the user clear and retype; it only saves values within [range]. */
@Composable
fun NumberField(label: String, value: Int, range: IntRange, modifier: Modifier = Modifier, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t.filter(Char::isDigit).take(6)
            text.toIntOrNull()?.takeIf { it in range }?.let(onChange)
        },
        label = { Text(label) },
        singleLine = true,
        isError = text.toIntOrNull()?.let { it !in range } ?: true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

