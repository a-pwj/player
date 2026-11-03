package com.vivi.player.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vivi.core.data.model.ThemeMode
import com.vivi.core.data.scan.MediaPermissions
import com.vivi.core.ffmpeg.FFmpegEnvironment
import com.vivi.player.di.appContainer
import com.vivi.player.ui.common.formatSize
import com.vivi.player.ui.theme.ViivTheme
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    requestMediaPermissions: () -> Unit,
    requestAllFiles: () -> Unit,
) {
    val container = appContainer()
    val settings by container.settings.settings.collectAsState(initial = com.vivi.core.data.repository.AppSettings())
    val scope = rememberCoroutineScope()
    val vaultConfigured by container.vaultRepository.isConfigured.collectAsState(initial = false)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
            )
        },
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Section("权限与扫描") {
                RowItem(
                    title = "视频读取权限",
                    subtitle = if (MediaPermissions.hasLibraryAccess(container.context)) {
                        "已授权，可读取媒体库中的视频"
                    } else {
                        "未授权，媒体库为空"
                    },
                    action = {
                        Text(
                            text = "授予",
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable(onClick = requestMediaPermissions),
                        )
                    },
                )
                RowItem(
                    title = "所有文件访问",
                    subtitle = if (MediaPermissions.hasAllFilesAccess()) {
                        "已授权：会扫描 MediaStore 未索引的目录与全部容器格式"
                    } else {
                        "未授权：只能看到媒体库索引过的视频"
                    },
                    action = {
                        Text(
                            text = "打开系统设置",
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable(onClick = requestAllFiles),
                        )
                    },
                )
                SwitchRow(
                    title = "扫描隐藏目录",
                    subtitle = "包含以 . 开头的目录（仍跳过 Android/data 等系统目录）",
                    checked = settings.showAllFiles,
                    onChange = { scope.launch { container.settings.setShowAllFiles(it); container.refreshScanScope() } },
                )
                SwitchRow(
                    title = "扫描 Android/data 目录",
                    subtitle = "包含 Android/data、Android/obb、Android/media。注意 Android 11+ 即使授予所有文件访问，" +
                        "系统仍可能拒绝读取这些目录，届时列表里不会出现它们。",
                    checked = settings.scanAndroidData,
                    onChange = { scope.launch { container.settings.setScanAndroidData(it); container.refreshScanScope() } },
                )
                RowItem(
                    title = "最小视频时长",
                    subtitle = "过滤短视频片段：当前 ${settings.minVideoDurationSec} 秒",
                    action = {},
                )
                Slider(
                    value = settings.minVideoDurationSec.toFloat(),
                    onValueChange = { value -> scope.launch { container.settings.setMinVideoDuration(value.toInt()) } },
                    valueRange = 0f..300f,
                    steps = 11,
                )
            }

            Section("播放") {
                SwitchRow(
                    title = "硬件解码",
                    subtitle = "关闭后系统会用软件解码，功耗更高但能绕开个别硬解 bug",
                    checked = settings.hardwareDecode,
                    onChange = { scope.launch { container.settings.setHardwareDecode(it) } },
                )
                SwitchRow(
                    title = "自动混流不兼容容器",
                    subtitle = "AVI / FLV / WMV / RMVB 等用 FFmpeg copy 重封装成 MKV 后播放（只改容器，不损画质）",
                    checked = settings.autoRemux,
                    onChange = { scope.launch { container.settings.setAutoRemux(it) } },
                )
                SwitchRow(
                    title = "允许视频转码",
                    subtitle = "遇到系统解不了的编码时重编视频，耗时较长且占内部存储",
                    checked = settings.allowVideoTranscode,
                    onChange = { scope.launch { container.settings.setAllowVideoTranscode(it) } },
                )
                SwitchRow(
                    title = "记住播放进度",
                    checked = settings.resumePlayback,
                    onChange = { scope.launch { container.settings.setResumePlayback(it) } },
                )
                SwitchRow(
                    title = "后台播放 / 锁屏控制",
                    subtitle = "开屏熄灭后继续出声，并显示通知栏与锁屏控件",
                    checked = settings.backgroundPlayback,
                    onChange = { scope.launch { container.settings.setBackgroundPlayback(it) } },
                )
                SwitchRow(title = "画中画", checked = settings.pipEnabled, onChange = { scope.launch { container.settings.setPipEnabled(it) } })
                SwitchRow(title = "屏幕常亮", checked = settings.keepScreenOn, onChange = { scope.launch { container.settings.setKeepScreenOn(it) } })
                RowItem(title = "单击步进", subtitle = "${settings.seekStepSec} 秒", action = {})
                Slider(
                    value = settings.seekStepSec.toFloat(),
                    onValueChange = { v -> scope.launch { container.settings.setSeekStep(v.toInt()) } },
                    valueRange = 5f..60f,
                )
                RowItem(title = "双击步进", subtitle = "${settings.doubleTapSeekSec} 秒", action = {})
                Slider(
                    value = settings.doubleTapSeekSec.toFloat(),
                    onValueChange = { v -> scope.launch { container.settings.setDoubleTapSeek(v.toInt()) } },
                    valueRange = 5f..120f,
                )
                RowItem(
                    title = "长按临时倍速",
                    subtitle = "播放界面长按屏幕即以该速度播放，松手恢复；当前 %.2fx".format(settings.longPressSpeed),
                    action = {},
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1.25f, 1.5f, 2f, 3f).forEach { speed ->
                        FilterChip(
                            selected = settings.longPressSpeed == speed,
                            onClick = { scope.launch { container.settings.setLongPressSpeed(speed) } },
                            label = { Text("%.2fx".format(speed), fontSize = 12.sp) },
                        )
                    }
                }
                Slider(
                    value = settings.longPressSpeed,
                    onValueChange = { v -> scope.launch { container.settings.setLongPressSpeed(v) } },
                    valueRange = 1f..4f,
                )
            }

            Section("字幕") {
                RowItem(title = "首选语言", subtitle = settings.subtitlePreferredLanguage, action = {})
                OutlinedTextField(
                    value = settings.subtitlePreferredLanguage,
                    onValueChange = { v -> scope.launch { container.settings.setSubtitleLanguage(v) } },
                    label = { Text("语言代码，如 zh / en / jpn") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                RowItem(title = "字号", subtitle = "%.2f".format(settings.subtitleScale), action = {})
                Slider(
                    value = settings.subtitleScale,
                    onValueChange = { v -> scope.launch { container.settings.setSubtitleScale(v) } },
                    valueRange = 0.5f..3f,
                )
                RowItem(title = "延迟", subtitle = "${settings.subtitleDelayMicros / 1_000_000f} 秒", action = {})
                Slider(
                    value = settings.subtitleDelayMicros / 1_000_000f,
                    onValueChange = { v -> scope.launch { container.settings.setSubtitleDelay((v * 1_000_000).toLong()) } },
                    valueRange = -30f..30f,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.subtitleOutline,
                        onClick = { scope.launch { container.settings.setSubtitleOutline(!settings.subtitleOutline) } },
                        label = { Text("描边") },
                    )
                    FilterChip(
                        selected = settings.subtitleShadow,
                        onClick = { scope.launch { container.settings.setSubtitleShadow(!settings.subtitleShadow) } },
                        label = { Text("阴影") },
                    )
                    FilterChip(
                        selected = settings.subtitleBackground,
                        onClick = { scope.launch { container.settings.setSubtitleBackground(!settings.subtitleBackground) } },
                        label = { Text("底色") },
                    )
                }
            }

            Section("手势") {
                SwitchRow(title = "横向拖动画面调节进度", checked = settings.gestureSeek, onChange = { scope.launch { container.settings.setGestureSeek(it) } })
            }

            Section("外观") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.themeMode == mode,
                            onClick = { scope.launch { container.settings.setTheme(mode) } },
                            label = {
                                Text(
                                    when (mode) {
                                        ThemeMode.SYSTEM -> "跟随系统"
                                        ThemeMode.LIGHT -> "浅色"
                                        ThemeMode.DARK -> "深色"
                                        ThemeMode.AMOLED -> "纯黑"
                                    }
                                )
                            },
                        )
                    }
                }
                SwitchRow(title = "动态取色", subtitle = "Android 12+ 按壁纸取色", checked = settings.dynamicColor, onChange = { scope.launch { container.settings.setDynamicColor(it) } })
            }

            Section("隐私空间") {
                RowItem(
                    title = if (vaultConfigured) "已开启" else "未开启",
                    subtitle = "PIN 加盐哈希存储；支持「仅在本应用隐藏」与「移入应用私有目录」两种模式。" +
                        "入口在媒体库右上角的锁形图标。",
                    action = {},
                )
            }

            Section("缓存") {
                RowItem(
                    title = "缩略图与 FFmpeg 产物",
                    subtitle = "占用 ${formatSize(thumbnailBytes(container) + preparedBytes(container))}",
                    action = {
                        Text(
                            "清理",
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable {
                                com.vivi.player.ui.common.ThumbnailLoader.clearCache(container.context)
                                container.playbackPreparer.clearPreparedCache()
                                container.thumbnailService.clearCache()
                            },
                        )
                    },
                )
            }

            Section("关于") {
                RowItem(title = "ViVi", subtitle = "版本 0.1.0 · 本地视频播放器", action = {})
                RowItem(
                    title = "FFmpeg",
                    subtitle = FFmpegEnvironment.version(),
                    action = {},
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(vertical = 10.dp)) {
        Text(title, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun RowItem(title: String, subtitle: String, action: @Composable () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action()
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp)
            if (subtitle != null) {
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun thumbnailBytes(container: com.vivi.player.di.AppContainer): Long =
    java.io.File(container.context.filesDir, "thumbs").listFiles()?.sumOf { it.length() } ?: 0L

private fun preparedBytes(container: com.vivi.player.di.AppContainer): Long =
    java.io.File(container.context.cacheDir, "ffmpeg").listFiles()?.sumOf { it.length() } ?: 0L
