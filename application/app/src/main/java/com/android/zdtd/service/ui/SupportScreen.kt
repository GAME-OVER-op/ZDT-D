package com.android.zdtd.service.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.R

@Composable
fun SupportScreen() {
  val context = LocalContext.current

  val links = remember {
    listOf(
      SupportLink(
        titleRaw = "GitHub",
        titleRes = null,
        subtitleRes = R.string.support_link_github_subtitle,
        url = "https://github.com/GAME-OVER-op/ZDT-D",
        icon = Icons.Filled.Code,
      ),
      SupportLink(
        titleRaw = null,
        titleRes = R.string.support_link_releases_title,
        subtitleRes = R.string.support_link_releases_subtitle,
        url = "https://github.com/GAME-OVER-op/ZDT-D/releases",
        icon = Icons.Filled.NewReleases,
      ),
      SupportLink(
        titleRaw = "Telegram",
        titleRes = null,
        subtitleRes = R.string.support_link_telegram_subtitle,
        url = "https://t.me/module_ggover",
        icon = Icons.Filled.Send,
      ),
      SupportLink(
        titleRaw = null,
        titleRes = R.string.support_link_support_author_title,
        subtitleRes = R.string.support_link_support_author_subtitle,
        url = "https://yoomoney.ru/to/4100118340691506/100",
        icon = Icons.Filled.Favorite,
      ),
    )
  }

  LazyColumn(
    modifier = Modifier
      .fillMaxSize()
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
      ) {
        Column(Modifier.padding(16.dp)) {
          Text(
            text = stringResource(R.string.support_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
          )
          Spacer(Modifier.height(6.dp))
          Text(
            text = stringResource(R.string.support_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
          )
        }
      }
    }

    items(links) { link ->
      SupportLinkCard(
        link = link,
        onOpen = {
          runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link.url)).apply {
              addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
          }
        },
      )
    }
  }
}

private data class SupportLink(
  val titleRaw: String?,
  val titleRes: Int?,
  val subtitleRes: Int,
  val url: String,
  val icon: ImageVector,
)

@Composable
private fun SupportLinkCard(link: SupportLink, onOpen: () -> Unit) {
  val title = link.titleRaw ?: stringResource(link.titleRes ?: R.string.support_title)

  ElevatedCard(
    modifier = Modifier
      .fillMaxWidth()
      .clickable { onOpen() },
    shape = RoundedCornerShape(18.dp),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(14.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        modifier = Modifier
          .size(44.dp)
          .clip(CircleShape)
          .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = link.icon,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
        )
      }

      Spacer(Modifier.width(12.dp))

      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = title,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
          text = stringResource(link.subtitleRes),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Text(
          text = link.url,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }

      TextButton(onClick = onOpen) {
        Text(stringResource(R.string.support_open))
      }
    }
  }
}
