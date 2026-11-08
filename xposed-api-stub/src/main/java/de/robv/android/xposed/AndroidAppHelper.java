package de.robv.android.xposed;

import android.app.Application;

/** Stub of the legacy Xposed API helper used to access the hooked process' Application. */
public final class AndroidAppHelper {

    private AndroidAppHelper() {
    }

    public static Application currentApplication() {
        return null;
    }
}
