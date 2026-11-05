package cz.flos.pos.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.flos.core.Cart
import cz.flos.core.Permission
import cz.flos.core.Permissions
import cz.flos.core.PaymentMethod
import cz.flos.core.Receipt
import cz.flos.core.ReceiptLayout
import cz.flos.core.StaffRole
import cz.flos.core.VatRate
import cz.flos.pos.data.AppSettings
import java.time.LocalDateTime

/** Business details, how receipts look, and what each role may do. Shown in Settings. */
@Composable
fun AdminSettings(vm: PosViewModel, s: AppSettings) {
    Section(tr("Kontakty a údaje firmy"))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Field(tr("Telefon"), s.phone, Modifier.weight(1f), KeyboardType.Phone) { v -> vm.updateSettings { it.copy(phone = v) } }
        Field(tr("E-mail"), s.email, Modifier.weight(1f), KeyboardType.Email) { v -> vm.updateSettings { it.copy(email = v.trim()) } }
    }
    Field(tr("Web"), s.web) { v -> vm.updateSettings { it.copy(web = v.trim()) } }
    Field(tr("Zápis v rejstříku (např. Zapsáno v OR vedeném KS v Praze, C 12345)"), s.registry) { v -> vm.updateSettings { it.copy(registry = v) } }
    OutlinedTextField(value = s.openingHours, onValueChange = { v -> vm.updateSettings { it.copy(openingHours = v) } },
        label = { Text(tr("Otevírací doba (např. Po-Pá 8:00-20:00)")) }, minLines = 2, modifier = Modifier.fillMaxWidth())

    Section(tr("Vzhled účtenky a dokladů"))
    SwitchRow(tr("Tisknout jméno obsluhy"), s.receiptShowCashier) { v -> vm.updateSettings { it.copy(receiptShowCashier = v) } }
    SwitchRow(tr("Sazba DPH u každé položky"), s.receiptItemVat) { v -> vm.updateSettings { it.copy(receiptItemVat = v) } }
    SwitchRow(tr("Velká celková částka"), s.receiptBigTotal) { v -> vm.updateSettings { it.copy(receiptBigTotal = v) } }
    Field(tr("Druhý řádek patičky (Instagram, Wi-Fi heslo…)"), s.receiptExtraFooter) { v -> vm.updateSettings { it.copy(receiptExtraFooter = v) } }
    Field(tr("Podmínky na nabídkách a zálohových fakturách"), s.documentTerms) { v -> vm.updateSettings { it.copy(documentTerms = v) } }
    NumberField(tr("Platnost cenové nabídky (dní)"), s.quoteValidDays, 1..365) { v -> vm.updateSettings { it.copy(quoteValidDays = v) } }
    var preview by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { preview = !preview }) { Text(if (preview) tr("Skrýt náhled") else tr("Náhled účtenky")) }
    if (preview) {
        val sample = remember(s) {
            val cart = Cart().add(1, "Cappuccino", 7500, VatRate.REDUCED).add(2, "Croissant", 4500, VatRate.REDUCED, 2.0)
            ReceiptLayout.toPlainText(ReceiptLayout(s.paperWidth.chars).layout(s.business,
                Receipt("2026-000123", LocalDateTime.now(), s.cashier.ifBlank { "Eva" }, cart, PaymentMethod.CARD)), s.paperWidth.chars)
        }
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
            Text(sample, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(12.dp).horizontalScroll(rememberScrollState()))
        }
    }

    Section(tr("Zprávy zákazníkům (WhatsApp, SMS, e-mail)"))
    Text(tr("Proměnné: {jmeno} {datum} {cas} {hoste} {stul} {cislo} {castka} {ucet} {vs} {provozovna} {telefon}"),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    listOf<Triple<String, String, (AppSettings, String) -> AppSettings>>(
        Triple(tr("Potvrzení rezervace"), s.msgReservation) { a, v -> a.copy(msgReservation = v) },
        Triple(tr("Připomínka rezervace"), s.msgReminder) { a, v -> a.copy(msgReminder = v) },
        Triple(tr("Objednávka připravena"), s.msgOrderReady) { a, v -> a.copy(msgOrderReady = v) },
        Triple(tr("Nabídka / zálohová faktura"), s.msgDocument) { a, v -> a.copy(msgDocument = v) },
    ).forEach { (label, value, set) ->
        OutlinedTextField(value = value, onValueChange = { v -> vm.updateSettings { set(it, v) } }, label = { Text(label) }, minLines = 2, modifier = Modifier.fillMaxWidth())
    }

    Section(tr("Role a oprávnění"))
    Text(tr("Majitel smí vždy vše. Zaškrtnuté = role smí. Role se berou z back office (PINy), jinak ze seznamu níže. Bez nastavení smí každý vše, jako dosud."),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(tr("Teď u pokladny: %s (%s)", s.cashier.ifBlank { "—" }, tr(vm.currentRole().label)), fontWeight = FontWeight.SemiBold)
    OutlinedTextField(value = s.staffRoles, onValueChange = { v -> vm.updateSettings { it.copy(staffRoles = v) } },
        label = { Text(tr("Role obsluhy bez back office (Eva: pokladní, Petr: vedoucí)")) }, minLines = 2, modifier = Modifier.fillMaxWidth())
    val perms = Permissions.parse(s.permissions)
    val roles = listOf(StaffRole.MANAGER, StaffRole.CASHIER)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("", Modifier.weight(1f))
        roles.forEach { r -> Text(tr(r.label), Modifier.width(84.dp), style = MaterialTheme.typography.labelMedium) }
    }
    Permission.entries.forEach { p ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr(p.label), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            roles.forEach { r ->
                Checkbox(checked = perms.allowed(r, p), modifier = Modifier.width(84.dp),
                    onCheckedChange = { allow -> vm.updateSettings { it.copy(permissions = Permissions.parse(it.permissions).with(r, p, allow).format()) } })
            }
        }
    }
}
