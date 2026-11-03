# ViVi 本地视频播放器

Android 本地视频播放器，播放核心用 **Media3/ExoPlayer**，容器与编解码兼容层用 **FFmpeg**（ffmpeg-kit 预编译库）。
目标是把市面上主流播放器（VLC / MX Player / nPlayer）的常用能力做齐，同时把 FFmpeg 用在它真正有价值的地方。

---

## 1. 设计分析

### 1.1 FFmpeg 到底该管哪一段

一开始最容易走偏的想法是「用 FFmpeg 做播放器」。这条路要么自己写 `libavformat` 解封装 + `libavcodec` 解码 +
`AMediaCodec`/OpenGL 渲染的整条 C/C++ 管线（需要自己用 NDK 编译 FFmpeg，几百 KB 代码 + 每次系统升级都要重做），
要么引入 ijkplayer 这类整套方案（体积大、维护停滞、和 Jetpack 生态脱节）。

主流做法其实是分工：

| 环节 | 谁来做 | 为什么 |
| --- | --- | --- |
| 解封装 + 解码 + 渲染 + 音画同步 | Media3 ExoPlayer + MediaCodec 硬解 | 系统级省电、HDR/10bit/杜比跟着设备能力走，自己实现很难追平 |
| 容器不被系统支持（AVI/FLV/WMV/RMVB/ASF） | **FFmpeg copy 重混流** | 只改封装不动码流，速度等于 IO，秒级完成 |
| 音频编码不支持（DTS/WMAPRO/WMA/…） | **FFmpeg 只重编音频** | 比转视频快一个数量级，画质零损失 |
| 视频编码不支持（RV/VP6/ProRes/旧 MPEG-4 ASP 等） | **FFmpeg 转码**（需用户确认） | 分钟级代价，必须让用户知情 |
| 内封 ASS/SSA 字幕 | **FFmpeg 导出为 .ass/.srt**，App 自渲染 | Media3 不解析 ASS，MKV 里最常见的就是它 |
| 媒体信息 / 章节 / 多音轨元数据 | **ffprobe JSON** | Media3 不暴露 MKV 章节，也不给 codec 细节 |
| 拖动进度条的缩略图预览 | **FFmpeg 抽帧 + tile 雪碧图** | 一条命令产出整表，比逐帧起进程可行 |

这样 FFmpeg 承担的是「补齐系统能力」，而不是重造播放器：画质不受损（copy 路径）、功耗由硬解保证、
出问题时也有明确的降级顺序。

### 1.2 模块划分

```
ViVi/
├── app/            Compose UI、导航、权限流程、MediaSessionService、画中画
├── core:data/      领域模型、MediaStore/文件系统/SAF 三种数据源、Room、DataStore、筛选与隐私空间
├── core:ffmpeg/    ffmpeg-kit 封装：探测、混流/转码、字幕导出、缩略图、命令转义与进度
└── core:player/    ExoPlayer 状态收敛、字幕解析(SRT/VTT/ASS)、播放队列、章节、睡眠定时
```

依赖方向单向：`app → core:player → core:ffmpeg`，`app → core:data`。`core:data` 不依赖 FFmpeg，
探测结果通过 `VideoMetadata` 模型回写，避免数据层被原生库绑住。

### 1.3 关键取舍

- **播放器实例进程级共享**：`AppContainer` 持有唯一 `ViViPlayer`，界面和 `MediaSessionService` 操作同一个
  ExoPlayer，通知栏/锁屏/媒体键和界面状态天然一致，不会出现「通知栏暂停了界面还在跑」。
- **字幕一律自渲染**：内封字幕从 `player.currentCues` 取，外挂/FFmpeg 导出的字幕自己解析。
  这样「延迟 / 字号 / 描边 / 底色」对内封和外挂都生效——Media3 的 `SubtitleView` 做不到延迟补偿。
- **筛选不触发扫描**：`VideoRepository` 只负责「全量条目 + 去重」，筛选/排序/隐私屏蔽在上层
  `LibraryFilterEngine` 做纯函数计算，条件变化不会重扫存储。
- **数据源合并策略**：MediaStore 是响应式（ContentObserver），文件系统遍历与 SAF 树是快照式，
  所以是「MediaStore 流 + 可刷新的补充列表」，而不是硬把三者都做成响应式。
- **手写依赖注入**：规模不大，`AppContainer` 一个文件看得清，省掉 Hilt/Koin + 一套 KSP 注解处理。

## 2. 功能清单

### 媒体库
- 所有文件访问授权后扫描**全部**视频（含 MediaStore 未索引目录、SD 卡/OTG 分区、`.hidden` 目录），未授权时退回媒体库模式
- 多维筛选：智能分类（电影/剧集/动画/纪录片/教程/短视频/其他）、清晰度档、时长档、文件大小档、容器格式、
  存储位置、观看状态（没看过/未看完/已看完）、只看收藏、指定文件夹、关键字搜索
- 排序：名称 / 最近添加 / 最早添加 / 时长 / 大小
- 继续观看（带进度条与「看到 xx:xx」）、文件夹视图、网格/列表、长按多选（收藏 / 隐藏 / 移入隐私空间 / 删除）
- 视频详情：分辨率、时长、大小、容器、码率
- 删除非本应用文件时走系统确认框（`MediaStore.createDeleteRequest` / `RecoverableSecurityException`）

