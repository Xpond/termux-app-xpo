package com.termux.shared.termux.settings.properties;

import android.content.Context;

public class TermuxSharedProperties {
    
    public static final String LOG_TAG = "TermuxSharedProperties";
    
    public static String getNightMode(Context context) {
        return TermuxPropertyConstants.DEFAULT_NIGHT_MODE;
    }
    
    public static String getDefaultWorkingDirectory(Context context) {
        return TermuxPropertyConstants.DEFAULT_WORKING_DIRECTORY;
    }
}