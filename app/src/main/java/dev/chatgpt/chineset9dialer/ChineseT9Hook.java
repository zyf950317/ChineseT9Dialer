package dev.chatgpt.chineset9dialer;

import android.content.Context;
import android.icu.text.Transliterator;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class ChineseT9Hook implements IXposedHookLoadPackage {
    private static final String TAG = "ChineseT9";
    private static final String TARGET = "com.android.dialer";
    private static final String DATABASE_HELPER_CLASS =
            "com.android.dialer.database.DialerDatabaseHelper";
    private static final String PREFIX_CLASS =
            "com.android.dialer.smartdial.util.SmartDialPrefix";
    private static final Set<Method> HOOKED_METHODS = new HashSet<>();

    private static void diag(String message) {
        XposedBridge.log("[ChineseT9] " + message);
        Log.i(TAG, message);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET.equals(lpparam.packageName)) return;

        try {
            Class<?> prefixClass = XposedHelpers.findClass(PREFIX_CLASS, lpparam.classLoader);
            Method original = prefixClass.getDeclaredMethod(
                    "generateNamePrefixes", Context.class, String.class);
            original.setAccessible(true);

            XposedBridge.hookMethod(original, new XC_MethodHook() {
                @Override
                @SuppressWarnings({"rawtypes", "unchecked"})
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length < 2 ||
                                !(param.args[1] instanceof String)) {
                            return;
                        }

                        String name = (String) param.args[1];
                        if (!containsHan(name)) return;

                        Object result = param.getResult();
                        String resultType = result == null
                                ? "null" : result.getClass().getName();
                        diag("METHOD CALLED: name=[" + name + "], resultType=" +
                                resultType + ", originalResult=" + String.valueOf(result));
                        XposedBridge.log(Log.getStackTraceString(new Throwable(
                                "ChineseT9 generateNamePrefixes caller")));

                        if (!(param.args[0] instanceof Context)) {
                            diag("SKIP: first argument is not Context; actual=" +
                                    (param.args[0] == null ? "null" :
                                            param.args[0].getClass().getName()));
                            return;
                        }
                        if (!(result instanceof ArrayList)) {
                            diag("SKIP: original result is not ArrayList");
                            return;
                        }

                        ArrayList output = (ArrayList) result;
                        Set<String> variants = makePinyinVariants(name);
                        diag("PINYIN VARIANTS: " + variants);

                        for (String variant : variants) {
                            if (variant.isEmpty()) continue;
                            Object generated = XposedBridge.invokeOriginalMethod(
                                    original, null, new Object[]{param.args[0], variant});
                            diag("GENERATED for [" + variant + "]: " +
                                    String.valueOf(generated));

                            if (generated instanceof ArrayList) {
                                for (Object token : (ArrayList) generated) {
                                    if (token instanceof String && !output.contains(token)) {
                                        output.add(token);
                                    }
                                }
                            } else {
                                diag("WARNING: generated result is not ArrayList for [" +
                                        variant + "]");
                            }
                        }
                        diag("FINAL PREFIXES for [" + name + "]: " + output);
                    } catch (Throwable t) {
                        XposedBridge.log("[ChineseT9] ERROR while processing name: " +
                                Log.getStackTraceString(t));
                        Log.e(TAG, "Failed to add pinyin T9 tokens", t);
                    }
                }
            });
            diag("Hook installed for " + PREFIX_CLASS + ".generateNamePrefixes");
        } catch (Throwable t) {
            XposedBridge.log("[ChineseT9] ERROR installing hook: " +
                    Log.getStackTraceString(t));
            Log.e(TAG, "Unable to install hook; dialer build may be incompatible", t);
        }

        installDatabaseSearchHooks(lpparam.classLoader);
    }

    private static void installDatabaseSearchHooks(ClassLoader classLoader) {
        try {
            Class<?> helperClass = Class.forName(
                    DATABASE_HELPER_CLASS, false, classLoader);
            int found = 0;
            for (Method method : helperClass.getDeclaredMethods()) {
                if (!"getLooseMatches".equals(method.getName())) continue;
                found++;
                try {
                    boolean hooked = hookOnce(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object[] args = param.args;
                                String query = args != null && args.length > 0
                                        ? String.valueOf(args[0]) : "<missing>";
                                Object matcher = args != null && args.length > 1
                                        ? args[1] : null;
                                String matcherType = matcher == null
                                        ? "<null>" : matcher.getClass().getName();
                                searchDiag("GET_LOOSE_MATCHES",
                                        "BEFORE signature=" + method.toGenericString() +
                                                ", parameterCount=" + method.getParameterTypes().length +
                                                ", query=[" + query + "], matcherType=" + matcherType);
                                if (matcher != null) installMatcherHook(matcher.getClass());
                            } catch (Throwable t) {
                                searchError("GET_LOOSE_MATCHES",
                                        "Error in beforeHookedMethod", t);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Throwable failure = param.getThrowable();
                                Object result = failure == null ? param.getResult() : null;
                                String returnType = result == null
                                        ? "null" : result.getClass().getName();
                                String count = result instanceof Collection
                                        ? String.valueOf(((Collection<?>) result).size())
                                        : "unavailable";
                                searchDiag("RESULTS", "signature=" + method.toGenericString() +
                                        ", returnType=" + returnType + ", collectionCount=" + count);

                                if (failure != null) {
                                    searchError("RESULTS", "getLooseMatches threw", failure);
                                    return;
                                }
                                if (result instanceof Collection) {
                                    for (Object contact : (Collection<?>) result) {
                                        String displayName = diagnosticProperty(contact, "displayName");
                                        String id = diagnosticProperty(contact, "id");
                                        searchDiag("RESULTS", "contact displayName=[" +
                                                displayName + "], id=[" + id + "]");
                                    }
                                }
                            } catch (Throwable t) {
                                searchError("RESULTS", "Error reading getLooseMatches result", t);
                            }
                        }
                    });
                    if (hooked) {
                        searchDiag("GET_LOOSE_MATCHES", "Hooked " + method.toGenericString());
                    }
                } catch (Throwable t) {
                    searchError("GET_LOOSE_MATCHES",
                            "Unable to hook " + method.toGenericString(), t);
                }
            }
            if (found == 0) {
                searchDiag("GET_LOOSE_MATCHES", "No declared getLooseMatches methods in " +
                        DATABASE_HELPER_CLASS);
            }
        } catch (Throwable t) {
            searchError("GET_LOOSE_MATCHES", "Unable to load " +
                    DATABASE_HELPER_CLASS + " with Dialer ClassLoader", t);
        }
    }

    private static void installMatcherHook(Class<?> matcherClass) {
        boolean found = false;
        try {
            for (Class<?> current = matcherClass; current != null; current = current.getSuperclass()) {
                for (Method method : current.getDeclaredMethods()) {
                    Class<?>[] parameterTypes = method.getParameterTypes();
                    if (!"matches".equals(method.getName()) ||
                            parameterTypes.length < 1 || parameterTypes.length > 2 ||
                            parameterTypes[parameterTypes.length - 1] != String.class ||
                            (parameterTypes.length == 2 &&
                                    !Context.class.isAssignableFrom(parameterTypes[0]))) {
                        continue;
                    }
                    found = true;
                    final int nameArgumentIndex = parameterTypes.length - 1;
                    try {
                        boolean hooked = hookOnce(method, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                try {
                                    String name = param.args != null &&
                                            param.args.length > nameArgumentIndex
                                            ? String.valueOf(param.args[nameArgumentIndex]) : "<missing>";
                                    String actualType = param.thisObject == null
                                            ? matcherClass.getName()
                                            : param.thisObject.getClass().getName();
                                    searchDiag("MATCHER", "BEFORE name=[" + name +
                                            "], signature=" + method.toGenericString() +
                                            ", matcherType=" + actualType);
                                } catch (Throwable t) {
                                    searchError("MATCHER", "Error in matches before hook", t);
                                }
                            }

                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    String name = param.args != null &&
                                            param.args.length > nameArgumentIndex
                                            ? String.valueOf(param.args[nameArgumentIndex]) : "<missing>";
                                    String actualType = param.thisObject == null
                                            ? matcherClass.getName()
                                            : param.thisObject.getClass().getName();
                                    Throwable failure = param.getThrowable();
                                    if (failure != null) {
                                        searchDiag("MATCHER", "AFTER name=[" + name +
                                                "], returned=<threw " + failure.getClass().getName() +
                                                ">, signature=" + method.toGenericString() +
                                                ", matcherType=" + actualType);
                                        searchError("MATCHER", "matches threw for name=[" + name +
                                                "], signature=" + method.toGenericString() +
                                                ", matcherType=" + actualType, failure);
                                        return;
                                    }
                                    Object result = param.getResult();
                                    searchDiag("MATCHER", "AFTER name=[" + name +
                                            "], returned=" + String.valueOf(result) +
                                            ", signature=" + method.toGenericString() +
                                            ", matcherType=" + actualType);
                                } catch (Throwable t) {
                                    searchError("MATCHER", "Error in matches after hook", t);
                                }
                            }
                        });
                        if (hooked) {
                            searchDiag("MATCHER", "Hooked " + method.toGenericString() +
                                    " on runtime type " + matcherClass.getName());
                        }
                    } catch (Throwable t) {
                        searchError("MATCHER", "Unable to hook " + method.toGenericString(), t);
                    }
                }
            }
            if (!found) {
                searchDiag("MATCHER", "No compatible matches(String) or " +
                        "matches(Context, String) method found in runtime type " +
                        matcherClass.getName());
            }
        } catch (Throwable t) {
            searchError("MATCHER", "Unable to inspect runtime type " +
                    matcherClass.getName(), t);
        }
    }

    private static boolean hookOnce(Method method, XC_MethodHook callback) {
        synchronized (HOOKED_METHODS) {
            if (HOOKED_METHODS.contains(method)) return false;
            method.setAccessible(true);
            XposedBridge.hookMethod(method, callback);
            HOOKED_METHODS.add(method);
            return true;
        }
    }

    private static String diagnosticProperty(Object target, String property) {
        if (target == null) return "<null>";
        String suffix = Character.toUpperCase(property.charAt(0)) + property.substring(1);
        String[] getterNames = {"get" + suffix, property};
        for (Class<?> current = target.getClass(); current != null; current = current.getSuperclass()) {
            for (String getterName : getterNames) {
                try {
                    Method getter = current.getDeclaredMethod(getterName);
                    getter.setAccessible(true);
                    Object value = getter.invoke(target);
                    return value == null ? "null" : String.valueOf(value);
                } catch (NoSuchMethodException ignored) {
                    // Try another accessor or the backing field.
                } catch (Throwable t) {
                    searchError("RESULTS", "Unable to read " + property +
                            " through " + getterName, t);
                }
            }
            try {
                Field field = current.getDeclaredField(property);
                field.setAccessible(true);
                Object value = field.get(target);
                return value == null ? "null" : String.valueOf(value);
            } catch (NoSuchFieldException ignored) {
                // Continue through the class hierarchy.
            } catch (Throwable t) {
                searchError("RESULTS", "Unable to read field " + property, t);
            }
        }
        return "<unavailable>";
    }

    private static void searchDiag(String section, String message) {
        diag("[SEARCH] " + section + " " + message);
    }

    private static void searchError(String section, String message, Throwable t) {
        searchDiag(section, message + "\n" + Log.getStackTraceString(t));
    }

    private static boolean containsHan(String s) {
        for (int i = 0; i < s.length();) {
            int cp = s.codePointAt(i);
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    private static Set<String> makePinyinVariants(String name) {
        Set<String> result = new LinkedHashSet<>();
        try {
            // ICU is part of Android; no third-party transliteration library is required.
            Transliterator tr = Transliterator.getInstance("Han-Latin; Latin-ASCII");
            String latin = tr.transliterate(name).toLowerCase(Locale.ROOT);
            String[] words = latin.split("[^a-z0-9]+");

            StringBuilder full = new StringBuilder();
            StringBuilder initials = new StringBuilder();
            for (String word : words) {
                if (word.isEmpty()) continue;
                full.append(word);
                initials.append(word.charAt(0));
            }
            if (full.length() > 0) result.add(full.toString());
            if (initials.length() > 0) result.add(initials.toString());

            String spaced = latin.replaceAll("[^a-z0-9]+", " ").trim();
            if (!spaced.isEmpty()) result.add(spaced);
        } catch (Throwable t) {
            XposedBridge.log("[ChineseT9] ICU transliteration error: " +
                    Log.getStackTraceString(t));
            Log.e(TAG, "ICU pinyin transliteration failed", t);
        }
        return result;
    }
}
