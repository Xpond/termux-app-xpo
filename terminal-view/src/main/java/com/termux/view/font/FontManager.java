package com.termux.view.font;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Font management for xport Terminal
 * 
 * Provides functionality to get and set terminal font variants
 * with persistent storage and runtime modification support.
 */
public class FontManager {
    private static final String TAG = "FontManager";
    private static final String FONT_FILE = "/data/data/com.xport.terminal/files/home/.font";
    
    // Available font variants
    public static final String GEIST_MONO_REGULAR = "geistmono-regular";
    public static final String GEIST_MONO_BOLD = "geistmono-bold";
    public static final String GEIST_MONO_ITALIC = "geistmono-italic";
    public static final String INTER_REGULAR = "inter-regular";
    public static final String INTER_BOLD = "inter-bold";
    public static final String INTER_ITALIC = "inter-italic";
    
    // Default font
    private static final String DEFAULT_FONT = GEIST_MONO_REGULAR;
    
    /**
     * Set font variant
     * @param fontName Font variant name (e.g., "geistmono-regular", "geistmono-bold", "geistmono-italic")
     * @return true if font was set successfully
     */
    public static boolean setFont(String fontName) {
        if (!isValidFont(fontName)) {
            Log.e(TAG, "Invalid font variant: " + fontName);
            return false;
        }
        
        try {
            // Write font name to file
            File fontFile = new File(FONT_FILE);
            fontFile.getParentFile().mkdirs();
            
            try (FileOutputStream fos = new FileOutputStream(fontFile)) {
                fos.write(fontName.getBytes());
            }
            
            Log.i(TAG, "Font variant set to: " + fontName);
            return true;
            
        } catch (IOException e) {
            Log.e(TAG, "Failed to save font variant", e);
            return false;
        }
    }
    
    /**
     * Get current font variant
     * @return current font variant name
     */
    public static String getFont() {
        try {
            File fontFile = new File(FONT_FILE);
            if (!fontFile.exists()) {
                return DEFAULT_FONT;
            }
            
            try (InputStream is = new FileInputStream(fontFile)) {
                byte[] buffer = new byte[32];
                int bytesRead = is.read(buffer);
                if (bytesRead > 0) {
                    String fontName = new String(buffer, 0, bytesRead).trim();
                    return isValidFont(fontName) ? fontName : DEFAULT_FONT;
                }
            }
            
        } catch (Exception e) {
            Log.w(TAG, "Failed to read font variant, using default", e);
        }
        
        return DEFAULT_FONT;
    }
    
    /**
     * Get current font display name
     * @return human-readable font name
     */
    public static String getCurrentFontDisplay() {
        String font = getFont();
        switch (font) {
            case GEIST_MONO_REGULAR:
                return "Geist Mono Regular";
            case GEIST_MONO_BOLD:
                return "Geist Mono Bold";
            case GEIST_MONO_ITALIC:
                return "Geist Mono Italic";
            case INTER_REGULAR:
                return "Inter Regular";
            case INTER_BOLD:
                return "Inter Bold";
            case INTER_ITALIC:
                return "Inter Italic";
            default:
                return "Geist Mono Regular";
        }
    }
    
    /**
     * Check if font variant is valid
     * @param fontName Font variant name to validate
     * @return true if valid font variant
     */
    public static boolean isValidFont(String fontName) {
        if (fontName == null || fontName.trim().isEmpty()) {
            return false;
        }
        
        String font = fontName.trim().toLowerCase();
        return GEIST_MONO_REGULAR.equals(font) ||
               GEIST_MONO_BOLD.equals(font) ||
               GEIST_MONO_ITALIC.equals(font) ||
               INTER_REGULAR.equals(font) ||
               INTER_BOLD.equals(font) ||
               INTER_ITALIC.equals(font);
    }
    
    /**
     * Get asset path for current font
     * @return asset path for the current font file
     */
    public static String getCurrentFontAssetPath() {
        return getFontAssetPath(getFont());
    }
    
    /**
     * Get asset path for specified font variant
     * @param fontName Font variant name
     * @return asset path for the font file
     */
    public static String getFontAssetPath(String fontName) {
        if (!isValidFont(fontName)) {
            fontName = DEFAULT_FONT;
        }
        
        switch (fontName) {
            case GEIST_MONO_REGULAR:
                return "fonts/GeistMono-Regular.ttf";
            case GEIST_MONO_BOLD:
                return "fonts/GeistMono-Bold.ttf";
            case GEIST_MONO_ITALIC:
                return "fonts/GeistMono-Italic.ttf";
            case INTER_REGULAR:
                return "fonts/Inter-Regular.ttf";
            case INTER_BOLD:
                return "fonts/Inter-Bold.ttf";
            case INTER_ITALIC:
                return "fonts/Inter-Italic.ttf";
            default:
                return "fonts/GeistMono-Regular.ttf";
        }
    }
    
    /**
     * Get all available font variants
     * @return array of available font variant names
     */
    public static String[] getAvailableFonts() {
        return new String[]{
            GEIST_MONO_REGULAR,
            GEIST_MONO_BOLD,
            GEIST_MONO_ITALIC,
            INTER_REGULAR,
            INTER_BOLD,
            INTER_ITALIC
        };
    }
    
    /**
     * Get display names for all available font variants
     * @return array of human-readable font names
     */
    public static String[] getAvailableFontDisplayNames() {
        return new String[]{
            "Geist Mono Regular",
            "Geist Mono Bold", 
            "Geist Mono Italic",
            "Inter Regular",
            "Inter Bold",
            "Inter Italic"
        };
    }
}