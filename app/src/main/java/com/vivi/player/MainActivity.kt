package com.vivi.player

import android.Manifest
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.vivi.core.data.model.MediaKey
import com.vivi.core.data.repository.AppSettings
import com.vivi.core.data.scan.MediaPermissions
import com.vivi.core.player.PlayerStatus
import com.vivi.player.di.AppContainer
import com.vivi.player.ui.library.LibraryScreen
import com.vivi.player.ui.permission.PermissionScreen
import com.vivi.player.ui.playlist.PlaylistScreen
import com.vivi.player.ui.player.PlayerScreen
import com.vivi.player.ui.settings.SettingsScreen
import com.vivi.player.ui.theme.ViivTheme
import com.vivi.player.ui.vault.VaultScreen
import com.vivi.player.ui.vault.VaultSetupScreen

object Routes {
    const val PERMISSION = "permission"
    const val LIBRARY = "library"
    const val FOLDERS = "folders"
    const val PLAYER = "player/{key}?playlist={playlist}"
    const val PLAYER_DIRECT = "player_direct/{uri}"
    const val PLAYLIST = "playlist/{id}"
    const val VAULT = "vault"
    const val VAULT_SETUP = "vault_setup"
    const val SETTINGS = "settings"

    fun player(key: MediaKey, playlistId: String? = null): String {
        val base = "player/${Uri.encode(key.value)}"
        return if (playlistId == null) base else "$base?playlist=${Uri.encode(playlistId)}"
    }

    fun playlist(id: String) = "playlist/${Uri.encode(id)}"

    fun playerDirect(uri: Uri) = "player_direct/${Uri.encode(uri.toString())}"

    fun folder(path: String) = "folder/${Uri.encode(path)}"

    fun decode(raw: String?): String = Uri.decode(raw.orEmpty())
}

class MainActivity : ComponentActivity() {

    private val container: AppContainer by lazy { (application as ViViApplication).container }

    private val deleteLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { /* 用户拒绝删除时静默返回，列表刷新会体现真实状态 */ }

    private val mediaPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        requestNotificationPermissionIfNeeded()
        container.refreshScanScope()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* 拒绝也能正常播放，只是没有通知栏与锁屏控件 */ }

    /**
     * Android 13 起通知是运行时权限。少了它，Media3 的前台服务通知会被系统直接吞掉，
     * 表现就是「后台还在出声，但通知栏和锁屏控件完全不出现」，用户也无法回来恢复播放。
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private val settingsPageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { container.refreshScanScope() }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        var ready = false
        splash.setKeepOnScreenCondition { !ready }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        container.pendingExternalUri.value = extractVideoUri(intent)

        setContent {
            val nav = rememberNavController()
            AppRoot(
                container = container,
                nav = nav,
                onReady = { ready = true },
                requestMediaPermissions = {
                    mediaPermissionLauncher.launch(MediaPermissions.requiredPermissions(this))
                },
                requestAllFiles = {
                    val target = MediaPermissions.allFilesSettingsIntent(packageName)
                        ?: MediaPermissions.appSettingsIntent(packageName)
                    settingsPageLauncher.launch(target)
                },
                confirmDelete = { pending -> deleteLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build()) },
            )
        }
        // 已经授过媒体权限的老用户不会再走授权页，这里补一次通知权限申请
        if (MediaPermissions.hasLibraryAccess(this) || MediaPermissions.hasAllFilesAccess()) {
            requestNotificationPermissionIfNeeded()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        extractVideoUri(intent)?.let { container.pendingExternalUri.value = it }
    }

    @Suppress("DEPRECATION")
    private fun extractVideoUri(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }

        else -> null
    }

    /** 退到后台自动进画中画，与主流播放器一致。 */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < 26 || !container.pipEnabled) return
        val state = com.vivi.player.di.PlaybackHolder.current()?.state?.value ?: return
        if (state.status != PlayerStatus.PLAYING) return
        runCatching { enterPictureInPictureMode(pipParamsFor(state.videoWidth, state.videoHeight)) }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        container.pipActive.value = isInPictureInPictureMode
    }

    companion object {
        /** 系统只接受 1:2.39 ~ 2.39:1 之间的比例。 */
        fun pipParamsFor(width: Int, height: Int): PictureInPictureParams {
            val builder = PictureInPictureParams.Builder()
            if (width > 0 && height > 0) {
                val ratio = (width.toFloat() / height).coerceIn(1f / 2.39f, 2.39f)
                builder.setAspectRatio(Rational(1000, (1000 / ratio).toInt()))
            }
            // Android 12+ 默认自动隐藏并显示系统提供的关闭/播放控制，不需要额外配置
            return builder.build()
        }
    }
}

private fun NavHostController.go(route: String) = navigate(route) { launchSingleTop = true }