### 播放器
- 手势：单击显示/隐藏控件、左右双击 ±10s、**长按临时倍速**（默认 1.25x，可在设置改成 1.1–4.0x，松手恢复原速）、
  横向拖动 seek（带 FFmpeg 雪碧图缩略预览）、左半屏上下滑亮度、右半屏上下滑音量
- 控件：上一/下一条、播放暂停、可拖动进度条（含缓冲位置）、倍速 0.25–4x（含 0.25/0.5/0.75/1/1.25/1.5/2/3/4 快捷档）、
  字幕菜单、音轨菜单、画面比例、循环模式、章节跳转、睡眠定时、画中画、界面锁定（竖屏防误触）
- 字幕：内封轨道选择、FFmpeg 导出 ASS/SRT 后自渲染、外挂字幕文件导入、延迟/字号/描边/底色
- 播放：同目录自然排序连播、断点续播（按视频记忆进度与音轨/字幕/倍速选择）、进度每 3 秒落库
- 系统：MediaSessionService 通知栏与锁屏控制、媒体键、后台播放、画中画（Android 8+，比例跟随视频）、屏幕常亮
- FFmpeg 兜底：容器不支持时自动混流并显示进度；视频编码不支持时提示并允许一键转码重试

### 隐私空间
- 两种模式：**仅在本应用隐藏**（不动文件）/ **移入应用私有目录**（复制进 `filesDir/vault` 并改成不可读文件名，
  其它应用与文件管理器都访问不到）
- PIN：PBKDF2 + 随机盐，不存明文；连续错误按次数递增锁定（3 次后 1 分钟、6 次后 15 分钟、10 次后 1 小时）
- 胁迫 PIN：用它解锁显示空列表；自动锁定时间可配；可在首页隐藏入口
- 移出：优先回到原目录，不可写则落到 `Download/ViVi` 并触发 `MediaScannerConnection` 重新索引

### 设置
- 权限状态与两个授权入口、扫描参数（最小时长、隐藏目录）
- 解码策略（硬解、自动混流、允许视频转码）、播放（续播、步进、后台、画中画、常亮）
- 字幕默认（语言、字号、延迟、描边/阴影/底色）、手势开关、外观（跟随系统/浅色/深色/纯黑、动态取色）
- 缓存占用与一键清理、FFmpeg 版本显示

## 3. 构建

```bash
# 需要 JDK 17+（本机用 D:/sdk/java/java21）、Android SDK（D:/sdk/android/sdk）
# 若 ~/.gradle/gradle.properties 里 org.gradle.java.home 指向 JDK 11，需要显式覆盖：
JAVA_HOME=D:/sdk/java/java21 ./gradlew :app:assembleDebug -Dorg.gradle.java.home=D:/sdk/java/java21

./gradlew :core:ffmpeg:testDebugUnitTest :core:player:testDebugUnitTest   # 纯 JVM 单测
```

产物按 ABI 拆分（ffmpeg-kit 每个 ABI 都带一整套 `libav*`）：
`app-arm64-v8a-debug.apk` ≈ 47MB、`app-armeabi-v7a-debug.apk` ≈ 48MB、`app-x86_64-debug.apk` ≈ 54MB，
`app-universal-debug.apk` ≈ 130MB（全 ABI，模拟器/未知设备用）。

版本锁定：AGP 8.7.3 / Gradle 8.9 / Kotlin 2.1.20 / KSP 2.1.20-1.0.32 / compileSdk 35 / minSdk 24 /
Media3 1.5.1 / Room 2.6.1 / Compose BOM 2025.01.00 / ffmpeg-kit-full 6.0-2。

> `ffmpeg-kit` 已从 Maven Central 下架（项目归档），只在 JCenter 时代的中转仓库上还有，
> 因此 `settings.gradle.kts` 里显式加了 `maven.aliyun.com/repository/public`。

## 4. 验证状态

### 已在真机验证（Redmi 10X 4G / Android 12 / arm64-v8a，`device-shots/`）
| 项目 | 结果 |
| --- | --- |
| 安装、图标、启动 | ✓ `10_player_texture_view.png` 图标与桌面一致 |
| 媒体库扫描 | ✓ MediaStore + 「所有文件」文件系统遍历双通道，`全部 17` |
| 缩略图 | ✓ 系统缩略图优先，非原生容器显示容器名占位（`05_flv_transcode_consent.png`） |
| 分类计数 / 继续观看 / 徽标 | ✓ 电影·剧集·动画计数、看到 0:02、1080p/MKV 徽标 |
| 原生播放 | ✓ 画面 + 声音（AudioTrack 354304 帧），横屏自动旋转 |
| 播放器控件 | ✓ 标题/返回/锁定/更多、上一条·暂停·下一条、缓冲进度条、0:02 / 32% / 0:08、1.0x·字幕·音轨·适应·循环·画中画 |
| FFmpeg ffprobe | ✓ 设备上产出探测缓存 `files/ffmpeg-probe/*` |
| FFmpeg 抽内封 ASS | ✓ `cache/ffmpeg/sub/…-s2.ass`，且字幕按样式渲染成**黄色底部居中**（`11_mkv_ass_subtitles.png`） |
| 长按临时倍速 | ✓ HUD「1.25x 加速中」（`12_longpress_speed.png`） |

### 真机测试发现并修掉的缺陷
1. **ExoPlayer 跨线程访问崩溃**：进度轮询跑在 `Dispatchers.Default` → 改到 `Dispatchers.Main.immediate`；
   同时把探测/混流/转码整条 IO 流水线从 Main 挪到 `Dispatchers.IO`（原来是主线程做磁盘与 FFmpeg 等待）。
