package com.android.zdtd.service.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.R
import org.json.JSONObject

internal data class SubscriptionServerLinkUi(
  val id: String,
  val subscriptionId: String,
  val nodeId: String,
  val missing: Boolean,
)

internal fun parseSubscriptionServerLinkUi(obj: JSONObject?): SubscriptionServerLinkUi? = obj?.let {
  SubscriptionServerLinkUi(
    id = it.optString("id"),
    subscriptionId = it.optString("subscription_id"),
    nodeId = it.optString("node_id"),
    missing = it.optBoolean("missing"),
  )
}?.takeIf { it.id.isNotBlank() }

@Composable
internal fun SubscriptionServerLinkCard(
  link: SubscriptionServerLinkUi,
  onDetach: () -> Unit,
) {
  val accent = if (link.missing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.medium,
    color = if (link.missing) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.42f)
    else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.34f),
    border = BorderStroke(1.dp, accent.copy(alpha = 0.22f)),
  ) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
      Text(
        stringResource(if (link.missing) R.string.subscription_missing_title else R.string.subscription_link_active),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = accent,
      )
      if (link.missing) {
        Text(stringResource(R.string.subscription_local_copy_saved), style = MaterialTheme.typography.bodySmall)
      }
      TextButton(onClick = onDetach) { Text(stringResource(R.string.subscription_detach)) }
    }
  }
}
