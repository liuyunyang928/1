package de.robv.android.xposed;

import java.lang.reflect.Member;

/**
 * Stub of the legacy Xposed API method hook. Only the members used by this module are
 * declared; LSPosed provides the real implementation at runtime.
 */
public abstract class XC_MethodHook {

    public static final int PRIORITY_DEFAULT = 50;

    protected XC_MethodHook() {
    }

    protected XC_MethodHook(int priority) {
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    public static final class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;
        private Object result;
        private Throwable throwable;

        public Object getResult() {
            return result;
        }

        public void setResult(Object result) {
            this.result = result;
        }

        public Throwable getThrowable() {
            return throwable;
        }

        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
        }

        public Object getResultOrThrowable() throws Throwable {
            if (throwable != null) {
                throw throwable;
            }
            return result;
        }
    }

    public class Unhook {
        private final Member member;

        Unhook(Member member) {
            this.member = member;
        }

        public Member getHookedMethod() {
            return member;
        }

        public void unhook() {
        }
    }
}
