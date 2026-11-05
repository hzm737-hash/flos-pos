package cz.flos.pos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.flos.core.bank.CzechBanks
import cz.flos.pos.data.AppSettings
import cz.flos.pos.data.BankSource

/** Settings for service types, automatic discounts (happy hour), the cashier's discount limit and scale labels. */
@Composable
fun PricingSettings(vm: PosViewModel, s: AppSettings) {
    Section(tr("Typy služby, slevy a váha"))
    Hint(tr("Typy služby se vybírají na prodeji (např. na místě, s sebou, rozvoz). Každý řádek jeden typ: název, za dvojtečkou příplatek nebo sleva v % a ceník."))
    OutlinedTextField(
        s.serviceTypes, { v -> vm.updateSettings { it.copy(serviceTypes = v) } },
        label = { Text(tr("Typy služby")) }, minLines = 2, modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Na místě\nS sebou\nRozvoz: +10 %, ceník Rozvoz") },
        supportingText = { Text(tr("Typů: %d", s.serviceTypeList.size)) },
    )
    Hint(tr("Automatické slevy se použijí samy, když položka splní podmínky. Každý řádek jedno pravidlo: název, sleva v %, čas, dny, období, kategorie, produkt nebo typ služby."))
    OutlinedTextField(
        s.discountRules, { v -> vm.updateSettings { it.copy(discountRules = v) } },
        label = { Text(tr("Automatické slevy (happy hour)")) }, minLines = 2, modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Happy hour: 20 %, 15:00-17:00, Po-Pá, kategorie Nápoje\nAkce: 10 %, produkt Pivo, 1.12.2026-31.12.2026") },
        supportingText = {
            val rules = s.discountRuleList
            Text(if (rules.isEmpty()) tr("Žádná pravidla") else rules.joinToString(" · ") { "${it.name} ${it.percent} %" })
        },
    )
    NumberField(tr("Nejvyšší ruční sleva pokladníka (%; 100 = bez omezení)"), s.maxCashierDiscount, 0..100, Modifier.fillMaxWidth()) { v ->
        vm.updateSettings { it.copy(maxCashierDiscount = v) }
    }
    Hint(tr("Vedoucí a majitel (podle back office) omezeni nejsou. Sleva zákazníka z věrnostního programu se počítá zvlášť."))

    Text(tr("Etikety z váhy"), style = MaterialTheme.typography.labelLarge)
    Hint(tr("Váha tiskne EAN-13: prefix, kód produktu (PLU) a hmotnost v gramech nebo cenu. Kód produktu zadejte u produktu jako čárový kód, cenu jako cenu za 1 kg."))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Field(tr("Prefix hmotnosti (např. 28)"), s.scaleWeightPrefixes, Modifier.weight(1f)) { v -> vm.updateSettings { it.copy(scaleWeightPrefixes = v.trim()) } }
        Field(tr("Prefix ceny (např. 29)"), s.scalePricePrefixes, Modifier.weight(1f)) { v -> vm.updateSettings { it.copy(scalePricePrefixes = v.trim()) } }
    }
    NumberField(tr("Počet číslic kódu produktu (4–6)"), s.scalePluDigits, 4..6, Modifier.fillMaxWidth()) { v -> vm.updateSettings { it.copy(scalePluDigits = v) } }
}

/** Shows which bank the account belongs to and how to connect it, with the recommended way switched on by one tap. */
@Composable
fun BankGuide(vm: PosViewModel, s: AppSettings) {
    val bank = CzechBanks.forAccount(s.bankAccount) ?: return
    val recommended = if (bank.directApi) BankSource.FIO else BankSource.EMAIL
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr("Banka účtu: %s (%s)", bank.name, bank.code), fontWeight = FontWeight.Bold)
            Text(if (bank.directApi) tr("Doporučeno: přímé napojení přes API banky (token jen pro čtení).")
                else tr("Doporučeno: e-mailová upozornění od banky. Funguje bez smlouvy a poplatků."))
            Text(tr("Upozornění: %s", bank.emailHint), style = MaterialTheme.typography.bodySmall)
            Text(tr("Výpisy: %s", bank.statementHint), style = MaterialTheme.typography.bodySmall)
            if (s.bankSource != recommended) OutlinedButton(onClick = { vm.updateSettings { it.copy(bankSource = recommended) } }) {
                Text(tr("Použít doporučený způsob"))
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
