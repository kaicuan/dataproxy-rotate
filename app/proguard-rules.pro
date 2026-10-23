# Add project specific ProGuard rules here.
# Keep app entry points
-keep class com.dataproxy.MainActivity { *; }
-keep class com.dataproxy.service.ProxyService { *; }
-keep class com.dataproxy.DataProxyApplication { *; }

# Keep coroutines internals
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# app_process entry point, loaded by name from the shell helper spawn.
-keep class com.dataproxy.helper.HelperMain {
    public static void main(java.lang.String[]);
}

# libadb discovers Conscrypt by Class.forName.
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
