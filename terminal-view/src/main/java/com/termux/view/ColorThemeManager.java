package com.termux.view;

import android.graphics.Color;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Color theme management for XPort Terminal
 * 
 * Provides functionality to get and set terminal text and background colors
 * with persistent storage and runtime modification support.
 */
public class ColorThemeManager {
    private static final String TAG = "ColorThemeManager";
    private static final String COLOR_CONFIG_FILE = "/data/data/com.xport.terminal/files/home/.xport_colors";
    
    // Default colors (with explicit ARGB values for consistency)
    private static final int DEFAULT_TEXT_COLOR = 0xFFFFFFFF; // White with full alpha
    private static final int DEFAULT_BACKGROUND_COLOR = 0xFF000000; // Black with full alpha
    
    // Color keys for properties file
    private static final String TEXT_COLOR_KEY = "text_color";
    private static final String BACKGROUND_COLOR_KEY = "background_color";
    
    /**
     * Set terminal text color
     * @param colorHex Color in hex format (e.g., "#FF0000" or "FF0000")
     * @return true if color was set successfully
     */
    public static boolean setTextColor(String colorHex) {
        try {
            int color = parseColorHex(colorHex);
            return setTextColor(color);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Invalid text color hex: " + colorHex, e);
            return false;
        }
    }
    
    /**
     * Set terminal text color
     * @param color Color as integer (ARGB format)
     * @return true if color was set successfully
     */
    public static boolean setTextColor(int color) {
        return saveColorProperty(TEXT_COLOR_KEY, color);
    }
    
    /**
     * Set terminal background color
     * @param colorHex Color in hex format (e.g., "#000000" or "000000")
     * @return true if color was set successfully
     */
    public static boolean setBackgroundColor(String colorHex) {
        try {
            int color = parseColorHex(colorHex);
            return setBackgroundColor(color);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Invalid background color hex: " + colorHex, e);
            return false;
        }
    }
    
    /**
     * Set terminal background color
     * @param color Color as integer (ARGB format)
     * @return true if color was set successfully
     */
    public static boolean setBackgroundColor(int color) {
        return saveColorProperty(BACKGROUND_COLOR_KEY, color);
    }
    
    /**
     * Get current text color
     * @return text color as integer (ARGB format)
     */
    public static int getTextColor() {
        return getColorProperty(TEXT_COLOR_KEY, DEFAULT_TEXT_COLOR);
    }
    
    /**
     * Get current background color
     * @return background color as integer (ARGB format)
     */
    public static int getBackgroundColor() {
        return getColorProperty(BACKGROUND_COLOR_KEY, DEFAULT_BACKGROUND_COLOR);
    }
    
    /**
     * Get text color as hex string
     * @return hex color string (e.g., "#FFFFFF")
     */
    public static String getTextColorHex() {
        return colorToHex(getTextColor());
    }
    
    /**
     * Get background color as hex string
     * @return hex color string (e.g., "#000000")
     */
    public static String getBackgroundColorHex() {
        return colorToHex(getBackgroundColor());
    }
    
    /**
     * Reset colors to default
     * @return true if reset was successful
     */
    public static boolean resetToDefaults() {
        try {
            File configFile = new File(COLOR_CONFIG_FILE);
            if (configFile.exists()) {
                configFile.delete();
            }
            Log.i(TAG, "Colors reset to defaults");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to reset colors to defaults", e);
            return false;
        }
    }
    
    /**
     * Parse color hex string to integer
     * @param colorHex Color in hex format (with or without #)
     * @return color as integer
     * @throws IllegalArgumentException if hex format is invalid
     */
    private static int parseColorHex(String colorHex) throws IllegalArgumentException {
        if (colorHex == null || colorHex.trim().isEmpty()) {
            throw new IllegalArgumentException("Color hex cannot be null or empty");
        }
        
        String hex = colorHex.trim();
        // Remove # if present
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        }
        
        // Support both RGB (6 chars) and ARGB (8 chars)
        if (hex.length() == 6) {
            // RGB format - add full alpha
            return Color.parseColor("#FF" + hex);
        } else if (hex.length() == 8) {
            // ARGB format
            return Color.parseColor("#" + hex);
        } else {
            throw new IllegalArgumentException("Invalid hex color format: " + colorHex + " (expected 6 or 8 characters)");
        }
    }
    
    /**
     * Convert color integer to hex string
     * @param color Color as integer
     * @return hex color string with # prefix
     */
    private static String colorToHex(int color) {
        return String.format("#%08X", color);
    }
    
    /**
     * Save a color property to the config file
     */
    private static boolean saveColorProperty(String key, int color) {
        try {
            Properties props = loadProperties();
            props.setProperty(key, String.valueOf(color));
            
            File configFile = new File(COLOR_CONFIG_FILE);
            configFile.getParentFile().mkdirs();
            
            try (FileOutputStream fos = new FileOutputStream(configFile)) {
                props.store(fos, "XPort Terminal Color Configuration");
            }
            
            Log.i(TAG, "Color property saved: " + key + " = " + colorToHex(color));
            return true;
            
        } catch (IOException e) {
            Log.e(TAG, "Failed to save color property: " + key, e);
            return false;
        }
    }
    
    /**
     * Get a color property from the config file
     */
    private static int getColorProperty(String key, int defaultColor) {
        try {
            Properties props = loadProperties();
            String colorStr = props.getProperty(key);
            if (colorStr != null && !colorStr.trim().isEmpty()) {
                // Parse as unsigned integer to handle large ARGB values
                long colorLong = Long.parseLong(colorStr.trim());
                return (int) colorLong;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to read color property: " + key + ", using default", e);
        }
        return defaultColor;
    }
    
    /**
     * Load properties from config file
     */
    private static Properties loadProperties() {
        Properties props = new Properties();
        try {
            File configFile = new File(COLOR_CONFIG_FILE);
            if (configFile.exists()) {
                try (InputStream is = new FileInputStream(configFile)) {
                    props.load(is);
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to load color properties, using defaults", e);
        }
        return props;
    }
}