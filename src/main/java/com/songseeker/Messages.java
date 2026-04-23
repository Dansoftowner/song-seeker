package com.songseeker;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.ResourceBundle;

public final class Messages {
    private static final String BASE_NAME = "i18n.messages";

    private Messages() {
    }

    public static String get(String key) {
        return bundle().getString(key);
    }

    public static String format(String key, Object... arguments) {
        return MessageFormat.format(get(key), arguments);
    }

    private static ResourceBundle bundle() {
        return ResourceBundle.getBundle(BASE_NAME, Locale.getDefault());
    }
}
