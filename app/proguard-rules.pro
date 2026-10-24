# ===== Xposed module keep rules =====
# LSPosed instantiates the entry class via assets/xposed_init by name,
# so it must survive R8 shrinking with all its members.
-keep class io.github.voreulch.honoradvanced.MainHook { *; }

# ContentProvider declared in AndroidManifest — must not be renamed/removed.
-keep class io.github.voreulch.honoradvanced.CfgProvider { *; }

# Launcher activity (also a Compose host).
-keep class io.github.voreulch.honoradvanced.SettingsActivity { *; }

# Hook classes call system/framework methods reflectively via XposedHelpers
# (findClass/findMethodExact/hookAllMethods). Keep every member so the
# runtime-resolved targets always match.
-keep class io.github.voreulch.honoradvanced.GlassHook { *; }
-keep class io.github.voreulch.honoradvanced.InstallerHook { *; }
-keep class io.github.voreulch.honoradvanced.Cfg { *; }

# ===== Compose / Kotlin / KMP =====
# Compose compiler already emits keep rules for @Composable call sites,
# but be explicit about the runtime intrinsics reflection touches.
-keep @androidx.compose.runtime.Composable class * { *; }
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}
-dontwarn org.jetbrains.annotations.**
-dontwarn javax.lang.model.**
-dontwarn java.lang.invoke.**
-dontwarn kotlin.reflect.jvm.internal.**

# miuix KMP library uses reflection for some theming — keep its public surface.
-keep class top.yukonga.miuix.kmp.blur.** { *; }
