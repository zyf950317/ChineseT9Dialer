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
                        Object result = param.getResult();
                        if (!(result instanceof ArrayList)) return;
                        if (!(param.args[0] instanceof Context) ||
                                !(param.args[1] instanceof String)) return;

                        String name = (String) param.args[1];
                        if (!containsHan(name)) return;

                        ArrayList output = (ArrayList) result;
                        Set<String> variants = makePinyinVariants(name);
                        for (String variant : variants) {
                            if (variant.isEmpty()) continue;
                            Object generated = XposedBridge.invokeOriginalMethod(
                                    original, null, new Object[]{param.args[0], variant});
                            if (generated instanceof ArrayList) {
                                for (Object token : (ArrayList) generated) {
                                    if (token instanceof String && !output.contains(token)) {
                                        output.add(token);
                                    }
                                }
                            }
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "Failed to add pinyin T9 tokens", t);
                    }
                }
            });
            Log.i(TAG, "Hook installed for " + PREFIX_CLASS + ".generateNamePrefixes");
        } catch (Throwable t) {
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

            // Preserve word boundaries as another candidate for implementations that
            // index token boundaries separately.
            String spaced = latin.replaceAll("[^a-z0-9]+", " ").trim();
            if (!spaced.isEmpty()) result.add(spaced);
        } catch (Throwable t) {
            Log.e(TAG, "ICU pinyin transliteration failed", t);
        }
        return result;
    }
}
