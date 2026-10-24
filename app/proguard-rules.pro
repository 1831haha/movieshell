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

# ---------- Java spider 宿主基类（jar 里子类按原名链接，R8 不许改名） ----------
# v0.42.0：漏了这条导致 release 包里宿主 Spider 被改名，
# jar 中 csp_ 子类链接到 jar 自带副本，as? Spider 全失败（"不是 Spider 子类"）。
-keep class com.github.catvod.crawler.** { *; }

# ---------- Gson（Java spider jar 依赖） ----------
-keep class com.google.gson.** { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod

# ---------- libmpv（dev.jdtech.mpv AAR，JNI） ----------
-keep class dev.jdtech.mpv.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# ---------- Guava（javascriptengine 的 ListenableFuture 依赖） ----------
-dontwarn com.google.common.**
-dontwarn com.google.j2objc.**

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

# ---------- Rhino JS（阅读式 JS 书源，v0.199.0，反射调用多，全 keep） ----------
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**

# ---------- slf4j（readability4j 的日志门面，v0.202.0 引入；无绑定实现） ----------
# v0.203.0 fix：readability4j 只带 slf4j-api、不带绑定，LoggerFactory.bind()
# 引用的 org.slf4j.impl.StaticLoggerBinder 不存在，release R8 在
# minifyReleaseWithR8 报错致 #393 失败；slf4j-api 无绑定时自动回退 NOP
# 日志，不影响正文提取，故只 dontwarn、不另引绑定包。
-dontwarn org.slf4j.**
