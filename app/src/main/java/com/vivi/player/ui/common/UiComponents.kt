package com.vivi.player.ui.common

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vivi.core.data.model.PlaybackProgress
import com.vivi.core.data.model.VideoMedia
import java.text.DateFormat
import java.util.Date
import java.util.Locale

fun formatDuration(micros: Long): String {
    if (micros <= 0) return "--:--"
    val totalSeconds = micros / 1_000_000L
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.ROOT, "%.2f GB", bytes / 1073741824.0)
    bytes >= 1L shl 20 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0)
    bytes >= 1L shl 10 -> String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

fun formatDate(millis: Long): String =
    if (millis <= 0) "" else DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))

fun formatBitrate(bitsPerSecond: Long): String =
    if (bitsPerSecond <= 0) "未知" else String.format(Locale.ROOT, "%.1f Mbps", bitsPerSecond / 1_000_000.0)

/**
 * 缩略图按卡片实际像素宽度请求，避免 1080p 源解码大图把列表滚动手感拖垮。
 */
@Composable
fun rememberThumbnail(
    media: VideoMedia,
    ffmpegFallback: (suspend () -> Bitmap?)? = null,
): Bitmap? {
    val context = LocalContext.current
    val widthPx = with(LocalDensity.current) { (LocalConfiguration.current.screenWidthDp / 3).dp.roundToPx() }
    var bitmap by remember(media.key, widthPx) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(media.key, widthPx) {
        bitmap = ThumbnailLoader.load(context, media, widthPx, ffmpegFallback)
    }
    return bitmap
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoCard(
    media: VideoMedia,
    progress: PlaybackProgress?,
    isFavorite: Boolean,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    val thumbnail = rememberThumbnail(media)
    Column(modifier = modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16 / 9f)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .background(Color(0xFF0B0B0F)),
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail.asImageBitmap(),
                    contentDescription = media.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // 系统解码不了的容器（AVI/FLV/WMV）拿不到缩略图，用图标+容器名占位而不是光秃秃的文字
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Filled.Videocam,
                            contentDescription = null,
                            tint = Color(0xFF3A3A42),
                            modifier = Modifier.size(30.dp),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = media.extension.uppercase().takeIf { it.isNotEmpty() } ?: "VIDEO",
                            color = Color(0xFF6B6B75),
                            fontSize = 11.sp,
                        )
                    }
                }
            }
            Text(
                text = formatDuration(media.durationMicros),
                color = androidx.compose.ui.graphics.Color.White,
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xB3000000))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
            if (isFavorite) {
                Image(
                    imageVector = Icons.Filled.Favorite,
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(Color(0xFFFF5B5B)),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .size(16.dp),
                )
            }
            if (isSelected) {
                Image(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(Color(0xFF4EA1FF)),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(20.dp),
                )
            }
            if (progress != null && progress.fraction > 0f) {
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp),
                )
            }
        }
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(
                text = media.displayName,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (media.resolutionLabel.isNotEmpty()) {
                    BadgeText(media.resolutionLabel)
                }
                BadgeText(media.extension.uppercase().ifBlank { "?" })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoRow(
    media: VideoMedia,
    progress: PlaybackProgress?,
    isFavorite: Boolean,
    isSelected: Boolean,
    subtitle: String? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    val thumbnail = rememberThumbnail(media)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 96.dp, height = 58.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF0B0B0F))
                .border(
                    width = if (isSelected) 2.dp else 0.dp,
                    color = Color(0xFF4EA1FF),
                    shape = RoundedCornerShape(8.dp),
                ),
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (progress != null && progress.fraction > 0f) {
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = media.displayName,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = subtitle ?: "${formatDuration(media.durationMicros)}  •  ${formatSize(media.sizeBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isFavorite) {
            Image(
                imageVector = Icons.Filled.Favorite,
                contentDescription = null,
                colorFilter = ColorFilter.tint(Color(0xFFFF5B5B)),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
fun BadgeText(text: String) {
    Text(
        text = text,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}
