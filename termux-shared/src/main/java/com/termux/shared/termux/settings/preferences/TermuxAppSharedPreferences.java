package com.termux.shared.termux.settings.preferences;

import android.content.Context;

public class TermuxAppSharedPreferences {
    
    private int appShellNumber = 0;
    private int terminalSessionNumber = 0;
    
    public static TermuxAppSharedPreferences build(Context context) {
        return new TermuxAppSharedPreferences();
    }
    
    public static TermuxAppSharedPreferences build(Context context, boolean loadTermuxProperties) {
        return new TermuxAppSharedPreferences();
    }
    
    // Notification methods
    public int getLastNotificationId() {
        return TermuxPreferenceConstants.TERMUX_APP.DEFAULT_VALUE_KEY_LAST_NOTIFICATION_ID;
    }
    
    public void setLastNotificationId(int id) {
        // Stub implementation
    }
    
    // Crash report methods
    public boolean areCrashReportNotificationsEnabled(boolean defaultValue) {
        return defaultValue; // Use default value for XPort
    }
    
    // Shell number tracking methods
    public void resetAppShellNumberSinceBoot() {
        appShellNumber = 0;
    }
    
    public void resetTerminalSessionNumberSinceBoot() {
        terminalSessionNumber = 0;
    }
    
    public int getAndIncrementAppShellNumberSinceBoot() {
        return ++appShellNumber;
    }
    
    public int getAndIncrementTerminalSessionNumberSinceBoot() {
        return ++terminalSessionNumber;
    }
    
    // Missing methods needed by the app
    public void setLogLevel(Object context, int logLevel) {
        // Stub implementation
    }
    
    public int getLogLevel() {
        return 1; // Default log level
    }
    
    public boolean arePluginErrorNotificationsEnabled(boolean defaultValue) {
        return defaultValue;
    }
    
    public void setCurrentSession(String sessionHandle) {
        // Stub implementation
    }
    
    // Additional missing methods
    public boolean isTerminalMarginAdjustmentEnabled() {
        return true; // Default terminal margin adjustment
    }
    
    public boolean shouldShowTerminalToolbar() {
        return false; // Default toolbar visibility
    }
    
    public boolean toogleShowTerminalToolbar() {
        return true; // Return new state after toggle
    }
    
    public boolean shouldKeepScreenOn() {
        return false; // Default keep screen on
    }
    
    public void setKeepScreenOn(boolean keepScreenOn) {
        // Stub implementation
    }
    
    public int getFontSize() {
        return 14; // Default font size
    }
    
    public boolean isTerminalViewKeyLoggingEnabled() {
        return false; // Default key logging
    }
    
    public void changeFontSize(boolean increase) {
        // Stub implementation for font size change
    }
    
    public void setSoftKeyboardEnabled(boolean enabled) {
        // Stub implementation
    }
    
    public boolean isSoftKeyboardEnabled() {
        return true; // Default soft keyboard enabled
    }
    
    public boolean isSoftKeyboardEnabledOnlyIfNoHardware() {
        return false; // Default hardware keyboard setting
    }
    
    public String getCurrentSession() {
        return "default"; // Default session handle
    }
}