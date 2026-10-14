# 侠影 release 混淆规则（v0.27.3 起启用 R8）
# 原则：宁可多 keep，不压崩；第三方库自带 rules 的不再重复写。

# ---------- Chaquopy Python（反射调用多，全 keep） ----------
-keep class com.chaquo.python.** { *; }
-dontwarn com.chaquo.python.**

# ---------- 数据模型：JSON 落盘/缓存/反射，全 keep ----------
-keep class com.movieshell.data.** { *; }
-keepclassmembers class com.movieshell.data.** { *; }

# ---------- kotlinx.serialization ----------
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep class kotlinx.serialization.** { *; }
-keepclasseswithmembernames class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---------- Gson（Java spider jar 依赖） ----------
-keep class com.google.gson.** { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod

# ---------- libmpv（dev.jdtech.mpv AAR，JNI） ----------
-keep class dev.jdtech.mpv.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# ---------- QuickJS ----------
-keep class app.cash.quickjs.** { *; }
-dontwarn app.cash.quickjs.**

# ---------- WebView JS 桥（DrpyBridge.JsBridge，JS 经反射回调） ----------
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ---------- WorkManager（Worker 按类名调度） ----------
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.ListenableWorker

# ---------- FileProvider ----------
-keep class androidx.core.content.FileProvider { *; }
