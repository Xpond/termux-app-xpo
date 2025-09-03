/**
 * XPort Minimal Bootstrap Loader
 * 
 * This file replaces termux-bootstrap.c with a minimal bootstrap loader
 * that extracts and sets up only essential SSH functionality components.
 * 
 * Key differences from Termux bootstrap:
 * - Much smaller bootstrap (~5-10MB vs 180MB+)
 * - Only essential components (Toybox, Dropbear SSH, minimal libs)
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
        BOOTSTRAP_PREFIX_DIR "/bin/toybox",
        BOOTSTRAP_PREFIX_DIR "/bin/ssh",
        BOOTSTRAP_PREFIX_DIR "/bin/dbclient",
        BOOTSTRAP_PREFIX_DIR "/bin/dropbearkey",
        BOOTSTRAP_PREFIX_DIR "/bin/scp",
        BOOTSTRAP_PREFIX_DIR "/bin/ssh-keygen",
        BOOTSTRAP_PREFIX_DIR "/bin/sh",
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
 * Create essential symlinks for Toybox applets
 */
static int setup_toybox_symlinks() {
    LOGI("Setting up Toybox symlinks");
    
    const char* toybox_path = BOOTSTRAP_PREFIX_DIR "/bin/toybox";
    if (access(toybox_path, F_OK) != 0) {
        LOGE("Toybox binary not found: %s", toybox_path);
        return -1;
    }
    
    // Essential commands that should be symlinked to toybox
    // NOTE: Excluding shell commands (sh, ash) due to Android compatibility issues
    const char* commands[] = {
        "ls", "cat", "cp", "mv", "rm", "mkdir", "chmod", "chown",
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
        if (symlink("toybox", symlink_path) != 0) {
            LOGE("Failed to create symlink %s -> toybox: %s", symlink_path, strerror(errno));
            // Don't fail completely, just log the error
        } else {
            LOGD("Created symlink: %s -> toybox", commands[i]);
        }
    }
    
    LOGI("Toybox symlinks setup complete");
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
        BOOTSTRAP_PREFIX_DIR "/bin/toybox",
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
    
    // Note: ZIP extraction is now handled by Java code before calling this function
    // This function just sets up permissions and configuration files
    
    // Setup permissions and symlinks
    if (setup_binary_permissions() != 0) {
        LOGE("Failed to setup binary permissions");
        return JNI_FALSE;
    }
    
    if (setup_toybox_symlinks() != 0) {
        LOGE("Failed to setup Toybox symlinks");
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