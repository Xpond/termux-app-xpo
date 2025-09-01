/**
 * XPort Minimal Bootstrap Loader
 * 
 * This file replaces termux-bootstrap.c with a minimal bootstrap loader
 * that extracts and sets up only essential SSH functionality components.
 * 
 * Key differences from Termux bootstrap:
 * - Much smaller bootstrap (~5-10MB vs 180MB+)
 * - Only essential components (BusyBox, OpenSSH, minimal libs)
 * - Simplified extraction and setup
 * - SSH-focused environment configuration
 */

#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <fcntl.h>
#include <errno.h>
#include <libgen.h>
#include <android/log.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>

#define LOG_TAG "XPortBootstrap"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// Bootstrap configuration
#define BOOTSTRAP_VERSION "1.0.0"
#define BOOTSTRAP_PREFIX_DIR "/data/data/com.xport.terminal/files/usr"
#define BOOTSTRAP_HOME_DIR "/data/data/com.xport.terminal/files/home"
#define BOOTSTRAP_TMP_DIR "/data/data/com.xport.terminal/files/tmp"

// Buffer sizes
#define BUFFER_SIZE 8192
#define PATH_MAX_LEN 1024

/**
 * Get the current Android architecture
 */
static const char* get_android_architecture() {
    #if defined(__aarch64__)
        return "arm64-v8a";
    #elif defined(__arm__)
        return "armeabi-v7a";
    #elif defined(__x86_64__)
        return "x86_64";
    #elif defined(__i386__)
        return "x86";
    #else
        return "unknown";
    #endif
}

/**
 * Create directory and all parent directories if they don't exist
 */
static int create_directory_recursive(const char* path, mode_t mode) {
    char* path_copy = strdup(path);
    if (!path_copy) {
        LOGE("Failed to allocate memory for path copy");
        return -1;
    }
    
    char* p = path_copy;
    
    // Skip leading slash
    if (*p == '/') p++;
    
    while (*p) {
        // Find next slash
        char* slash = strchr(p, '/');
        if (slash) *slash = '\0';
        
        // Create directory
        if (mkdir(path_copy, mode) != 0 && errno != EEXIST) {
            LOGE("Failed to create directory %s: %s", path_copy, strerror(errno));
            free(path_copy);
            return -1;
        }
        
        if (slash) {
            *slash = '/';
            p = slash + 1;
        } else {
            break;
        }
    }
    
    free(path_copy);
    return 0;
}

/**
 * Set permissions on a file
 */
static int set_file_permissions(const char* path, mode_t mode) {
    if (chmod(path, mode) != 0) {
        LOGE("Failed to set permissions on %s: %s", path, strerror(errno));
        return -1;
    }
    return 0;
}

/**
 * Extract a single file from Android assets to filesystem
 */
static int extract_asset_file(AAssetManager* asset_manager, const char* asset_path, const char* dest_path) {
    LOGD("Extracting %s to %s", asset_path, dest_path);
    
    // Open asset
    AAsset* asset = AAssetManager_open(asset_manager, asset_path, AASSET_MODE_STREAMING);
    if (!asset) {
        LOGE("Failed to open asset: %s", asset_path);
        return -1;
    }
    
    // Create destination directory
    char* dest_dir = strdup(dest_path);
    if (!dest_dir) {
        AAsset_close(asset);
        return -1;
    }
    
    char* dir_path = dirname(dest_dir);
    if (create_directory_recursive(dir_path, 0755) != 0) {
        LOGE("Failed to create destination directory: %s", dir_path);
        free(dest_dir);
        AAsset_close(asset);
        return -1;
    }
    free(dest_dir);
    
    // Open destination file
    int dest_fd = open(dest_path, O_CREAT | O_WRONLY | O_TRUNC, 0644);
    if (dest_fd < 0) {
        LOGE("Failed to create destination file %s: %s", dest_path, strerror(errno));
        AAsset_close(asset);
        return -1;
    }
    
    // Copy data
    char buffer[BUFFER_SIZE];
    int bytes_read;
    int total_bytes = 0;
    
    while ((bytes_read = AAsset_read(asset, buffer, sizeof(buffer))) > 0) {
        if (write(dest_fd, buffer, bytes_read) != bytes_read) {
            LOGE("Failed to write to destination file %s: %s", dest_path, strerror(errno));
            close(dest_fd);
            AAsset_close(asset);
            unlink(dest_path);
            return -1;
        }
        total_bytes += bytes_read;
    }
    
    close(dest_fd);
    AAsset_close(asset);
    
    LOGD("Extracted %d bytes from %s to %s", total_bytes, asset_path, dest_path);
    return 0;
}

/**
 * Extract bootstrap ZIP file using minimal unzip
 */
static int extract_bootstrap_zip(const char* zip_path, const char* dest_dir) {
    LOGI("Extracting bootstrap ZIP: %s to %s", zip_path, dest_dir);
    
    // Create destination directory
    if (create_directory_recursive(dest_dir, 0755) != 0) {
        return -1;
    }
    
    // Use busybox unzip if available, or implement simple ZIP extraction
    char unzip_cmd[PATH_MAX_LEN * 2];
    snprintf(unzip_cmd, sizeof(unzip_cmd), "cd \"%s\" && unzip -qq \"%s\" 2>/dev/null", dest_dir, zip_path);
    
    int result = system(unzip_cmd);
    if (result != 0) {
        LOGE("Failed to extract ZIP file: %s (exit code: %d)", zip_path, result);
        return -1;
    }
    
    LOGI("Bootstrap ZIP extracted successfully");
    return 0;
}

/**
 * Setup essential environment directories
 */
static int setup_bootstrap_directories() {
    LOGI("Setting up bootstrap directories");
    
    const char* directories[] = {
        BOOTSTRAP_PREFIX_DIR,
        BOOTSTRAP_PREFIX_DIR "/bin",
        BOOTSTRAP_PREFIX_DIR "/lib",
        BOOTSTRAP_PREFIX_DIR "/etc",
        BOOTSTRAP_PREFIX_DIR "/etc/ssh",
        BOOTSTRAP_PREFIX_DIR "/usr",
        BOOTSTRAP_PREFIX_DIR "/usr/share",
        BOOTSTRAP_PREFIX_DIR "/var",
        BOOTSTRAP_PREFIX_DIR "/var/run",
        BOOTSTRAP_PREFIX_DIR "/var/empty",
        BOOTSTRAP_HOME_DIR,
        BOOTSTRAP_HOME_DIR "/.ssh",
        BOOTSTRAP_TMP_DIR,
        NULL
    };
    
    for (int i = 0; directories[i] != NULL; i++) {
        if (create_directory_recursive(directories[i], 0755) != 0) {
            LOGE("Failed to create directory: %s", directories[i]);
            return -1;
        }
    }
    
    // Set special permissions for SSH directories
    set_file_permissions(BOOTSTRAP_HOME_DIR "/.ssh", 0700);
    set_file_permissions(BOOTSTRAP_PREFIX_DIR "/var/empty", 0755);
    
    LOGI("Bootstrap directories setup complete");
    return 0;
}

/**
 * Setup executable permissions for binaries
 */
static int setup_binary_permissions() {
    LOGI("Setting up binary permissions");
    
    const char* binaries[] = {
        BOOTSTRAP_PREFIX_DIR "/bin/busybox",
        BOOTSTRAP_PREFIX_DIR "/bin/ssh",
        BOOTSTRAP_PREFIX_DIR "/bin/ssh-keygen",
        BOOTSTRAP_PREFIX_DIR "/bin/sh",
        BOOTSTRAP_PREFIX_DIR "/bin/ash",
        NULL
    };
    
    for (int i = 0; binaries[i] != NULL; i++) {
        if (access(binaries[i], F_OK) == 0) {
            if (set_file_permissions(binaries[i], 0755) != 0) {
                LOGE("Failed to set executable permission on: %s", binaries[i]);
                // Don't fail completely, just log the error
            } else {
                LOGD("Set executable permission on: %s", binaries[i]);
            }
        }
    }
    
    LOGI("Binary permissions setup complete");
    return 0;
}

/**
 * Create essential symlinks for BusyBox applets
 */
static int setup_busybox_symlinks() {
    LOGI("Setting up BusyBox symlinks");
    
    const char* busybox_path = BOOTSTRAP_PREFIX_DIR "/bin/busybox";
    if (access(busybox_path, F_OK) != 0) {
        LOGE("BusyBox binary not found: %s", busybox_path);
        return -1;
    }
    
    // Essential commands that should be symlinked to busybox
    const char* commands[] = {
        "sh", "ash", "ls", "cat", "cp", "mv", "rm", "mkdir", "chmod", "chown",
        "touch", "echo", "pwd", "test", "[", "which", "whoami", "id", "groups",
        "tar", "gzip", "gunzip", "unzip", "wget", "grep", "find", "sort",
        "head", "tail", "cut", "sed", "awk", "wc", "uniq", "basename", "dirname",
        "env", "printenv", "date", "sleep", "kill", "ps", "mount", "umount",
        "clear", "reset", "tty", "stty", "stat", "readlink", "realpath",
        NULL
    };
    
    char symlink_path[PATH_MAX_LEN];
    for (int i = 0; commands[i] != NULL; i++) {
        snprintf(symlink_path, sizeof(symlink_path), BOOTSTRAP_PREFIX_DIR "/bin/%s", commands[i]);
        
        // Remove existing symlink if it exists
        if (access(symlink_path, F_OK) == 0) {
            unlink(symlink_path);
        }
        
        // Create symlink
        if (symlink("busybox", symlink_path) != 0) {
            LOGE("Failed to create symlink %s -> busybox: %s", symlink_path, strerror(errno));
            // Don't fail completely, just log the error
        } else {
            LOGD("Created symlink: %s -> busybox", commands[i]);
        }
    }
    
    LOGI("BusyBox symlinks setup complete");
    return 0;
}

/**
 * Write essential configuration files
 */
static int setup_configuration_files() {
    LOGI("Setting up configuration files");
    
    // Create basic shell profile
    char profile_path[PATH_MAX_LEN];
    snprintf(profile_path, sizeof(profile_path), BOOTSTRAP_PREFIX_DIR "/etc/profile");
    
    FILE* profile = fopen(profile_path, "w");
    if (profile) {
        fprintf(profile, "# XPort minimal shell profile\n");
        fprintf(profile, "export PATH=\"%s/bin:$PATH\"\n", BOOTSTRAP_PREFIX_DIR);
        fprintf(profile, "export HOME=\"%s\"\n", BOOTSTRAP_HOME_DIR);
        fprintf(profile, "export TMPDIR=\"%s\"\n", BOOTSTRAP_TMP_DIR);
        fprintf(profile, "export SHELL=\"%s/bin/sh\"\n", BOOTSTRAP_PREFIX_DIR);
        fprintf(profile, "export TERM=\"xterm-256color\"\n");
        fprintf(profile, "export PREFIX=\"%s\"\n", BOOTSTRAP_PREFIX_DIR);
        fprintf(profile, "export LANG=\"en_US.UTF-8\"\n");
        fprintf(profile, "export LC_ALL=\"en_US.UTF-8\"\n");
        fprintf(profile, "\n# Change to home directory\n");
        fprintf(profile, "cd \"$HOME\"\n");
        fclose(profile);
        
        LOGD("Created profile: %s", profile_path);
    } else {
        LOGE("Failed to create profile: %s", profile_path);
    }
    
    // Create SSH client configuration
    char ssh_config_path[PATH_MAX_LEN];
    snprintf(ssh_config_path, sizeof(ssh_config_path), BOOTSTRAP_PREFIX_DIR "/etc/ssh/ssh_config");
    
    FILE* ssh_config = fopen(ssh_config_path, "w");
    if (ssh_config) {
        fprintf(ssh_config, "# XPort SSH client configuration\n");
        fprintf(ssh_config, "Host *\n");
        fprintf(ssh_config, "    Port 22\n");
        fprintf(ssh_config, "    Protocol 2\n");
        fprintf(ssh_config, "    ServerAliveInterval 30\n");
        fprintf(ssh_config, "    ServerAliveCountMax 3\n");
        fprintf(ssh_config, "    TCPKeepAlive yes\n");
        fprintf(ssh_config, "    Compression yes\n");
        fprintf(ssh_config, "    PubkeyAuthentication yes\n");
        fprintf(ssh_config, "    PasswordAuthentication yes\n");
        fprintf(ssh_config, "    HostbasedAuthentication no\n");
        fprintf(ssh_config, "    GSSAPIAuthentication no\n");
        fprintf(ssh_config, "    UserKnownHostsFile ~/.ssh/known_hosts\n");
        fprintf(ssh_config, "    IdentityFile ~/.ssh/id_rsa\n");
        fprintf(ssh_config, "    IdentityFile ~/.ssh/id_ed25519\n");
        fclose(ssh_config);
        
        LOGD("Created SSH config: %s", ssh_config_path);
    } else {
        LOGE("Failed to create SSH config: %s", ssh_config_path);
    }
    
    LOGI("Configuration files setup complete");
    return 0;
}

/**
 * Check if bootstrap is already installed and up to date
 */
static int is_bootstrap_installed() {
    // Check for key files
    const char* key_files[] = {
        BOOTSTRAP_PREFIX_DIR "/bin/busybox",
        BOOTSTRAP_PREFIX_DIR "/bin/ssh",
        BOOTSTRAP_PREFIX_DIR "/bin/ssh-keygen",
        BOOTSTRAP_PREFIX_DIR "/etc/profile",
        NULL
    };
    
    for (int i = 0; key_files[i] != NULL; i++) {
        if (access(key_files[i], F_OK) != 0) {
            LOGD("Bootstrap file missing: %s", key_files[i]);
            return 0; // Not installed
        }
    }
    
    LOGD("Bootstrap appears to be installed");
    return 1; // Installed
}

/**
 * Main bootstrap installation function
 */
JNIEXPORT jboolean JNICALL
Java_com_xport_terminal_XPortBootstrap_installBootstrap(JNIEnv *env, jclass clazz __attribute__((unused)), jobject asset_manager) {
    LOGI("Starting XPort minimal bootstrap installation (version %s)", BOOTSTRAP_VERSION);
    
    // Check if already installed
    if (is_bootstrap_installed()) {
        LOGI("Bootstrap already installed, skipping installation");
        return JNI_TRUE;
    }
    
    // Get Android architecture
    const char* arch = get_android_architecture();
    LOGI("Target architecture: %s", arch);
    
    if (strcmp(arch, "unknown") == 0) {
        LOGE("Unsupported architecture");
        return JNI_FALSE;
    }
    
    // Get asset manager
    AAssetManager* mgr = AAssetManager_fromJava(env, asset_manager);
    if (!mgr) {
        LOGE("Failed to get asset manager");
        return JNI_FALSE;
    }
    
    // Setup directories
    if (setup_bootstrap_directories() != 0) {
        LOGE("Failed to setup bootstrap directories");
        return JNI_FALSE;
    }
    
    // Extract bootstrap ZIP for current architecture
    char bootstrap_zip[256];
    snprintf(bootstrap_zip, sizeof(bootstrap_zip), "xport-bootstrap-%s.zip", arch);
    
    char temp_zip_path[PATH_MAX_LEN];
    snprintf(temp_zip_path, sizeof(temp_zip_path), "%s/bootstrap.zip", BOOTSTRAP_TMP_DIR);
    
    // Extract ZIP from assets to temp location
    if (extract_asset_file(mgr, bootstrap_zip, temp_zip_path) != 0) {
        LOGE("Failed to extract bootstrap ZIP from assets");
        return JNI_FALSE;
    }
    
    // Extract bootstrap files from ZIP
    if (extract_bootstrap_zip(temp_zip_path, BOOTSTRAP_PREFIX_DIR) != 0) {
        LOGE("Failed to extract bootstrap files");
        unlink(temp_zip_path);
        return JNI_FALSE;
    }
    
    // Clean up temp ZIP
    unlink(temp_zip_path);
    
    // Setup permissions and symlinks
    if (setup_binary_permissions() != 0) {
        LOGE("Failed to setup binary permissions");
        return JNI_FALSE;
    }
    
    if (setup_busybox_symlinks() != 0) {
        LOGE("Failed to setup BusyBox symlinks");
        return JNI_FALSE;
    }
    
    // Setup configuration files
    if (setup_configuration_files() != 0) {
        LOGE("Failed to setup configuration files");
        return JNI_FALSE;
    }
    
    LOGI("XPort minimal bootstrap installation completed successfully");
    return JNI_TRUE;
}

/**
 * Get bootstrap information
 */
JNIEXPORT jstring JNICALL
Java_com_xport_terminal_XPortBootstrap_getBootstrapInfo(JNIEnv *env, jclass clazz __attribute__((unused))) {
    char info[512];
    const char* arch = get_android_architecture();
    int installed = is_bootstrap_installed();
    
    snprintf(info, sizeof(info),
        "XPort Bootstrap %s\nArchitecture: %s\nInstalled: %s\nPrefix: %s",
        BOOTSTRAP_VERSION, arch, installed ? "Yes" : "No", BOOTSTRAP_PREFIX_DIR);
    
    return (*env)->NewStringUTF(env, info);
}

/**
 * Check if bootstrap is installed
 */
JNIEXPORT jboolean JNICALL
Java_com_xport_terminal_XPortBootstrap_isBootstrapInstalled(JNIEnv *env __attribute__((unused)), jclass clazz __attribute__((unused))) {
    return is_bootstrap_installed() ? JNI_TRUE : JNI_FALSE;
}