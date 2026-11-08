# The Xposed entry point is instantiated by LSPosed through reflection, keep it.
-keep class io.github.liuyunyang928.noshake.xposed.ModuleEntry { *; }

# The legacy Xposed API is provided by LSPosed at runtime, never bundle it.
-dontwarn de.robv.android.xposed.**