2. **SurfaceView 盖住所有叠加层**：`PlayerView` 默认 SurfaceView 由系统单独合成一层，
   导致控件、字幕、进度预览在真机上完全不可见 → 改用 `app:surface_type="texture_view"`
   （`res/layout/vivi_player_surface.xml`）。
3. **分类「全部」永远显示 0**：ALL 不是分类而是总数 → facets 里改为条目总数。
4. **小体积视频被漏扫**：文件遍历的 1MB 门槛把几百 KB 的短片全过滤掉 → 纯视频容器降到 128KB，音频类扩展名保留 1MB。
5. **标题被挤成竖排**：TopAppBar 七个图标 → 文件夹/隐私空间/设置收进「更多」菜单。
6. **横屏未接线**：`autoRotate` 设置之前没有任何地方使用 → 播放器按设置请求横屏/竖屏。

### 仍待真机验证
- AVI/WMV/FLV 的混流与转码确认流程（命令向量已在主机用同一参数验证通过，App 侧接线未跑过）
- 隐私空间（PIN 设置/锁定/移入移出）、画中画、后台播放与通知栏、外挂 SRT 导入、字幕延迟与字号
- ffmpeg-kit 的日志噪声：`V ffmpeg-kit` 会把 ffprobe 的 JSON 逐行打进 logcat，
  6.0 版没有 `setLogcatLevel`，需要用 `FFmpegSession.create(..., LogRedirectionStrategy)` 显式建会话才能压掉

### 离线验证
- 21 个 JVM 单测通过（命令转义 6 / ffprobe JSON 解析 9 / 字幕解析 6）
- 16 项 FFmpeg 命令级校验通过（`tools/verify-ffmpeg-commands.sh`，用与 Kotlin 侧一致的参数向量）
- 期间修掉：copy 混流目标容器由 MP4 改为 MKV（实测 wmv/flv copy 进 MP4 被容器拒绝）、
  ASS `\an` 编号映射写反、VTT 带 `align:middle` 时时间解析失败、章节 `HH:MM:SS.nnn` 被读成 0

## 5. 已知限制与下一步

1. **FFmpeg 不参与解码**：视频解码仍是 MediaCodec。真正的「FFmpeg 软解」需要自己用 NDK 编出
   `libav*` 并写 ExoPlayer 的 `ffmpeg` extension（官方在 ExoPlayer 源码树里有该扩展，但要自行编译 FFmpeg）。
   当前实现用混流/转码覆盖了同一批场景，代价是转码路径需要等待。
2. **位图字幕（PGS/DVD/DVB）无法显示**：FFmpeg 不做 OCR，这类轨道只能标记为不支持。
3. **ASS 渲染是近似**：解析了 PrimaryColour / `\an` / MarginV，未实现 `\pos`、卡拉 OK `\k`、多重样式叠加。
4. **网络源**：SMB/NFS/WebDAV/FTP 尚未接入（`core:ffmpeg` 的输入解析已为 http/rtsp 预留路径）。
5. **转码是整文件**：没有做「分段转码 + 边转边播」，长片等待时间较明显。
6. 隐私空间的「仅隐藏」模式对系统相册无效——这是 Android 媒体库的固有行为，只有 RELOCATED 模式才真正隔离。

## 6. 第二轮真机迭代（Android/data 扫描 + 界面调整）

**扫描 Android/data**
- 新增设置项「扫描 Android/data 目录」（默认开）。`StorageVolumes` 把 `/android/data|obb|media`
  从硬排除改成可开关；`/proc /sys /system /vendor /apex` 仍然永久排除。
- 真机结论：Android 11+ **不能枚举** `/sdcard/Android/data`，遍历必须在应用自己的外部私有目录上直接命中。
  因此把 `getExternalFilesDir(null)` 与 `externalMediaDirs` 追加为扫描根，实测 `own_pkg_video.mp4` 进入列表；
  放在别的包目录下的 `hidden_in_android_data.mp4` 仍不可见 —— 这是系统限制，不是扫描逻辑问题。
- 扫描提示改为「新增条数」而不是遍历总数（之前显示「补充 17 条」其实是全量，误导）。

**界面调整**
- 顶栏 7 个图标 → 4 个 +「更多」菜单，标题恢复单行，不再被挤成竖排。
- 「继续观看」改为 152dp 紧凑卡（16:10 缩略图 + 底部进度条 +「还剩 x:xx」），不再用大卡叠长文字。
- 分类条加左右留白与选中填充样式；「全部」计数改用当前可见条数，与顶栏计数一致（实测 `全部 10` / `10 个视频`）。
- 无系统缩略图的容器（WMV/FLV）占位改为「摄像机图标 + 容器名」，不再是光秃秃的文字。
- 播放器改为全程沉浸（状态栏/导航栏一直隐藏），控件叠加层用 `WindowInsets.safeDrawing` 避让刘海与圆角。

## 7. 第三轮真机迭代（FFmpeg 三条分支全部跑通）

媒体库链路实测通过：搜索（需先聚焦输入框）、排序（名称 A→Z 生效）、筛选面板（分类/清晰度/时长/大小/观看状态/容器/存储位置/只看收藏）、
重置、容器筛选后 `1 个视频` 精确命中。

FFmpeg 三条分支在 Redmi 10X 上端到端验证：

