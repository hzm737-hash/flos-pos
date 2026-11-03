package cz.flos.pos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.flos.core.eet.EetFormat
import cz.flos.pos.data.EetStatus
import cz.flos.pos.eet.EetService
import java.util.concurrent.TimeUnit

/**
 * Shows sales that are not yet confirmed by EET, how long the oldest has waited against the
 * 48-hour legal deadline, and rejected sales that need attention.
 */
@Composable
fun EetQueueBanner(vm: PosViewModel) {
    val open by vm.eetOpen.collectAsState()
    val settings by vm.settings.collectAsState()
    if (!settings.eetEnabled || open.isEmpty()) return

    val pending = open.filter { it.status == EetStatus.PENDING }
    val rejected = open.filter { it.status == EetStatus.REJECTED }
    val oldestHours = pending.minOfOrNull { it.saleTime }?.let { TimeUnit.MILLISECONDS.toHours(System.currentTimeMillis() - it) } ?: 0
    val urgent = rejected.isNotEmpty() || oldestHours >= EetService.WARN_AFTER_HOURS

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (urgent) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        ),
        modifier = Modifier.fillMaxWidth().padding(12.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (urgent) Icons.Filled.Warning else Icons.Filled.CloudUpload, null)
                Spacer(Modifier.width(8.dp))
                Text(tr("EET"), fontWeight = FontWeight.Bold)
            }
            if (pending.isNotEmpty()) {
                Text(
                    tr("%d tržeb čeká na odeslání. Nejstarší čeká %d h (zákonná lhůta %d h).", pending.size, oldestHours, EetFormat.OFFLINE_DEADLINE_HOURS) +
                        (pending.first().lastError?.let { "\n" + tr("Poslední chyba: %s", it) } ?: ""),
                )
            }
            rejected.take(5).forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Odmítnuto %s: %s", r.poradCis, r.lastError), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { vm.requeueEet(r) }) { Text(tr("Odeslat znovu")) }
                }
            }
            if (pending.isNotEmpty()) TextButton(onClick = { vm.retryEetNow() }) { Text(tr("Odeslat nyní")) }
        }
    }
}
