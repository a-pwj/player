package com.vivi.player.ui.permission

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.vivi.core.data.scan.MediaPermissions

/**
 * 首屏授权。
 *
 * 两级权限分开申请：媒体权限是「能看到大部分视频」，所有文件访问才是「能看到全部视频」
 * （包括未被媒体库索引的目录、SD 卡深层目录、隐藏目录）。商店政策不允许我们强制索取后者，
 * 所以把它做成带说明的推荐项，用户拒绝也能用。
 */
@Composable
fun PermissionScreen(
    requestMedia: () -> Unit,
    requestAllFiles: () -> Unit,
    granted: () -> Unit,
) {
    val context = LocalContext.current
    var mediaGranted by remember { mutableStateOf(MediaPermissions.hasLibraryAccess(context)) }
    var allFilesGranted by remember { mutableStateOf(MediaPermissions.hasAllFilesAccess()) }

    LifecycleResumeEffect(Lifecycle.State.RESUMED) {
        mediaGranted = MediaPermissions.hasLibraryAccess(context)
        allFilesGranted = MediaPermissions.hasAllFilesAccess()
        onPauseOrDispose { }
    }
    LaunchedEffect(mediaGranted, allFilesGranted) {
        if (mediaGranted || allFilesGranted) granted()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Top),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.PlayCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.size(12.dp))
            Column {
                Text("ViVi 本地播放器", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text("扫描全部视频 · 字幕 · 隐私空间", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        PermissionCard(
            title = "读取视频（必需）",
            body = "Android 13 以上只需「图片和视频」权限，媒体库里的视频就能被列出并播放。",
            granted = mediaGranted,
            actionLabel = "授予视频权限",
            onAction = requestMedia,
        )

        PermissionCard(
            title = "所有文件访问（推荐）",
            body = "只有拿到这个权限，才能扫到 MediaStore 没索引的视频：未命名的深层目录、.hidden 目录、" +
                "SD 卡/OTG 上的其它分区，以及 AVI/RMVB/WMV 等非标准容器。不授予也能用，但列表会少一批文件。",
            granted = allFilesGranted,
            actionLabel = "打开系统设置授权",
            onAction = requestAllFiles,
        )

        OutlinedButton(
            onClick = granted,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("先只用媒体库") }

        Text(
            text = "隐私空间的文件只保存在本机应用私有目录，PIN 加盐哈希存储，不会上传。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PermissionCard(
    title: String,
    body: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (granted) Icons.Filled.VerifiedUser else Icons.Filled.FolderOpen,
                    contentDescription = null,
                    tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(title, fontWeight = FontWeight.Medium)
                if (granted) {
                    Spacer(Modifier.weight(1f))
                    Text("已授权", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(body, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!granted) {
                Spacer(Modifier.height(12.dp))
                Button(onClick = onAction, modifier = Modifier.fillMaxWidth()) { Text(actionLabel) }
            }
        }
    }
}