| 分支 | 输入 | 结果 |
| --- | --- | --- |
| 原生 | h264_aac.mp4 | ✓ 画面+声音 |
| 混流 copy | xvid.avi | ✓ 产出 `cache/ffmpeg/play/<key>.mkv`，AVI 正常播放 |
| 内封字幕 | mkv_ass.mkv | ✓ 抽出 ASS 并按样式渲染（黄色底部居中） |
| 转码 | flv_sample.flv（flv1，系统无解） | ✓ 确认后产出 230KB MKV 并播放 |

### 这一轮修掉的 4 个 bug
1. **`callbackFlow` 不关闭通道**：`FFmpegRunner.run()` 发完 `Done` 就挂在 `awaitClose`，
   用 `collect{}` 的混流/转码**永远不返回**（真机表现为进度条卡在 60%）。用 `first()` 的抽字幕反而正常，
   所以这个 bug 只在混流路径暴露。已补 `channel.close()`。
2. **ffmpeg-kit-full 不含 libx264**：真机报 `Unknown encoder 'libx264'`，转码分支必然失败。
   改为运行时探测编码器（`libvpx` → 退化 `mpeg4`），输出容器统一 MKV。
3. **第二个跨线程崩溃**：`AppContainer` 的设置监听跑在 `Dispatchers.Default` 上直接调
   `setSubtitleStyle/setPreferredLanguages` → 点「转码后播放」写设置时崩。已切回主线程。
4. **重试转码读旧快照**：`allowTranscodeAndRetry()` 写完设置立刻 `start()`，但读的是尚未回流的
   `settingsSnapshot` → 又弹一次确认。改为显式 `start(forceTranscode = true)`。

另外：混流产物用 `File.renameTo` 落位在该 ROM 上返回 false（导致每次进视频都重混一遍），改用 `Files.move`；
失败页不再叠一层播放控件。

### 仍未在真机验证
隐私空间全流程、画中画、后台播放与通知栏、外挂 SRT 导入、字幕延迟/字号、拖动预览雪碧图的实际显示。

## 8. 第四轮真机迭代（隐私空间 + 画中画 + 拖动预览）

**已验证**
- 隐私空间：设置 PIN（两遍确认）→ 胁迫 PIN/自动锁定页 → 完成；列表空态提示正确；
  移入 RELOCATED 后：文件复制到 `files/vault/f4c4fae4….flv`（大小一致、权限 600、名字不可读），
  原文件删除、MediaStore 记录清除、媒体库从 10 条变 9 条 ✓；退出再进需要重新输 PIN ✓；
  错误 PIN 提示「PIN 不正确」✓。
- 画中画：起播后按 HOME 自动进 PiP（`mLastReportedPictureInPictureMode=true`），小窗画面比例正确、系统关闭按钮可用 ✓。
- 拖动预览：雪碧图生成 + 裁切显示 + 松手 seek 全部正常（截图 `p03_scrub_fixed.png` 显示 0:06 / 0:08 与缩略格）。

**这一轮修掉的 7 个问题**
1. 横屏下 PIN 键盘的「0 / 退格 / 确定」被挤出屏幕外，无法提交 → 键盘改可滚动 + 紧凑尺寸。
2. 隐私空间各页面没画背景，深色窗口底 + 浅色文字几乎看不清 → 统一 `background(MaterialTheme.colorScheme.background)`。
3. PIN 输错后键盘不清空，第二次输入会和上一次拼成 8 位，永远解不开 → 提交即清空。
4. 设置完 PIN 立刻又被要求输入一次 → `createPin` 成功后置为已解锁。
5. 雪碧图输出目录没有 `mkdirs()`，真机上 ffmpeg 报 `Could not open file …/thumb/<hash>/sheet_001.jpg`，
   拖动预览一直是坏的（主机脚本因为预先建了目录所以没暴露）。
6. **拖动进度条不会 seek**：`ScrubBar` 只接了 `onValueChange`，没接 `onValueChangeFinished`，
   也没置 scrub 状态 → 松手不跳转、预览不出现。已补齐。
7. 隐私空间列表显示哈希文件名 → 改为记住并显示移入时的原名。

**仍未验证**：后台播放与通知栏/锁屏控件、外挂 SRT 导入、字幕延迟与字号的实际观感、修改/关闭 PIN。

## 9. 第五轮真机测试：后台播放与锁屏控件（**未通过，已定位根因**）

**能工作的部分**
- `MediaSessionService` 会被拉起，`MediaSession` 注册成功，系统能看到会话（`controllers: 3`）。
- 打开「后台播放」后按 HOME，音频确实继续播（`state=3`，位置持续推进）。
- 关掉「后台播放」后按 HOME 会暂停（此前那次「没暂停」其实是自动进了画中画，PiP 下 Activity 仍算前台，属正确行为）。

**失败的部分**
- **通知栏与锁屏控件完全不出现**：`startForegroundCount=0`，`dumpsys notification` 里没有本包的 NotificationRecord。
- 后果是真机可复现的：约 10 余秒后进程被 MIUI 杀掉（pid 从 23045 变成 24181，会话变 `active=false / state=null`），后台播放随之中断。

**根因**
现在的架构是「进程级共享一个 ExoPlayer，UI 和 Service 都用它」，Service 只是被 `startService` 拉起。
Media3 1.5.1 的 `MediaSessionService` 是否转前台由它内部的「建议」决定，实测在这台 MIUI 上永远不给 `true`；
而 1.5.1 又**没有** `onCreateNotificationManager` / `setForegroundMode` 这些 1.6 才有的钩子，
`onUpdateNotification(session, startForeground)` 覆写也没能触发。

