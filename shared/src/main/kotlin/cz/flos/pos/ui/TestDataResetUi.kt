package cz.flos.pos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cz.flos.pos.data.TestDataCounts
import kotlinx.coroutines.launch

/** The word typed to confirm, so the data is never cleared by one stray tap. */
private const val CONFIRM_WORD = "SMAZAT"

/**
 * Pokročilé nastavení → Smazat testovací data: before the till goes live, the receipts and orders made while trying
 * it out are deleted (here and in the back office). Products, menu, customers, printers, bank, EET and the pairing stay.
 */
@Composable
internal fun TestDataResetSection(vm: PosViewModel) {
    val scope = rememberCoroutineScope()
    var counts by remember { mutableStateOf<TestDataCounts?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    Section(tr("Smazat testovací data"))
    Text(tr("Před ostrým provozem smaže účtenky, objednávky (i z webu a QR), směny, platby z banky, nezaplacené a záznam storen, tady i v back office. Produkty, menu, zákazníci, tiskárny, banka, EET a propojení s back office zůstanou."),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedButton(enabled = !working, onClick = {
        status = null
        scope.launch { counts = vm.testDataCounts() }
    }) { Icon(Icons.Rounded.DeleteSweep, null); Text(tr("  Smazat testovací data…")) }
    status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

    counts?.let { c ->
        ResetDialog(c, onDismiss = { counts = null }) { documents, reservations, points, restart ->
            counts = null
            working = true
            status = tr("Mažu…")
            scope.launch {
                val error = vm.resetTestData(documents, reservations, points, restart)
                working = false
                status = error ?: tr("Hotovo. Testovací data jsou smazaná, pokladna je připravená na ostrý provoz.")
            }
        }
    }
}

@Composable
private fun ResetDialog(c: TestDataCounts, onDismiss: () -> Unit, onOk: (Boolean, Boolean, Boolean, Boolean) -> Unit) {
    var documents by remember { mutableStateOf(true) }
    var reservations by remember { mutableStateOf(true) }
    var points by remember { mutableStateOf(false) }
    var restart by remember { mutableStateOf(true) }
    var typed by remember { mutableStateOf("") }
    val blocked = c.realEet > 0
    val ok = !blocked && typed.trim().uppercase() == CONFIRM_WORD
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Smazat testovací data?")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr("Smaže se: %d účtenek, %d otevřených objednávek, %d pohybů pokladny (směny), %d plateb z banky.",
                    c.sales, c.openOrders, c.cashMovements, c.bankPayments), style = MaterialTheme.typography.bodySmall)
                if (blocked) {
                    Text(tr("Nelze smazat: %d účtenek je skutečně evidováno v EET. Evidované tržby musí zůstat.", c.realEet),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                } else {
                    Check(tr("Také doklady (nabídky, zálohové faktury, objednávky zákazníků): %d", c.documents), documents) { documents = it }
                    Check(tr("Také rezervace: %d", c.reservations), reservations) { reservations = it }
                    Check(tr("Vynulovat věrnostní body zákazníků"), points) { points = it }
                    Check(tr("Číslovat účtenky a objednávky znovu od 1"), restart) { restart = it }
                    Text(tr("Nejde vrátit zpět. Na zařízení zůstane kopie databáze (flos.db.before-test-reset). Pro jistotu si můžete nejdřív udělat zálohu."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        typed, { typed = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text(tr("Pro potvrzení napište %s", CONFIRM_WORD)) },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (ok) onOk(documents, reservations, points, restart) }, enabled = ok,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text(tr("Smazat")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Zrušit")) } },
    )
}

@Composable
private fun Check(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}
