package com.xport.terminal;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

/**
 * XPort Minimal Bootstrap Manager
 * 
 * This class manages the installation and initialization of the minimal bootstrap
 * system that provides essential SSH functionality with minimal size footprint.
 */
public class XPortBootstrap {
    private static final String TAG = "XPortBootstrap";
    
    // Native library loading state
    private static boolean sNativeLibraryLoaded = false;
    
    // Load native library (called on-demand)
    private static void loadNativeLibrary() {
        if (!sNativeLibraryLoaded) {
            try {
                System.loadLibrary("xport-bootstrap");
                sNativeLibraryLoaded = true;
                Log.i(TAG, "Native bootstrap library loaded successfully");
            } catch (UnsatisfiedLinkError e) {
                Log.e(TAG, "Failed to load native bootstrap library", e);
            }
        }
    }
    
    // Native method declarations
    private static native boolean installBootstrap(AssetManager assetManager);
    private static native String getBootstrapInfo();
    private static native boolean isBootstrapInstalled();
    
    /**
     * Install the minimal bootstrap if not already installed
     * 
     * @param context Application context
     * @return true if bootstrap is installed successfully, false otherwise
     */
    public static boolean ensureBootstrapInstalled(Context context) {
        try {
            Log.i(TAG, "Checking XPort bootstrap installation...");
            
            // Load native library first
            loadNativeLibrary();
            if (!sNativeLibraryLoaded) {
                Log.e(TAG, "Cannot install bootstrap - native library failed to load");
                return false;
            }
            
            // Check if already installed
            // TEMP: Force reinstall to debug bootstrap extraction issue
            // if (isBootstrapInstalled()) {
            //     Log.i(TAG, "Bootstrap already installed");
            //     return true;
            // }
            Log.i(TAG, "Forcing bootstrap installation for debugging");
            
            Log.i(TAG, "Installing XPort minimal bootstrap...");
            
            // Get asset manager
            AssetManager assetManager = context.getAssets();
            
            // Install bootstrap
            boolean success = installBootstrap(assetManager);
            
            if (success) {
                Log.i(TAG, "Bootstrap installation completed successfully");
                
                // Log bootstrap information
                String info = getBootstrapInfo();
                Log.i(TAG, "Bootstrap info: " + info);
            } else {
                Log.e(TAG, "Bootstrap installation failed");
            }
            
            return success;
            
        } catch (Exception e) {
            Log.e(TAG, "Exception during bootstrap installation", e);
            return false;
        }
    }
    
    /**
     * Check if the bootstrap is installed
     * 
     * @return true if bootstrap is installed, false otherwise
     */
    public static boolean isInstalled() {
        try {
            loadNativeLibrary();
            if (!sNativeLibraryLoaded) {
                return false;
            }
            return isBootstrapInstalled();
        } catch (Exception e) {
            Log.e(TAG, "Error checking bootstrap installation status", e);
            return false;
        }
    }
    
    /**
     * Get bootstrap information string
     * 
     * @return Bootstrap information including version, architecture, etc.
     */
    public static String getInfo() {
        try {
            loadNativeLibrary();
            if (!sNativeLibraryLoaded) {
                return "Bootstrap info unavailable - native library not loaded";
            }
            return getBootstrapInfo();
        } catch (Exception e) {
            Log.e(TAG, "Error getting bootstrap information", e);
            return "Bootstrap info unavailable";
        }
    }
    
    /**
     * Get the bootstrap prefix directory path
     * 
     * @return Bootstrap prefix directory path
     */
    public static String getBootstrapPrefix() {
        return "/data/data/com.xport.terminal/files/usr";
    }
    
    /**
     * Get the bootstrap home directory path
     * 
     * @return Bootstrap home directory path  
     */
    public static String getBootstrapHome() {
        return "/data/data/com.xport.terminal/files/home";
    }
    
    /**
     * Get the bootstrap temp directory path
     * 
     * @return Bootstrap temp directory path
     */
    public static String getBootstrapTmp() {
        return "/data/data/com.xport.terminal/files/tmp";
    }
    
    /**
     * Get the shell executable path
     * 
     * @return Path to the shell executable
     */
    public static String getShellPath() {
        return getBootstrapPrefix() + "/bin/sh";
    }
    
    /**
     * Get the SSH client executable path
     * 
     * @return Path to the SSH client executable
     */
    public static String getSshPath() {
        return getBootstrapPrefix() + "/bin/ssh";
    }
    
    /**
     * Get the SSH keygen executable path
     * 
     * @return Path to the SSH keygen executable
     */
    public static String getSshKeygenPath() {
        return getBootstrapPrefix() + "/bin/ssh-keygen";
    }
}