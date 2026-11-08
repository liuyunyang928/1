package de.robv.android.xposed;

/**
 * Implemented by the module entry point. LSPosed instantiates the class listed in
 * {@code assets/xposed_init} and calls {@link #handleLoadPackage} in every process that is
 * included in the module's scope.
 */
public interface IXposedHookLoadPackage {

    void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable;
}