**下一步（两种做法，建议 A）**
- A. 按 Media3 官方模式重构：播放器归 `MediaSessionService` 所有，UI 通过 `MediaController` 连接。
  这样通知、锁屏、媒体键、PiP 自定义按钮都由 Media3 托管，也天然拿到前台服务保护。
- B. 保留共享播放器，自己实现 `MediaNotification.Provider` 并在其中显式 `startForeground`（改动小，但要自己维护通知与操作按钮）。

## 10. 第六轮：A 方案重构（播放器归 Service）+ 后台播放/锁屏控件通过

架构改动：`ViViPlayer`(ExoPlayer) 与探测→混流→转码流水线整体搬进
`app/.../service/PlaybackCoordinator.kt`，由 `PlaybackHolder` 保证进程内单例，
`ViViMediaService.onCreate` 用它建 `MediaSession`；`PlayerViewModel` 退化成薄客户端，
`onCleared` 只落进度、不释放播放器。

**两个只有真机能暴露的根因**

1. `startForegroundService()` 是错的。Media3 1.5.1 的 `MediaSessionService.onStartCommand`
   只处理媒体键与自定义动作，普通启动 intent 什么都不做，于是 10 秒义务没人兑现，
   系统直接抛 `ForegroundServiceDidNotStartInTimeException` 并 ANR 杀进程
   （`device-shots/n08`、logcat `AnrManager: Reason: Context.startForegroundService() did not then call Service.startForeground()`）。
   现在改成普通 `startService()`。
2. 前台化的真正开关是 `addSession()`。`MediaNotificationManager.updateNotification()`
   第一句 `isSessionAdded(session)` 为假就 return；而 1.5.1 只在「控制端通过 binder 连进来」
   时才登记会话，进程内 `MediaSession.Builder.build()` 不触发它。
   在 `onCreate` 里显式 `addSession(session)` 后链路全通。
   （`onUpdateNotification(session, startForeground)` 的默认实现会把布尔值丢弃并转调单参版本，
   上一轮试图靠它强制前台是无效的。）

**验证结果**

- Redmi 10X（Android 11）：起播即 `isForeground=true foregroundId=1001 category=transport actions=3`，
  通知栏出现媒体卡片（片名 + 进度 + 三个控制键），锁屏同样出现；
  熄屏 60 秒后进程仍在、`state=3` 持续推进；通知上的暂停/播放按钮实测改变会话状态（3↔2）。
- 模拟器（Android 13 / API 33）：`startForegroundCount=1`、通知存在、后台 12 秒位置从 94s 走到 110s。
- 顺带修掉：队列自动推进到下一条不兼容格式时只会 `UnrecognizedInputFormatException` 报错，
  现在 `PlaybackCoordinator` 监听 `onPlayerError` 把出错那条重新送回 FFmpeg 入口（同一条只兜一次，避免死循环）。
- 通知栏/锁屏卡片原来只显示应用名，因为 `MediaItem` 没带 `MediaMetadata`；现在标题与时长都填上。

## 11. 第七轮：界面按新规则重做 + 本地播放列表

**播放界面**
- 默认竖屏（画面居中留黑边），顶栏新增旋转按钮手动横竖屏切换；原来的「自动旋转」设置项删除。
- 控件整体缩小：播放键 72→52dp、上下曲 52→38dp、顶栏图标 48→34dp、底部功能键文字 13→11sp。
- 进度条换成自绘细条（轨道 2.5dp、滑块 4.5dp）：Material3 1.3.1 的 `Slider` 不支持自定义 thumb/track，
  要换 `SliderState` 重载才行，不值得为此绕。
- 倍速与切片提示改到顶部小字（`Hud.Speed` / `Hud.Track`），快进快退仍居中。
- 上下滑动不再调亮度/音量，改为切换上一个/下一个视频（上滑=下一个，阈值 90dp）；
  对应的两个设置项删除，`横向拖动画面调节进度` 保留并真正接上 `gestureSeek` 开关。
- **修掉一个只有真手感才会暴露的 bug**：长按循环原来把「事件已被消费」当成松手处理，
  而 `detectTapGestures` 会消费 down，导致长按倍速永远进不来；现在只看 `change.pressed`。
  （注意 `adb shell input swipe` 起止同点时不产生中间 MOVE 事件，用它测长按会得到假阴性。）

**本地播放列表**
- Room 升到 v2，新增 `playlists` / `playlist_items` 两张表 + 手写 `Migration(1,2)`
  （`fallbackToDestructiveMigration` 会把观看进度和收藏一起清掉，所以必须给迁移）。
- `PlaylistRepository`：建/改名/删列表、加入/移出/重排；只存 media key 与顺序，不复制文件，
  文件被移走后条目自然消失。
- 媒体库顶部的分类栏改成两个维度：**全部 N / 播放列表 M**（分类筛选仍在筛选面板里）。
- 播放列表详情页：播放全部、改名、删除、逐条移出；起播时把列表顺序作为连播队列传给播放器。
- 视频长按详情面板新增「加入播放列表」，可在弹窗里选已有列表或就地新建。

**Android 13 上的通知权限缺口**
`POST_NOTIFICATIONS` 只在 manifest 里声明、从未申请。Android 13 起它是运行时权限，
不授权时前台服务通知会被系统吞掉，表现就是「后台还在出声，但通知栏与锁屏控件完全不出现」。
现在在媒体权限流程之后补申请，老用户（媒体权限已授）冷启动时也会补一次。

