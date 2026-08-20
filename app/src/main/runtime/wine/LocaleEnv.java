package com.winlator.cmod.runtime.wine;

import java.util.Locale;

/* Guest locale 环境归一化。
 *
 * imagefs 不携带任何 glibc locale 数据，请求真实 locale（如 zh_CN.UTF-8）会让
 * setlocale() 失败并静默回退到纯 "C"（ASCII-only 字符集），wine 命令行上的
 * 非 ASCII 路径/文件名（快捷方式名、游戏目录、exe 路径）随之全部失效。
 *
 * 因此 LC_ALL 固定为 glibc 内建的 C.UTF-8（无需 locale 数据）；用户原始
 * locale（容器/快捷方式存储值，为空则取设备 locale）改经 LANG 导出。 */
public final class LocaleEnv {
    private LocaleEnv() {}

    public static String normalize() {
        return "C.UTF-8";
    }

    public static String normalizeLang(String stored) {
        if (stored != null && !stored.isEmpty()) {
            return ensureEncoding(stored);
        }
        return deriveFromDevice();
    }

    public static String deriveFromDevice() {
        Locale locale = Locale.getDefault();
        String lang = locale.getLanguage();
        String country = locale.getCountry();
        if (country == null || country.isEmpty()) {
            country = defaultCountryFor(lang);
        }
        if (lang == null || lang.isEmpty() || country == null || country.isEmpty()) {
            return "C.UTF-8";
        }
        return lang + "_" + country + ".UTF-8";
    }

    private static String ensureEncoding(String value) {
        int dot = value.indexOf('.');
        if (dot >= 0) return value;
        return value + ".UTF-8";
    }

    private static String defaultCountryFor(String lang) {
        if (lang == null) return null;
        switch (lang) {
            case "en": return "US";
            case "da": return "DK";
            case "de": return "DE";
            case "es": return "ES";
            case "fr": return "FR";
            case "it": return "IT";
            case "ko": return "KR";
            case "pl": return "PL";
            case "pt": return "BR";
            case "ro": return "RO";
            case "uk": return "UA";
            case "ja": return "JP";
            case "ru": return "RU";
            case "ar": return "EG";
            case "zh": return "CN";
            default: return null;
        }
    }
}
