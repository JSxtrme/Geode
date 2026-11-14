# Geode ProGuard rules (v0.1.18, R8/minify).
#
# R8's own generated rules (missing_rules.txt, first R8 run): Tink
# references these error-prone annotations, which are compile-time
# only and are not shipped in the app — safe to suppress.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi
# (no Class.forName, no JavascriptInterface, no Gson/Moshi/
# kotlinx-serialization). All JSON is org.json, networking is plain
# OkHttp, the key store is EncryptedSharedPreferences, and every
# Activity/Service/Receiver is declared in AndroidManifest.xml (which
# R8 keeps automatically). Compose, OkHttp, Okio and
# EncryptedSharedPreferences/Tink ship their own consumer ProGuard rules.
