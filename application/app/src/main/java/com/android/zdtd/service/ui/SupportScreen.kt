package com.android.zdtd.service.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.ripple
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.BuildConfig
import com.android.zdtd.service.R

@Composable
fun SupportScreen(
  topContentPadding: Dp = 0.dp,
  bottomContentPadding: Dp = 0.dp,
) {
  val context = LocalContext.current
  val isCompactWidth = rememberIsCompactWidth()
  val isNarrowWidth = rememberIsNarrowWidth()
  val showSupportAuthor = rememberIsRussianAppLanguage()

  val links = remember(showSupportAuthor) {
    buildList {
      add(
        SupportLink(
          titleRaw = "GitHub",
          titleRes = null,
          subtitleRes = R.string.support_link_github_subtitle,
          url = "https://github.com/GAME-OVER-op/ZDT-D",
          icon = Icons.Filled.Code,
        )
      )
      add(
        SupportLink(
          titleRaw = null,
          titleRes = R.string.support_link_releases_title,
          subtitleRes = R.string.support_link_releases_subtitle,
          url = "https://github.com/GAME-OVER-op/ZDT-D/releases",
          icon = Icons.Filled.NewReleases,
        )
      )
      add(
        SupportLink(
          titleRaw = "Telegram",
          titleRes = null,
          subtitleRes = R.string.support_link_telegram_subtitle,
          url = "https://t.me/module_ggover",
          icon = Icons.Filled.Send,
        )
      )
      if (showSupportAuthor) {
        add(
          SupportLink(
            titleRaw = null,
            titleRes = R.string.support_link_support_author_title,
            subtitleRes = R.string.support_link_support_author_subtitle,
            url = "https://yoomoney.ru/to/4100118340691506/100",
            icon = Icons.Filled.Favorite,
          )
        )
      }
    }
  }

  val screenPadding = rememberAdaptiveScreenPadding()
  var heroAppeared by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) { heroAppeared = true }

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(
      start = screenPadding,
      top = topContentPadding + screenPadding,
      end = screenPadding,
      bottom = bottomContentPadding + screenPadding,
    ),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    if (BuildConfig.IS_ROOT_BUILD) {
      item(key = "diagnostics") {
        SupportDiagnosticsCard()
      }
    }

    item(key = "intro") {
      val introAppear by animateFloatAsState(
        targetValue = if (heroAppeared) 1f else 0f,
        animationSpec = tween(420),
        label = "supportIntroAppear",
      )
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(18.dp))
          .graphicsLayer {
            alpha = introAppear
            translationY = (1f - introAppear) * 18f
          },
      ) {
        Box(
          modifier = Modifier
            .matchParentSize()
            .background(
              Brush.linearGradient(
                listOf(
                  MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                  MaterialTheme.colorScheme.surfaceContainerLow,
                ),
              ),
            ),
        )
        ElevatedCard(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(18.dp),
          colors = CardDefaults.elevatedCardColors(containerColor = Color.Transparent),
          elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
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
    }

    itemsIndexed(links, key = { _, link -> link.url }, contentType = { _, _ -> "support_link" }) { index, link ->
      StaggeredReveal(index = index + 1) {
        SupportLinkCard(
          compact = isCompactWidth,
          narrow = isNarrowWidth,
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
}

private data class SupportLink(
  val titleRaw: String?,
  val titleRes: Int?,
  val subtitleRes: Int,
  val url: String,
  val icon: ImageVector,
)

@Composable
private fun SupportLinkCard(compact: Boolean, narrow: Boolean, link: SupportLink, onOpen: () -> Unit) {
  val title = link.titleRaw ?: stringResource(link.titleRes ?: R.string.support_title)
  val interaction = remember { MutableInteractionSource() }
  val pressed by interaction.collectIsPressedAsState()
  val scale by animateFloatAsState(
    targetValue = if (pressed) 0.985f else 1f,
    animationSpec = spring(dampingRatio = 0.7f, stiffness = 360f),
    label = "supportLinkPress",
  )

  ElevatedCard(
    modifier = Modifier
      .fillMaxWidth()
      .graphicsLayer { scaleX = scale; scaleY = scale }
      .clickable(interactionSource = interaction, indication = ripple(), onClick = onOpen),
    shape = RoundedCornerShape(18.dp),
  ) {
    if (compact || narrow) {
      SupportLinkVerticalLayout(
        title = title,
        link = link,
        onOpen = onOpen,
        spacing = if (compact) 12.dp else 10.dp,
      )
    } else {
      SupportLinkHorizontalLayout(title = title, link = link, onOpen = onOpen)
    }
  }
}

@Composable
private fun SupportLinkVerticalLayout(title: String, link: SupportLink, onOpen: () -> Unit, spacing: Dp) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(14.dp),
    verticalArrangement = Arrangement.spacedBy(spacing),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(
        modifier = Modifier
          .size(44.dp)
          .clip(CircleShape)
          .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
      ) {
        Icon(imageVector = link.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
      }

      Spacer(Modifier.width(12.dp))

      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = title,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
          text = stringResource(link.subtitleRes),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }

    Text(
      text = link.url,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )

    TextButton(onClick = onOpen, modifier = Modifier.align(Alignment.End)) {
      Text(stringResource(R.string.support_open))
    }
  }
}

@Composable
private fun SupportLinkHorizontalLayout(title: String, link: SupportLink, onOpen: () -> Unit) {
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
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      Spacer(Modifier.height(2.dp))
      Text(
        text = stringResource(link.subtitleRes),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      Spacer(Modifier.height(6.dp))
      Text(
        text = link.url,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }

    TextButton(onClick = onOpen) {
      Text(stringResource(R.string.support_open))
    }
  }
}
