# ffmpeg-kit 的 Java 入口需要保留（Native 侧通过 JNI 按名字回调）
-keep class com.arthenica.ffmpegkit.** { *; }
-dontwarn com.arthenica.ffmpegkit.**

# Media3 通过反射加载 Extractor / Decoder 工厂
-keep class androidx.media3.exoplayer.** { *; }
-dontwarn androidx.media3.**

# Room 实体与 DAO 由注解处理器生成实现，保留模型字段
-keep class com.vivi.core.data.db.** { *; }
-keep class com.vivi.core.data.model.** { *; }

# kotlinx.serialization 生成的 serializer
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
