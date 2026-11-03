package com.vivi.core.ffmpeg

/**
 * ffmpeg 命令行构造。ffmpeg-kit 的 `execute()` 只接收参数串（可执行文件名由 SDK 内部补齐），
 * 参数串由内置解析器按 shell 规则切分，所以来自文件名/路径的外部输入必须先转义，
 * 否则空格、引号、`&` 会改变命令语义。
 */
object CommandBuilder {

    private val SAFE_CHARS = "-_.:/=+@,[]()'".toSet()

    fun arg(value: String): String {
        if (value.isEmpty()) return "''"
        val needsQuote = value.any { !it.isLetterOrDigit() && it !in SAFE_CHARS }
        if (!needsQuote) return value
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"'
    }

    /** 中文等非 ASCII 字符不需要转义，只有 shell 元字符需要。 */
    fun path(value: String): String = arg(value)

    /** ffmpeg-kit 的 SAF 参数是它自己生成的语法，加引号会让 saf 协议解析失败。 */
    fun input(input: MediaInput): String =
        if (input.rawCommandParameter) input.path else path(input.path)

    fun join(args: List<String>): String = args.joinToString(" ")

    fun join(vararg args: String): String = args.joinToString(" ")
}