**本轮遗留**
- 设置里的「最小视频时长」曾是死配置（只在设置页读写）——第八轮已接进筛选链路。
- 「时长未知就不过滤」是有意为之：MediaStore 与文件遍历都给不出时长，只有 ffprobe 能给出，
  所以探测前的条目一律放行，否则测试片与未索引目录会被整批隐藏。

## 12. 第八轮：按功能补测试用例并跑到全绿

**JVM 单测（`gradle testDebugUnitTest`）**
- `core:data` 新增 25 例：
  - `PlaylistRepositoryTest`（10）：建列表 / 空白名回落 / 加入按序编号且重复忽略 / 顺序解析并跳过已移走文件 / 重排 / 移出 / 删列表连带清条目 / 改名忽略空白 / 计数 / 实时条目。
  - `LibraryFiltersTest`（11）：最短时长只砍「已知且过短」的、搜索大小写无关、外部分类映射优先、启发式分类各分支、分辨率分档与卡片标签一致、时长与体积分档边界、多维叠加、收藏与观看状态、5 种排序、目录筛选、facets 的「全部」等于总数。
  - `ScanRulesTest`（4）：扩展名识别、纯视频容器更低的体积门槛、系统目录恒跳过而应用私有目录随开关、扫描深度上限。
- `app` 新增 `PlayerGesturesTest`（10）：双击落在左右半屏的方向与步长、上下滑的方向与阈值边界、长按需要「够久且没移动」。
- 为可测性做的改造（不是为测试而测试）：
  - `PlaylistRepository` 依赖 `PlaylistDao`/`PlaylistItemDao` + key 解析函数，不再绑死 `ViViDatabase`。
  - 手势里与框架无关的判定抽到 `PlayerGestures`，Compose 的手势识别本身不归我们测。
  - `core:data` 开 `unitTests.isReturnDefaultValues` 并加同包 `TestUri`（`android.net.Uri` 构造器是包级私有，子类必须放在 `android.net` 包里）。

**设备功能测试（`python tools/functional_tests.py`，14 例）**
覆盖媒体库可见性、播放列表建-开-删、默认竖屏与旋转切换、控件尺寸、上下滑切视频、长按倍速、双击步进、内封 ASS 字幕、后台播放+前台服务+通知+媒体键、锁屏可见通知、WMV 走 FFmpeg、搜索与筛选面板、画中画、隐私空间入口。

**这轮暴露的测试方法学坑（都写进了脚本注释）**
- `uiautomator dump` 一次要 1.5~2.5 秒，比控件自动隐藏（3.5s）和 HUD（900ms）还慢，「读不到」经常是观测延迟。瞬态 UI 的断言改成读 `dumpsys media_session` 的进度与墙钟时间之差。
- adb `input tap; input tap` 间隔只有约 18ms，**太快不会被识别成双击**；`sendevent` 在这台模拟器上注不进去。可用形式是 `input motionevent DOWN/UP; sleep 0.12; DOWN/UP`，实测进度精确 +30.016s。
- 「继续观看」会把播放点恢复到片尾（不是 bug，是上次看到哪儿就续到哪儿），导致位置读数纹丝不动；测步进/字幕前必须先回到片头（用应用自己的横向拖动进度手势往回拖）。
- 屏幕正中的单击会命中播放/暂停键，收放控件要点空白区（540,470）。

## 13. 第八轮后半段：测试逼出来的 4 个真 bug

补测试的过程中，以下缺陷是被用例（而不是人工点按）发现并修掉的：

1. **同一个文件有两套 key，进度/收藏会整条失联**
   `MediaStoreVideoSource` 用 `ms:<id>`，文件扫描用 `fs:<路径>`。进度、收藏、播放列表存的是
   「当时看到的那把 key」，于是 MediaProvider 一完成索引，之前用 `fs:` 存的观看进度就再也匹配不上，
   「继续观看」直接空掉。修法：MediaStore 行有真实路径时统一用 `MediaKey.ofPath(dataPath)`
   （[MediaStoreVideoSource.kt:101](core/data/src/main/java/com/vivi/core/data/scan/MediaStoreVideoSource.kt)），
   并给历史数据留了按路径回退的 `resolveMediaKey()`。
   已知边界：scoped storage 下 `DATA` 为空、只有 `ms:<id>` 的行，一旦媒体库不再列出就无从回推
   （用例 `idKeyedRowWithoutPathCannotBeRecovered` 把这个边界钉住，而不是假装能解析）。
2. **「继续观看」的流只监听进度表**
   冷启动时媒体库条目还没就绪，解析结果为空；之后进度不变，Room 不会再推一次，这一段就永久空白。
   改成与 `videoRepository.observeItems()` 一起 `combine`。
3. **时长未知就显示「已看完」**
   `ResumableCard` 用媒体库里的时长（MediaStore 给不出时是 0）减进度 → 剩余恒为 0。
   现在回退用进度里存的时长，且只有真正 completed 才标「已看完」，否则显示「看到 mm:ss」。
4. **倍速面板只有 5 个速度能点**
   9 个速度塞在一个 `Row` 里，超出屏宽的 1.5x/2x/3x/4x 既看不见也点不到。改成 `LazyRow`。

另外把设置里一直是摆设的「最小视频时长」接进筛选链路（`LibraryViewModel.recompute()` 用它覆盖
`LibraryFilter.minDurationMicros`）。

