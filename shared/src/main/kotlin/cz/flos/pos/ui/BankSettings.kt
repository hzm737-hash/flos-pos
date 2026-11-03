package cz.flos.pos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cz.flos.pos.data.AppSettings
import cz.flos.pos.data.BankSource
import cz.flos.pos.data.TipsOwner

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BankSettings(vm: PosViewModel, s: AppSettings) {
    val state by vm.bankState.collectAsState()
    Section(tr("Platby z banky a spropitné"))
    Text(
        tr("Flos čte příchozí platby a páruje je s účtenkami placenými QR kódem nebo převodem podle variabilního symbolu. Co zákazník pošle navíc, uloží jako spropitné."),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BankSource.entries.forEach { b ->
            FilterChip(selected = s.bankSource == b, onClick = { vm.updateSettings { it.copy(bankSource = b) } }, label = { Text(tr(b.label)) })
        }
    }
    when (s.bankSource) {
        BankSource.OFF -> {}
        BankSource.EMAIL -> {
            Text(
                tr("Funguje s každou českou bankou. V internetovém bankovnictví zapněte jedno z toho a posílejte to do schránky níže:"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                tr("• e-mailová upozornění na příchozí platby (platba se potvrdí do minuty), nebo\n• denní výpis e-mailem jako příloha ve formátu GPC/ABO nebo XML CAMT.053 (potvrzení druhý den ráno).\nČeská spořitelna, ČSOB, Komerční banka, Raiffeisenbank, Moneta, Air Bank, UniCredit, mBank i Fio umí obojí. Doporučujeme samostatnou schránku jen pro banku. U Gmailu a Seznamu použijte heslo pro aplikace."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(tr("Server IMAP (např. imap.gmail.com)"), s.imapHost, Modifier.weight(2f)) { v -> vm.updateSettings { it.copy(imapHost = v.trim()) } }
                Field(tr("Port"), s.imapPort.toString(), Modifier.weight(1f), KeyboardType.Number) { v ->
                    v.filter(Char::isDigit).take(5).toIntOrNull()?.let { p -> vm.updateSettings { it.copy(imapPort = p) } }
                }
            }
            Field(tr("Uživatel (e-mail)"), s.imapUser) { v -> vm.updateSettings { it.copy(imapUser = v.trim()) } }
            OutlinedTextField(
                value = s.imapPassword, onValueChange = { v -> vm.updateSettings { it.copy(imapPassword = v) } },
                label = { Text(tr("Heslo")) }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
            )
            Field(tr("Složka"), s.imapFolder) { v -> vm.updateSettings { it.copy(imapFolder = v) } }
        }
        BankSource.FIO -> {
            Text(
                tr("Ve Fio internetbankingu otevřete Nastavení → API, vytvořte token jen pro čtení a vložte ho sem."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = s.fioToken, onValueChange = { v -> vm.updateSettings { it.copy(fioToken = v.trim()) } },
                label = { Text(tr("Token Fio API")) }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    Text(
        tr("Výpis lze také kdykoli nahrát ručně v Historii → Platby z banky (soubor GPC/ABO nebo XML CAMT.053 stažený z internetového bankovnictví)."),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (s.bankSource != BankSource.OFF) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.testBank(s) }) { Text(tr("Vyzkoušet spojení")) }
            Button(onClick = { vm.checkBankNow() }) { Text(tr("Zkontrolovat teď")) }
        }
        state?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }

    Text(tr("Platby ze zahraničí (SEPA QR v EUR)"), style = MaterialTheme.typography.labelLarge)
    Text(
        tr("Zahraniční zákazník dostane QR kód evropského standardu EPC s částkou v eurech. Nejlepší je samostatný EUR účet (u Fio zdarma): peníze přijdou bez převodu měny a spropitné se spočítá přesně. Bez něj se použije hlavní účet a banka eura převede."),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Field(tr("EUR účet nebo IBAN (nepovinné)"), s.eurAccount) { v -> vm.updateSettings { it.copy(eurAccount = v) } }
    if (s.eurAccount.isNotBlank() && s.eurIban == null) Text(tr("Neplatné číslo účtu"), color = MaterialTheme.colorScheme.error)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Field(tr("Vlastní kurz Kč/EUR (prázdné = kurz ČNB)"), s.eurRateManual, Modifier.weight(1f), KeyboardType.Decimal) { v ->
            vm.updateSettings { it.copy(eurRateManual = v.filter { c -> c.isDigit() || c == ',' || c == '.' }.take(8)) }
        }
        NumberField(tr("Splatnost převodu (dny)"), s.transferDueDays, 1..30, Modifier.weight(1f)) { d -> vm.updateSettings { it.copy(transferDueDays = d) } }
    }
    if (s.bankSource == BankSource.FIO) Text(
        tr("Máte-li u Fio i EUR účet, vložte do pole tokenu oba tokeny oddělené čárkou."),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Text(tr("Komu patří spropitné"), style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TipsOwner.entries.forEach { o ->
            FilterChip(selected = s.tipsOwner == o, onClick = { vm.updateSettings { it.copy(tipsOwner = o) } }, label = { Text(tr(o.label)) })
        }
    }
    Text(tipsHint(s.tipsOwner) + " " + when (s.tipsOwner) {
        TipsOwner.STAFF -> tr("Od roku 2027 je dobrovolné spropitné zaměstnanců v gastronomii osvobozeno od daně a pojistného do 7 % měsíční tržby; přehled najdete v Uzávěrce.")
        TipsOwner.BUSINESS -> tr("Spropitné je pak příjmem provozovny pro daň z příjmů.")
    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
