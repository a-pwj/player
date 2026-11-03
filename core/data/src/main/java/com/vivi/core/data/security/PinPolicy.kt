package com.vivi.core.data.security

import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * 隐私空间 PIN 的纯规则部分：强度校验、PBKDF2 派生、恒定时间比对、错误次数的锁定时长阶梯。
 *
 * 抽出来是为了能在 JVM 单测里覆盖——这些是安全语义，出错不会表现为「界面异常」，
 * 只会表现为「别人的 PIN 也能开」或「输错三次没有锁」。
 */
object PinPolicy {

    const val MIN_PIN = 4
    const val SALT_BYTES = 16
    const val ITERATIONS = 120_000
    const val KEY_BITS = 256

    /** 只接受纯数字且不少于 [MIN_PIN] 位。 */
    fun accepts(pin: String): Boolean = pin.length >= MIN_PIN && pin.all { it.isDigit() }

    fun randomSalt(): ByteArray = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }

    fun derive(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_BITS)
        val factory = runCatching {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        }.getOrElse { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1") }
        return factory.generateSecret(spec).encoded
    }

    fun hashHex(pin: String, saltHex: String): String = toHex(derive(pin, hexToBytes(saltHex)))

    /** 恒定时间比较，避免用耗时差猜前缀。 */
    fun matches(pin: String, saltHex: String, expectedHex: String): Boolean =
        equalsConstantTime(hashHex(pin, saltHex), expectedHex)

    fun equalsConstantTime(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    /** 连续错误的惩罚阶梯：前两次不锁，之后 1 / 15 / 60 分钟。 */
    fun penaltyMinutes(failedAttempts: Int): Int = when {
        failedAttempts < 3 -> 0
        failedAttempts < 6 -> 1
        failedAttempts < 10 -> 15
        else -> 60
    }

    fun toHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        bytes.forEach { append(((it.toInt() and 0xFF) or 0x100).toString(16).substring(1)) }
    }

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        require(clean.length % 2 == 0) { "hex 长度必须是偶数" }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