**测试清单（当前全绿）**
- JVM：99 例（`core:ffmpeg` 39、`core:player` 6、`core:data` 44、`app` 10），`gradle testDebugUnitTest` 0 失败。
  本轮新增：`CompatibilityPolicyTest` 14、`SpriteSheetTest` 10、`PinPolicyTest` 9、
  `MediaKeyResolutionTest` 9、`PlayerGesturesTest` 10、`PlaylistRepositoryTest` 10、
  `LibraryFiltersTest` 12、`ScanRulesTest` 4。
- 设备功能套件 `tools/functional_tests.py`：18 例（媒体库可见、播放列表建-开-删、默认竖屏+旋转、
  控件尺寸、上下滑切视频、长按临时倍速、双击步进、内封 ASS、后台播放+前台服务+通知+媒体键、
  锁屏可见通知、WMV 走 FFmpeg、搜索、筛选面板、画中画、隐私空间端到端、倍速面板选 1.5x、
  画面比例切 16:9、观看进度进「继续观看」）。

## 14. 第九轮：隐私空间端到端跑通 + 补两类设备用例

**`vault_flow` 卡在哪（三处都是测试自身的问题，应用逻辑没改）**
1. 「长按面板里没有『仅在本应用隐藏』」——`VideoCard` 的 `combinedClickable` 只挂在缩略图那个
   16:9 Box 上，文件名在下面的另一个 Column 里（[UiComponents.kt:116](app/src/main/java/com/vivi/player/ui/common/UiComponents.kt)），
   adb 长按文字位置本来就什么也不会弹。用例改成优先命中卡片 `content-desc`（它就在缩略图里），
   没缩略图的容器（AVI/WMV）从文字位置上移 170px。
2. 期望写错了：解锁状态是 `VaultRepository` 进程内的 `MutableStateFlow`（默认 1 分钟自动上锁），
   同进程里退回媒体库再进隐私空间不会再要 PIN。用例改成先 `am force-stop` 再进，PIN 关卡才真的被测到。
3. `reset_vault_state()` 删错目录：DataStore 实际落在 `files/datastore/`，不是 `datastore/`，
   于是上一轮设的 PIN 一直留着，第二次跑就变成「既没有设置入口也没有 PIN 锁」。
   另外用例现在会先把隐私空间里残留的条目「取消隐藏」再取基线，中途失败也能重跑。

模拟器（Android 13）实测：基线 9 → 隐藏后 8 且卡片从库里消失 → 重启后要求输 PIN →
隐私空间里能看到该片 → 取消隐藏 → 计数回到 9。

**这一轮新增两例设备用例**
- `vault_relocated`：「移入隐私空间目录」是真实复制文件（`files/vault` + 改名 + 校验大小后删原文件），
  UI 计数不可靠，断言直接读文件系统：移入后 `run-as ls files/vault` 有文件且 `/sdcard/Movies/ViViTest/xvid.avi` 消失；
  点「放回原目录」后两者反过来。探针片用 `xvid.avi`（仓库 `tools/testmedia/` 留有副本，中途失败可重放，
  不占用其它用例依赖的片）。
- `android_data_visible`：对应「搜索不能跳过 `Android/data`」这条硬要求。Android 11+ 之后
  `/sdcard/Android/data` 不可枚举，但**应用自己的**外部私有目录能用绝对路径读
  （[ExtraVideoSources.kt:55](core/data/src/main/java/com/vivi/core/data/scan/ExtraVideoSources.kt) 的 `defaultRoots`），
  所以往 `/sdcard/Android/data/<pkg>/files/` 放一片，冷启动重扫后媒体库必须多出一条，删掉后又必须消失。
- `long_press_speed_config`：「长按默认 1.25x、**可以修改**」这半句之前只在设置页读写、没在设备上验过。
  现在先到设置里点 `3.00x`（确认副标题跟着变成「当前 3.00x」），再进播放界面长按，
  顶部小字提示必须是「3.00x 加速中」，最后把值改回 1.25x，免得把默认值用例带跑。

**本轮收尾状态（写这份 README 时的证据边界）**
- 已验证：JVM 单测 99 例 0 失败（`core:ffmpeg` 39 / `core:player` 6 / `core:data` 44 / `app` 10）；
  设备套件在当前构建（`app-x86_64-debug.apk`，emulator-5554 / Android 13）上已跑出的用例全部 PASS、0 FAIL，
  包括后台播放 + 前台服务 + 通知 + 媒体键、锁屏可见通知、PiP、WMV 走 FFmpeg、内封 ASS、
  隐私空间端到端（`vault_flow` 单独跑通：9 → 隐藏后 8 → 重启要输 PIN → 取消隐藏回到 9）。
- 排队未收口：整套 21 例的「一次性全绿」记录。跑法是
  `PYTHONIOENCODING=utf-8 python -u tools/functional_tests.py --serial emulator-5554`（约 40 分钟，
  中途别占设备；`--case` 支持逗号分隔以便分段跑），日志在 `tools/final21.txt`。
  这套件是幂等的：`vault_flow` 会先清残留隐藏项，`vault_relocated` 用可重放的 `xvid.avi`，
  `android_data_visible` 用完就把探针片删掉。

## 15. 第十轮：按用户要求改回真机，暴露 3 个只在 MIUI 上成立的问题

真机 `108a24d50409`（Redmi 10X / Android 12）整套 21 例现在 **19 过 / 2 卡**；锁屏用例按「不用测试锁屏情况」删掉。

