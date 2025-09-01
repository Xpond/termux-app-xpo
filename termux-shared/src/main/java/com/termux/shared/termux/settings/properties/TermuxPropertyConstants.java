package com.termux.shared.termux.settings.properties;

import java.util.HashMap;
import java.util.Map;

public class TermuxPropertyConstants {
    
    // Property constants for Termux configuration
    public static final String KEY_DEFAULT_WORKING_DIRECTORY = "default-working-directory";
    public static final String KEY_NIGHT_MODE = "night-mode";
    public static final String KEY_EXTRA_KEYS = "extra-keys";
    public static final String KEY_EXTRA_KEYS_STYLE = "extra-keys-style";
    
    // Action shortcuts (int values for switch statements)
    public static final int ACTION_SHORTCUT_CREATE_SESSION = 1;
    public static final int ACTION_SHORTCUT_NEXT_SESSION = 2;
    public static final int ACTION_SHORTCUT_PREVIOUS_SESSION = 3;
    public static final int ACTION_SHORTCUT_RENAME_SESSION = 4;
    
    // Bell behavior values
    public static final int IVALUE_BELL_BEHAVIOUR_VIBRATE = 1;
    public static final int IVALUE_BELL_BEHAVIOUR_BEEP = 2;
    public static final int IVALUE_BELL_BEHAVIOUR_IGNORE = 3;
    
    // Default values
    public static final String DEFAULT_WORKING_DIRECTORY = "/data/data/com.xport.terminal/files/home";
    public static final String DEFAULT_NIGHT_MODE = "auto";
    public static final String DEFAULT_IVALUE_EXTRA_KEYS = "[[['ESC','/','-','HOME','UP','END','PGUP'],['TAB','CTRL','ALT','LEFT','DOWN','RIGHT','PGDN']]]";
    public static final String DEFAULT_IVALUE_EXTRA_KEYS_STYLE = "default";
    
    // Session shortcuts map
    public static final Map<String, Integer> MAP_SESSION_SHORTCUTS = new HashMap<>();
    static {
        MAP_SESSION_SHORTCUTS.put("create-session", ACTION_SHORTCUT_CREATE_SESSION);
        MAP_SESSION_SHORTCUTS.put("next-session", ACTION_SHORTCUT_NEXT_SESSION);
        MAP_SESSION_SHORTCUTS.put("previous-session", ACTION_SHORTCUT_PREVIOUS_SESSION);
        MAP_SESSION_SHORTCUTS.put("rename-session", ACTION_SHORTCUT_RENAME_SESSION);
    }
}