@Composable
private fun AppRoot(
    container: AppContainer,
    nav: NavHostController,
    onReady: () -> Unit,
    requestMediaPermissions: () -> Unit,
    requestAllFiles: () -> Unit,
    confirmDelete: (PendingIntent) -> Unit,
) {
    val settings by container.settings.settings.collectAsState(initial = AppSettings())
    val context = LocalContext.current
    val permissionGranted = MediaPermissions.hasLibraryAccess(context) || MediaPermissions.hasAllFilesAccess()

    // 外部「用其他应用打开」进来时直接进播放器
    val externalUri by container.pendingExternalUri.collectAsState()
    LaunchedEffect(externalUri) {
        externalUri?.let {
            nav.go(Routes.playerDirect(it))
            container.pendingExternalUri.value = null
        }
    }

    ViivTheme(mode = settings.themeMode, dynamicColor = settings.dynamicColor) {
        NavHost(navController = nav, startDestination = if (permissionGranted) Routes.LIBRARY else Routes.PERMISSION) {
            composable(Routes.PERMISSION) {
                PermissionScreen(
                    requestMedia = requestMediaPermissions,
                    requestAllFiles = requestAllFiles,
                    granted = { nav.navigate(Routes.LIBRARY) { popUpTo(Routes.PERMISSION) { inclusive = true } } },
                )
            }
            composable(Routes.LIBRARY) {
                LibraryScreen(
                    onPlay = { key -> nav.go(Routes.player(key)) },
                    onPlayFromPlaylist = { key, playlistId -> nav.go(Routes.player(key, playlistId)) },
                    onOpenPlaylist = { id -> nav.go(Routes.playlist(id)) },
                    onOpenFolder = { path -> nav.go(Routes.folder(path)) },
                    onOpenVault = { nav.go(Routes.VAULT) },
                    onOpenSettings = { nav.go(Routes.SETTINGS) },
                    onOpenFoldersTab = { nav.go(Routes.FOLDERS) },
                    confirmDelete = confirmDelete,
                )
            }
            composable("folder/{path}") { entry ->
                LibraryScreen(
                    folderPath = Routes.decode(entry.arguments?.getString("path")),
                    onPlay = { key -> nav.go(Routes.player(key)) },
                    onPlayFromPlaylist = { key, playlistId -> nav.go(Routes.player(key, playlistId)) },
                    onOpenPlaylist = { id -> nav.go(Routes.playlist(id)) },
                    onOpenFolder = { nav.popBackStack() },
                    onOpenVault = { nav.go(Routes.VAULT) },
                    onOpenSettings = { nav.go(Routes.SETTINGS) },
                    onOpenFoldersTab = { nav.popBackStack() },
                    confirmDelete = confirmDelete,
                )
            }
            composable(Routes.FOLDERS) {
                LibraryScreen(
                    showFoldersOnly = true,
                    onPlay = { key -> nav.go(Routes.player(key)) },
                    onPlayFromPlaylist = { key, playlistId -> nav.go(Routes.player(key, playlistId)) },
                    onOpenPlaylist = { id -> nav.go(Routes.playlist(id)) },
                    onOpenFolder = { path -> nav.go(Routes.folder(path)) },
                    onOpenVault = { nav.go(Routes.VAULT) },
                    onOpenSettings = { nav.go(Routes.SETTINGS) },
                    onOpenFoldersTab = { nav.popBackStack() },
                    confirmDelete = confirmDelete,
                )
            }
            composable(Routes.PLAYER) { entry ->
                PlayerScreen(
                    mediaKey = MediaKey(Routes.decode(entry.arguments?.getString("key"))),
                    playlistId = entry.arguments?.getString("playlist")?.takeIf { it.isNotEmpty() }?.let { Routes.decode(it) },
                    onBack = { nav.popBackStack() },
                    onOpenSettings = { nav.go(Routes.SETTINGS) },
                    confirmDelete = confirmDelete,
                )
            }
            composable(Routes.PLAYLIST) { entry ->
                PlaylistScreen(
                    playlistId = Routes.decode(entry.arguments?.getString("id")),
                    onBack = { nav.popBackStack() },
                    onPlay = { key, playlistId -> nav.go(Routes.player(key, playlistId)) },
                )
            }
            composable(Routes.PLAYER_DIRECT) { entry ->
                PlayerScreen(
                    directUri = Routes.decode(entry.arguments?.getString("uri")).takeIf { it.isNotEmpty() }?.let(Uri::parse),
                    onBack = { nav.popBackStack() },
                    onOpenSettings = { nav.go(Routes.SETTINGS) },
                    confirmDelete = confirmDelete,
                )
            }
            composable(Routes.VAULT) {
                VaultScreen(
                    onPlay = { key -> nav.go(Routes.player(key)) },
                    onSetup = { nav.go(Routes.VAULT_SETUP) },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Routes.VAULT_SETUP) {
                VaultSetupScreen(
                    onDone = { nav.popBackStack() },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = { nav.popBackStack() },
                    requestMediaPermissions = requestMediaPermissions,
                    requestAllFiles = requestAllFiles,
                )
            }
        }
    }
    LaunchedEffect(Unit) { onReady() }
}
