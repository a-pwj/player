package com.vivi.core.player

/** 播放器对 UI 暴露的全部状态，UI 不做任何推断。 */
enum class PlayerStatus { IDLE, PREPARING, PLAYING, PAUSED, ENDED, ERROR }

enum class AspectMode { FIT, STRETCH, CROP_16_9, CROP_4_3, CROP_FILL, ZOOM_2 }

data class TrackOption(
    val groupId: Int,
    val trackIndex: Int,
    val label: String,
    val language: String?,
    val codec: String?,
    val isDefault: Boolean,
    val isForced: Boolean,
)

data class SubtitleCue(
    val startMicros: Long,
    val endMicros: Long,
    val text: String,
    val primaryColorRgb: Int? = null,
    val alignment: AssAlignment = AssAlignment.BOTTOM_CENTER,
    val marginBottomPx: Int? = null,
)

/** ASS 的对齐编号 1..9 自下而上、从左到右，枚举顺序必须和它一致。 */
enum class AssAlignment {
    BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT,
    MIDDLE_LEFT, MIDDLE_CENTER, MIDDLE_RIGHT,
    TOP_LEFT, TOP_CENTER, TOP_RIGHT;

    companion object {
        /** ASS 的 \an{1..9} 与 NineSpos 编号一致。 */
        fun fromAssCode(code: Int): AssAlignment = entries.getOrElse(code - 1) { BOTTOM_CENTER }
    }
}

data class ChapterMarker(
    val startMicros: Long,
    val endMicros: Long,
    val title: String,
)

data class PlayerUiState(
    val status: PlayerStatus = PlayerStatus.IDLE,
    val title: String = "",
    val positionMicros: Long = 0,
    val bufferedMicros: Long = 0,
    val durationMicros: Long = 0,
    val speed: Float = 1f,
    val volume: Float = 1f,
    val isMuted: Boolean = false,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val rotationDegrees: Int = 0,
    val audioTracks: List<TrackOption> = emptyList(),
    val selectedAudioGroup: Int? = null,
    val subtitleTracks: List<TrackOption> = emptyList(),
    val selectedSubtitleGroup: Int? = null,
    val externalSubtitleCount: Int = 0,
    val activeCues: List<SubtitleCue> = emptyList(),
    val subtitleDelayMicros: Long = 0,
    val subtitleScale: Float = 1f,
    val chapters: List<ChapterMarker> = emptyList(),
    val aspectMode: AspectMode = AspectMode.FIT,
    val errorMessage: String? = null,
    val preparingNote: String? = null,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
) {
    val isReady: Boolean get() = durationMicros > 0 && status != PlayerStatus.IDLE
}

enum class RepeatMode { OFF, ONE, ALL }
