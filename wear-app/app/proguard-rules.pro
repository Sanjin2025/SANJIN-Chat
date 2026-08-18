# ==================== 混淆规则 ====================
# 保留 kotlinx.serialization 的序列化类
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.SerializationKt
-keep,includedescriptorclasses class com.sanjin.wear.model.**$$serializer { *; }
-keepclassmembers class com.sanjin.wear.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.sanjin.wear.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# 保留 OkHttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }
