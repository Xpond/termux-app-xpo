package com.xport.terminal;

import android.content.Context;
import android.content.res.AssetManager;
// Removed Log import for production

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * XPort Minimal Bootstrap Manager
 * 
 * This class manages the installation and initialization of the minimal bootstrap
 * system that provides essential SSH functionality with minimal size footprint.
 */
public class XPortBootstrap {
    // Removed TAG for production build
    
    // Native library loading state
    private static boolean sNativeLibraryLoaded = false;
    
    // Load native library (called on-demand)
    private static void loadNativeLibrary() {
        if (!sNativeLibraryLoaded) {
            try {
                System.loadLibrary("xport-bootstrap");
                sNativeLibraryLoaded = true;
                // Log removed for production
            } catch (UnsatisfiedLinkError e) {
                // Log removed for production
            }
        }
    }
    
    // Native method declarations
    private static native boolean installBootstrap(AssetManager assetManager);
    private static native String getBootstrapInfo();
    private static native boolean isBootstrapInstalled();
    
    /**
     * Ensure critical binaries have execute permissions
     */
    private static void ensureBinaryPermissions() {
        String[] criticalBinaries = {
            "toybox", "ssh", "dbclient", "dropbearkey", "scp", "sh", "fontsize"
        };
        
        String binDir = getBootstrapPrefix() + "/bin";
        
        for (String binary : criticalBinaries) {
            File binaryFile = new File(binDir, binary);
            if (binaryFile.exists() && !binaryFile.canExecute()) {
                boolean execSet = binaryFile.setExecutable(true, false);

            }
        }
    }
    
    /**
     * Extract ZIP asset to destination directory using Java
     */
    private static boolean extractZipAsset(AssetManager assetManager, String assetName, String destDir) {

        
        try {
            // Create destination directory
            File destDirFile = new File(destDir);
            if (!destDirFile.exists() && !destDirFile.mkdirs()) {

                return false;
            }
            
            // Open ZIP asset
            InputStream assetStream = assetManager.open(assetName);
            ZipInputStream zipStream = new ZipInputStream(assetStream);
            
            byte[] buffer = new byte[8192];
            ZipEntry entry;
            int extractedFiles = 0;
            
            while ((entry = zipStream.getNextEntry()) != null) {
                String fileName = entry.getName();
                File outputFile = new File(destDir, fileName);
                
                if (entry.isDirectory()) {
                    // Create directory
                    if (!outputFile.exists() && !outputFile.mkdirs()) {
                        // Log removed for production
                    }
                } else {
                    // Create parent directories if needed
                    File parentDir = outputFile.getParentFile();
                    if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs()) {
                        // Log removed for production
                    }
                    
                    // Extract file
                    try (FileOutputStream outputStream = new FileOutputStream(outputFile)) {
                        int bytesRead;
                        while ((bytesRead = zipStream.read(buffer)) != -1) {
                            outputStream.write(buffer, 0, bytesRead);
                        }
                    }
                    
                    // Set execute permissions for binaries
                    if (fileName.startsWith("bin/") && !fileName.endsWith("/")) {
                        boolean execSet = outputFile.setExecutable(true, false);

                    }
                    
                    extractedFiles++;

                }
                
                zipStream.closeEntry();
            }
            
            zipStream.close();
            assetStream.close();
            

            return true;
            
        } catch (IOException e) {

            return false;
        }
    }

    /**
     * Install the minimal bootstrap if not already installed
     * 
     * @param context Application context
     * @return true if bootstrap is installed successfully, false otherwise
     */
    public static boolean ensureBootstrapInstalled(Context context) {
        try {

            
            // Load native library first
            loadNativeLibrary();
            if (!sNativeLibraryLoaded) {

                return false;
            }
            
            // Check if already installed
            if (isBootstrapInstalled()) {

                return true;
            }
            

            
            // Get asset manager
            AssetManager assetManager = context.getAssets();
            
            // Get architecture
            String arch = getArchitecture();
            String bootstrapZip = "xport-bootstrap-" + arch + ".zip";
            
            // Extract bootstrap ZIP using Java instead of native unzip
            String bootstrapPrefix = getBootstrapPrefix();
            if (!extractZipAsset(assetManager, bootstrapZip, bootstrapPrefix)) {

                return false;
            }
            
            // Ensure critical binaries have execute permissions (double-check)
            ensureBinaryPermissions();
            
            // Install bootstrap (setup permissions and config)
            boolean success = installBootstrap(assetManager);
            
            if (success) {

                
                // Final permissions check
                ensureBinaryPermissions();
                
                // Log bootstrap information
                String info = getBootstrapInfo();

            } else {

            }
            
            return success;
            
        } catch (Exception e) {

            return false;
        }
    }
    
    /**
     * Get current Android architecture
     */
    private static String getArchitecture() {
        String abi = android.os.Build.SUPPORTED_ABIS[0];
        switch (abi) {
            case "arm64-v8a":
                return "arm64-v8a";
            case "armeabi-v7a":
                return "armeabi-v7a";
            case "x86_64":
                return "x86_64";
            case "x86":
                return "x86";
            default:
                // Log removed for production
                return "arm64-v8a";
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
    
}