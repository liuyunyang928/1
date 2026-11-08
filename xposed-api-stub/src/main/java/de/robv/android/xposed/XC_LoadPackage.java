package de.robv.android.xposed;

import android.content.pm.ApplicationInfo;

/** Stub of the legacy Xposed API holder for the information of the loaded package. */
public final class XC_LoadPackage {

    private XC_LoadPackage() {
    }

    public static final class LoadPackageParam {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public ApplicationInfo appInfo;
        public boolean isFirstApplication;
    }
}
