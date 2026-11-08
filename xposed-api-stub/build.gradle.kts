plugins {
    `java-library`
}

// Minimal compile-time stubs for the legacy Xposed API (version 82).
// They are never packaged into the APK: LSPosed provides the real classes at runtime.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // The stubs reference a few framework types (Application, ApplicationInfo).
    // The real android.jar is used by the app module; this old public stub jar is enough to
    // compile the stubs on a plain JVM (CI + local builds) without hard-coding an SDK path.
    compileOnly("com.google.android:android:4.1.1.4")
}
