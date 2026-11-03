package com.vivi.core.ffmpeg

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFmpegSessionCompleteCallback
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.FFprobeSessionCompleteCallback
import com.arthenica.ffmpegkit.LogCallback
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.StatisticsCallback
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class FFmpegOutcome(
    val success: Boolean,
    val returnCode: Int,
    val logs: String,
) {
    val errorSummary: String
        get() = logs.lineSequence()
            .lastOrNull { it.contains("Error", ignoreCase = true) || it.contains("error", ignoreCase = true) }
            ?.trim()
            ?.take(300)
            ?: "ffmpeg 退出码 $returnCode"
}

sealed interface FFmpegEvent {
    /** 已写入输出文件的媒体时间，单位微秒。 */
    data class Processed(val micros: Long) : FFmpegEvent
    data class Done(val outcome: FFmpegOutcome) : FFmpegEvent
}

class FFmpegFailure(message: String) : Exception(message)

/**
 * ffmpeg-kit 只有回调 API，且统计回调是进程级单例，这里按 sessionId 把它分发给正在收集的 Flow。
 * 协程取消时同步取消底层 session，否则 ffmpeg 会继续读写缓存文件。
 */
class FFmpegRunner(private val context: Context) {

    fun run(arguments: String): Flow<FFmpegEvent> = callbackFlow {
        FFmpegEnvironment.init(context)
        var sessionId = NO_SESSION
        val listener: (Long) -> Unit = { micros -> trySend(FFmpegEvent.Processed(micros)) }

        val outcome = suspendCancellableCoroutine { cont ->
            val session: FFmpegSession = FFmpegKit.executeAsync(
                arguments,
                FFmpegSessionCompleteCallback { completed ->
                    FFmpegStats.removeListener(completed.sessionId)
                    if (cont.isActive) cont.resume(toOutcome(completed))
                },
                null as LogCallback?,
                null as StatisticsCallback?,
            )
            sessionId = session.sessionId
            FFmpegStats.addListener(sessionId, listener)
            cont.invokeOnCancellation {
                FFmpegStats.removeListener(sessionId)
                if (sessionId != NO_SESSION) FFmpegKit.cancel(sessionId)
            }
        }
        trySend(FFmpegEvent.Done(outcome))
        // callbackFlow 不会自己结束：不关通道的话，用 collect{} 的调用方会永远等下去
        // （真机上表现为混流进度条卡住不动），只有 first() 这类会主动取消的写法才能返回。
        channel.close()
        awaitClose { FFmpegStats.removeListener(sessionId) }
    }

    /** 只关心结果，丢弃进度。 */
    suspend fun execute(arguments: String): FFmpegOutcome =
        run(arguments).filterIsInstance<FFmpegEvent.Done>().first().outcome

    /**
     * ffprobe 的 JSON 输出。ffmpeg-kit 把 stdout 汇进日志缓冲，
     * `allLogsAsString` 为空时再取 `output`。
     */
    suspend fun probe(arguments: String): String {
        FFmpegEnvironment.init(context)
        return suspendCancellableCoroutine { cont ->
            val session = FFprobeKit.executeAsync(
                arguments,
                FFprobeSessionCompleteCallback { completed ->
                    if (!cont.isActive) return@FFprobeSessionCompleteCallback
                    val code = completed.returnCode
                    val text = completed.allLogsAsString ?: completed.output
                    if (code != null && ReturnCode.isSuccess(code) && !text.isNullOrEmpty()) {
                        cont.resume(text)
                    } else {
                        cont.resumeWithException(
                            FFmpegFailure("ffprobe 失败: ${(text ?: completed.allLogsAsString).orEmpty().take(300)}")
                        )
                    }
                },
                null as LogCallback?,
            )
            cont.invokeOnCancellation { FFmpegKit.cancel(session.sessionId) }
        }
    }

    private fun toOutcome(session: FFmpegSession): FFmpegOutcome {
        val code: ReturnCode? = session.returnCode
        return FFmpegOutcome(
            success = code != null && ReturnCode.isSuccess(code),
            returnCode = code?.value ?: RETURN_CODE_UNSET,
            logs = session.allLogsAsString.orEmpty(),
        )
    }

    private companion object {
        const val NO_SESSION = -1L
        const val RETURN_CODE_UNSET = -10000
    }
}

/** 全局统计回调 -> sessionId -> 监听者。 */
internal object FFmpegStats {

    private val listeners = ConcurrentHashMap<Long, MutableSet<(Long) -> Unit>>()

    init {
        FFmpegKitConfig.enableStatisticsCallback { stats ->
            val sessionId = stats?.sessionId ?: return@enableStatisticsCallback
            // ffmpeg-kit 6.x 的 Statistics.time 单位是毫秒
            val micros = (stats.time * 1000).toLong()
            if (micros > 0) listeners[sessionId]?.forEach { it.invoke(micros) }
        }
    }

    fun addListener(sessionId: Long, listener: (Long) -> Unit) {
        listeners.getOrPut(sessionId) { ConcurrentHashMap.newKeySet() }.add(listener)
    }

    fun removeListener(sessionId: Long) {
        listeners.remove(sessionId)
    }
}
