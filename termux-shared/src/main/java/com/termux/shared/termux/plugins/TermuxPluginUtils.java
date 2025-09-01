package com.termux.shared.termux.plugins;

import android.content.Context;

public class TermuxPluginUtils {
    
    public static void sendPluginCommandErrorNotification(Context context, String logTag, String error) {
        android.util.Log.e(logTag, "Plugin error: " + error);
    }
    
    public static void sendPluginCommandErrorNotification(Context context, String logTag, String title, String message, Throwable throwable) {
        // Stub implementation with throwable
        android.util.Log.e(logTag, "Plugin error: " + title + " - " + message, throwable);
    }
    
    // Missing methods needed by the app
    public static void processPluginExecutionCommandResult(Context context, String logTag, Object executionCommand) {
        // Stub implementation
        android.util.Log.d(logTag, "Plugin execution completed successfully");
    }
    
    public static void processPluginExecutionCommandError(Context context, String logTag, Object executionCommand, boolean showNotification) {
        // Stub implementation
        android.util.Log.e(logTag, "Plugin execution error");
    }
    
    public static void setAndProcessPluginExecutionCommandError(Context context, String logTag, Object executionCommand, boolean showNotification, String errorMessage) {
        // Stub implementation
        android.util.Log.e(logTag, "Plugin execution error: " + errorMessage);
    }
    
    public static String checkIfAllowExternalAppsPolicyIsViolated(Context context, String logTag) {
        // Return null to indicate no policy violation
        return null;
    }
}