**这轮改掉的三个真问题（模拟器发现不了）**
1. **长按临时倍速要求手指抖动**：boost 只在收到中间 MOVE 事件时才判定，按住不动（或 adb 注入的定点长按）永远不起 1.25x。
   改成用 `withTimeoutOrNull(50L)` 包住 `awaitPointerEvent`，超时也按墙上时间判一次。
   真机实测：会话 `speed=1.25`；设置里改成 3.00x 后会话 `speed=3.0`、提示「3.0x 加速中」。
2. **横屏切不过去**：原来用 `SCREEN_ORIENTATION_SENSOR_LANDSCAPE`，MIUI 在系统「自动旋转」关闭时会忽略所有 sensor 系朝向。
   改成固定 `SCREEN_ORIENTATION_LANDSCAPE`（本来就是手动按钮，不需要跟重力），真机 0→90→0→90 正常。
3. **播放控件底栏贴着手势导航条**：补 `windowInsetsPadding(WindowInsets.navigationBars)`；
   `AnimatedVisibility` 默认 enter 含 `expandVertically`，改成只 `fadeIn/fadeOut`。

**还卡着的两条，根因在观测层**：MIUI 把底栏那一排（`1.0x`、字幕、音轨、适应、画中画、进度时间）的 bounds 全报成
`[0,0][0,0]`（顶栏和中间三个键正常），按 UI 文本就找不到可点坐标，于是 `speed_selection` / `aspect_toggle` 停在「入口点不到」；
同一构建在模拟器上这两条是绿的。两条出路：底栏换成带 `contentDescription` 的 IconButton（有 desc 的节点 bounds 正常），
或这两条改成按锚点几何坐标点击、只断言会话里的 speed / 比例字段。

**脚本自身的坑（都已修）**：`rotation()` 原来只认 AOSP 的 `mDisplayRotation`，MIUI 报的是 `mCurrentRotation`；
`uiautomator dump /dev/tty` 在 MIUI 上被 `theme_compatibility.xml` 堆栈污染且偶发返回空树，改成落盘再 `cat`、
丢弃零面积节点并重抓（`embedded_subtitle` 因此转绿）；转屏动画期间旧坐标作废，第二次点旋转前要等稳；
锁屏用例会把设了 PIN 的真机锁死，导致后面每条用例都变成假失败（现在 `relaunch()` 检测到锁屏直接 `[STOP]`）。

## 16. 第十一轮：全屏播放返回首页，顶栏被状态栏压住

真机 `108a24d50409` 复现并修掉，取证图 `device-shots/status_bar_before_fix.png` / `status_bar_after_fix.png`。

**根因**：`PlayerScreen` 用 `LaunchedEffect(pipActive)` 调 `applyImmersive(hideSystemUi = !pipActive)`，
只有「进入播放页时隐藏」，没有任何地方在离开时还回来。整个 App 只有一个 Activity，系统栏的隐藏状态挂在 Window 上，
`popBackStack()` 回首页后仍停在隐藏态；首页 `Scaffold` 的 `contentWindowInsets` 读到的 `statusBars` inset 变成 0，
顶栏就顶到屏幕最上沿，状态栏一出现（MIUI 会把它拉回来）正好压在「媒体库」标题上。

**改法**：换成 `DisposableEffect(activity, pipActive)`——进入时照旧全程沉浸，`onDispose` 里
`show(systemBars())` 把系统栏还回来。PiP 分支不变（`pipActive` 变化时先 dispose 再应用，两次都是 `show`，幂等）。
从播放页进「设置」也一并受益，之前那条路径同样会把隐藏态带过去。

**真机读数**（新增用例 `status_bar_after_player`，整套现在 21 条）
- 修复前（旧包）：首页顶栏距屏幕上沿 冷启动 `109px` → 横屏全屏返回首页 `9px`，FAIL。
- 修复后（新包）：`109px → 109px`，PASS；同一条用例还断言沉浸没被改坏——
  `dumpsys window` 的 `InsetsSource type=ITYPE_STATUS_BAR … visible=` 在冷启动是 `true`、播放页里是 `false`。
- 回归全绿：`library_visible`、`player_portrait_rotate`、`resume_progress`、`pip`、`background_notification`、
  `playlist_crud`、`controls_compact`、`search_hits`、`filter_sheet`；JVM 单测 99 例 0 失败。

**这一轮踩到的两个观测坑**
1. 拿「播放页控件行距顶」和「首页标题距顶」比大小是错的：`ControlsOverlay` 自己带内边距，实测 128px 反而大于首页的 109px。
   系统栏状态只认 `dumpsys window` 里的 `ITYPE_STATUS_BAR visible`（MIUI 的 `dumpsys statusbar` 没有 `mVisible` 字段）。
2. 确认控件在和取坐标必须落在同一次 dump 里：控件 3.5 秒自动收起，而一次 dump 要 1.5~2.5 秒，
   分两次读会点到已经收起的按钮（横屏因此进不去，误报成产品 bug）。

**顺带定性的一条 `background_notification` 假失败**：整套里报 `后台不在播放：state=1`，单跑同一构建是绿的
（位置 119ms→128ms、暂停→2、播放→3）。原因是上一轮用例中途退出时播放器停在片尾，
「继续观看」把下一条用例的起播点恢复到末尾，10 秒内播完就会报 state=1——观测层问题，不是后台播放链路坏了。



