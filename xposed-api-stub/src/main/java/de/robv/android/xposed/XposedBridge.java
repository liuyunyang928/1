package de.robv.android.xposed;

import java.lang.reflect.Member;
import java.util.Set;

/** Stub of the legacy Xposed API bridge. */
public final class XposedBridge {

    private XposedBridge() {
    }

    public static void log(String text) {
    }

    public static void log(Throwable throwable) {
    }

    public static Set<XC_MethodHook.Unhook> hookAllMethods(
            Class<?> hookClass, String methodName, XC_MethodHook callback) {
        return null;
    }

    public static Set<XC_MethodHook.Unhook> hookAllConstructors(
            Class<?> hookClass, XC_MethodHook callback) {
        return null;
    }

    public static XC_MethodHook.Unhook hookMethod(Member method, XC_MethodHook callback) {
        return null;
    }

    public static int getXposedVersion() {
        return 82;
    }
}
