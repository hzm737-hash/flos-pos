package cz.flos.pos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cz.flos.pos.LocalPlatform
import cz.flos.pos.data.AppSettings
import java.text.DateFormat

/** ČSOB CEB Business Connector: contract number and the till's own client certificate. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CsobSettings(vm: PosViewModel, s: AppSettings) {
    val info by vm.csobCert.collectAsState()
    val platform = LocalPlatform.current
    var saved by remember { mutableStateOf<Boolean?>(null) }
    val saveFile = platform.rememberFileSaver("application/pkcs10") { ok -> saved = ok }
    val pickCert = platform.rememberFilePicker { bytes -> vm.csobImportCertificate(bytes) }
    var p12 by remember { mutableStateOf<ByteArray?>(null) }
    val pickP12 = platform.rememberFilePicker { bytes -> p12 = bytes }

    Text(
        tr("Flos čte avíza o příchozích platbách přímo z ČSOB CEB. Banka je vytvoří hned po připsání platby, takže se účtenka potvrdí do 1–2 minut. Bez smlouvy navíc a bez poplatku; stačí certifikát z CEB."),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Field(tr("Číslo smlouvy CEB"), s.csobContract, keyboard = KeyboardType.Number) { v ->
        vm.updateSettings { it.copy(csobContract = v.filter(Char::isDigit).take(18)) }
    }

    Text(tr("Certifikát pro Business Connector"), style = MaterialTheme.typography.labelLarge)
    Text(
        when {
            info.name != null -> tr("Nahrán: %s, platí do %s", info.name, info.validUntil?.let { DateFormat.getDateInstance().format(it) } ?: "?") +
                if (info.pending) "\n" + tr("Čeká se na nový certifikát k poslední žádosti.") else ""
            info.pending -> tr("Žádost je vytvořená. Nahrajte ji v CEB a stažený certifikát vložte sem (krok 3).")
            else -> tr("Zatím není nahrán.")
        },
        style = MaterialTheme.typography.bodyMedium,
        color = if (info.name == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
    Text(
        tr("1. Vytvořte žádost a uložte soubor .csr (klíč zůstane jen v této pokladně).\n2. V CEB otevřete Nastavení → Business Connector → Požádat o certifikát, vložte soubor .csr a stáhněte vydaný certifikát.\n3. Tady stiskněte Nahrát certifikát z CEB a vyberte stažený soubor.\n4. V CEB → Nastavení → Business Connector povolte pro svůj účet stahování avíz (formát MT942) a případně výpisů.\nKaždá pokladna potřebuje vlastní certifikát a potvrzuje platby za své účtenky."),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = { vm.csobNewRequest { pem -> saveFile("flos-csob.csr", pem.toByteArray(Charsets.US_ASCII)) } }) {
            Text(tr("1. Vytvořit žádost (.csr)"))
        }
        Button(onClick = pickCert) { Text(tr("3. Nahrát certifikát z CEB")) }
        TextButton(onClick = pickP12) { Text(tr("Nahrát .p12 / .pfx")) }
        if (info.name != null || info.pending) TextButton(onClick = { vm.csobRemoveCertificate() }) { Text(tr("Odebrat certifikát")) }
    }
    saved?.let {
        Text(
            if (it) tr("Žádost uložena. Přeneste soubor flos-csob.csr do počítače, kde se přihlašujete do CEB.") else tr("Soubor nebyl uložen"),
            style = MaterialTheme.typography.bodySmall,
        )
    }

    p12?.let { bytes ->
        var password by remember(bytes) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { p12 = null },
            title = { Text(tr("Heslo k certifikátu")) },
            text = {
                OutlinedTextField(
                    value = password, onValueChange = { password = it }, label = { Text(tr("Heslo")) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            },
            confirmButton = { Button(onClick = { vm.csobImportCertificate(bytes, password); p12 = null }) { Text(tr("Nahrát")) } },
            dismissButton = { TextButton(onClick = { p12 = null }) { Text(tr("Zrušit")) } },
        )
    }
}
