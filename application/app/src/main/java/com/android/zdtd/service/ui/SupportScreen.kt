package com.android.zdtd.service.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun SupportScreen() {
  val context = LocalContext.current

  val links = remember {
    listOf(
      SupportLink(
        title = "GitHub",
        subtitle = "Source code, issues, docs",
        url = "https://github.com/GAME-OVER-op/ZDT-D",
        icon = Icons.Filled.Code,
      ),
      SupportLink(
        title = "Releases",
        subtitle = "Latest builds and changelogs",
        url = "https://github.com/GAME-OVER-op/ZDT-D/releases",
        icon = Icons.Filled.NewReleases,
      ),
      SupportLink(
        title = "Telegram",
        subtitle = "News & community: @module_ggover",
        url = "https://t.me/module_ggover",
        icon = Icons.Filled.Send,
      ),
      SupportLink(
        title = "Support the author",
        subtitle = "YooMoney donation link",
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
            text = "Support",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
          )
          Spacer(Modifier.height(6.dp))
          Text(
            text = "Links to the project, releases, community and author support.",
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
  val title: String,
  val subtitle: String,
  val url: String,
  val icon: ImageVector,
)

@Composable
private fun SupportLinkCard(link: SupportLink, onOpen: () -> Unit) {
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
          text = link.title,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
          text = link.subtitle,
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
        Text("Open")
      }
    }
  }
}
