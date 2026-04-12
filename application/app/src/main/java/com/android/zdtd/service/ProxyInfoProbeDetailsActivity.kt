package com.android.zdtd.service

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.ui.theme.ZdtdTheme

class ProxyInfoProbeDetailsActivity : AppCompatActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    AppLanguageSupport.applyPersistedAppLocale(applicationContext)
    super.onCreate(savedInstanceState)

    val event = ProxyInfoProbeContract.fromIntent(intent)

    setContent {
      ZdtdTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
          ProxyInfoProbeDetailsScreen(
            event = event,
            onClose = { finish() },
          )
        }
      }
    }
  }
}

@Composable
private fun ProxyInfoProbeDetailsScreen(
  event: ProxyInfoProbeEvent?,
  onClose: () -> Unit,
) {
  val context = LocalContext.current
  val fallbackUnknown = stringResource(R.string.probe_detail_unknown)
  val appName = event?.packageName?.let { AppLanguageSupport.resolveAppLabel(context, it) }.orEmpty()
  val packagesValue = event?.packagesCsv
    ?.split(',')
    ?.mapNotNull { raw -> raw.trim().takeIf { it.isNotEmpty() } }
    ?.distinct()
    ?.joinToString("\n") { pkg ->
      val label = AppLanguageSupport.resolveAppLabel(context, pkg)
      if (label == pkg) pkg else "$label ($pkg)"
    }
    ?.takeIf { it.isNotBlank() }
    ?: fallbackUnknown
  val protoValue = event?.proto?.takeIf { it.isNotBlank() } ?: fallbackUnknown
  val portsValue = event?.portsHint?.takeIf { it.isNotBlank() } ?: fallbackUnknown
  val sourceValue = event?.source?.takeIf { it.isNotBlank() } ?: fallbackUnknown
  val uidValue = event?.uid?.takeIf { it >= 0 }?.toString() ?: fallbackUnknown
  val hitsValue = event?.hitCount?.takeIf { it > 0 }?.toString() ?: fallbackUnknown
  val windowValue = event?.windowSecs?.takeIf { it > 0 }?.let { stringResource(R.string.probe_detail_window_secs_value, it) } ?: fallbackUnknown
  val packageValue = event?.packageName?.takeIf { it.isNotBlank() } ?: fallbackUnknown
  val eventTypeValue = when (event?.eventType) {
    ProxyInfoProbeContract.EVENT_CONFIRMED -> stringResource(R.string.probe_detail_event_type_confirmed)
    ProxyInfoProbeContract.EVENT_SUSPICION -> stringResource(R.string.probe_detail_event_type_suspicion)
    else -> fallbackUnknown
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(20.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(
      text = stringResource(R.string.probe_detail_title),
      style = MaterialTheme.typography.headlineSmall,
      fontWeight = FontWeight.Bold,
    )
    Text(
      text = stringResource(R.string.probe_detail_status_blocked),
      style = MaterialTheme.typography.bodyLarge,
    )

    DetailCard(label = stringResource(R.string.probe_detail_event_type), value = eventTypeValue)
    DetailCard(
      label = stringResource(R.string.probe_detail_app_name),
      value = if (appName.isNotBlank()) appName else packageValue,
    )
    DetailCard(label = stringResource(R.string.probe_detail_package), value = packageValue)
    DetailCard(label = stringResource(R.string.probe_detail_packages_csv), value = packagesValue)
    DetailCard(label = stringResource(R.string.probe_detail_uid), value = uidValue)
    DetailCard(label = stringResource(R.string.probe_detail_proto), value = protoValue)
    DetailCard(label = stringResource(R.string.probe_detail_ports_hint), value = portsValue)
    DetailCard(label = stringResource(R.string.probe_detail_hit_count), value = hitsValue)
    DetailCard(label = stringResource(R.string.probe_detail_window_secs), value = windowValue)
    DetailCard(label = stringResource(R.string.probe_detail_source), value = sourceValue)

    Button(
      onClick = onClose,
      modifier = Modifier
        .fillMaxWidth()
        .padding(top = 8.dp),
    ) {
      Text(stringResource(R.string.probe_detail_close))
    }
  }
}

@Composable
private fun DetailCard(label: String, value: String) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
      )
      Text(
        text = value,
        style = MaterialTheme.typography.bodyLarge,
      )
    }
  }
}
