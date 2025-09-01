package com.termux.shared.termux.settings.properties;

import android.content.Context;

public class TermuxAppSharedProperties {
    
    private static TermuxAppSharedProperties instance;
    
    public static TermuxAppSharedProperties getProperties() {
        if (instance == null) {
            instance = new TermuxAppSharedProperties();
        }
        return instance;
    }
    
    public boolean shouldRunTermuxAmSocketServer() {
        return false; // Disable by default for XPort
    }
    
    // Directory management methods
    public String getDefaultWorkingDirectory() {
        return "/data/data/com.xport.terminal/files/home";
    }
    
    // Cleanup methods - return 0 to disable cleanup by default
    public int getDeleteTMPDIRFilesOlderThanXDaysOnExit() {
        return 0; // Disable automatic cleanup for XPort
    }
    
    // Static initialization method
    public static TermuxAppSharedProperties init(Context context) {
        return getProperties();
    }
    
    // Missing methods needed by the app
    public String getNightMode() {
        return TermuxPropertyConstants.DEFAULT_NIGHT_MODE; // Default night mode
    }
    
    public void loadTermuxPropertiesFromDisk() {
        // Stub implementation
    }
    
    public boolean isUsingFullScreen() {
        return false; // Default fullscreen setting
    }
    
    public int getTerminalMarginHorizontal() {
        return 0; // Default horizontal margin
    }
    
    public int getTerminalMarginVertical() {
        return 0; // Default vertical margin  
    }
    
    public int getTerminalToolbarHeightScaleFactor() {
        return 1; // Default scale factor
    }
    
    public boolean shouldExtraKeysTextBeAllCaps() {
        return false; // Default extra keys setting
    }
    
    public int getTerminalTranscriptRows() {
        return 2000; // Default transcript rows
    }
    
    public boolean shouldOpenTerminalTranscriptURLOnClick() {
        return true; // Default URL click behavior
    }
    
    public boolean isBackKeyTheEscapeKey() {
        return false; // Default back key behavior
    }
    
    public boolean isEnforcingCharBasedInput() {
        return true; // Default input behavior
    }
    
    public boolean isUsingCtrlSpaceWorkaround() {
        return false; // Default ctrl space workaround
    }
    
    public boolean areHardwareKeyboardShortcutsDisabled() {
        return false; // Default hardware keyboard shortcuts
    }
    
    public boolean areVirtualVolumeKeysDisabled() {
        return false; // Default volume keys behavior
    }
    
    public Object getInternalPropertyValue(String key, boolean useDefaultValue) {
        // Return default values for common properties
        return null; // Stub implementation
    }
    
    public boolean shouldEnableDisableSoftKeyboardOnToggle() {
        return true; // Default keyboard toggle behavior
    }
    
    public boolean shouldSoftKeyboardBeHiddenOnStartup() {
        return false; // Default startup keyboard behavior
    }
    
    public int getTerminalCursorBlinkRate() {
        return 1000; // Default cursor blink rate in ms
    }
    
    public int getBellBehaviour() {
        return 1; // Default bell behavior (vibrate)
    }
    
    public int getTerminalCursorStyle() {
        return 0; // Default cursor style (block)
    }
    
    public boolean areTerminalSessionChangeToastsDisabled() {
        return false; // Default session change toasts
    }
    
    public boolean isUsingFullScreenWorkAround() {
        return false; // Default fullscreen workaround
    }
}