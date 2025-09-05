package com.termux.shared.termux.settings.preferences;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Font size management for XPort Terminal
 * 
 * Provides functionality to get and set font size scale (1-10)
 * and convert scales to actual pixel sizes.
 */
public class FontSizeManager {
    private static final String TAG = "FontSizeManager";
    private static final String FONTSIZE_FILE = "/data/data/com.xport.terminal/files/home/.fontsize";
    
    /**
     * Set font size scale (1-10)
     */
    public static boolean setScale(int scale) {
        if (scale < 1 || scale > 10) {
            Log.e(TAG, "Invalid font size scale: " + scale + " (must be 1-10)");
            return false;
        }
        
        try {
            // Write scale to file
            File scaleFile = new File(FONTSIZE_FILE);
            scaleFile.getParentFile().mkdirs();
            
            try (FileOutputStream fos = new FileOutputStream(scaleFile)) {
                fos.write(String.valueOf(scale).getBytes());
            }
            
            Log.i(TAG, "Font size scale set to: " + scale);
            return true;
            
        } catch (IOException e) {
            Log.e(TAG, "Failed to save font size scale", e);
            return false;
        }
    }
    
    /**
     * Get current font size scale (1-10)
     */
    public static int getScale() {
        try {
            File scaleFile = new File(FONTSIZE_FILE);
            if (!scaleFile.exists()) {
                return 5; // Default scale
            }
            
            try (InputStream is = new FileInputStream(scaleFile)) {
                byte[] buffer = new byte[2];
                int bytesRead = is.read(buffer);
                if (bytesRead > 0) {
                    String scaleStr = new String(buffer, 0, bytesRead).trim();
                    int scale = Integer.parseInt(scaleStr);
                    return (scale >= 1 && scale <= 10) ? scale : 5;
                }
            }
            
        } catch (Exception e) {
            Log.w(TAG, "Failed to read font size scale, using default", e);
        }
        
        return 5; // Default scale
    }
    
    /**
     * Convert scale (1-10) to actual pixel size
     */
    public static int scaleToPixels(int scale) {
        return 8 + (scale * 3); // Same formula as TermuxAppSharedPreferences
    }
    
    /**
     * Get current font size in pixels
     */
    public static int getCurrentPixelSize() {
        return scaleToPixels(getScale());
    }
}