package dev.chatgpt.chineset9dialer;

import android.content.Context;
import android.icu.text.Transliterator;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
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
    private static final String PREFIX_CLASS =
            "com.android.dialer.smartdial.util.SmartDialPrefix";

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
