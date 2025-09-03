#!/bin/bash

# Build minimal bootstrap for Android terminals
# Creates a minimal toybox with essential tools for SSH functionality

set -e

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
BOOTSTRAP_DIR="$PROJECT_ROOT/bootstrap"
BUILD_DIR="$BOOTSTRAP_DIR/build"
DOWNLOADS_DIR="$BOOTSTRAP_DIR/downloads"

# Package versions
TOYBOX_VERSION="0.8.10"
OPENSSL_VERSION="3.1.1"
DROPBEAR_VERSION="2024.85"

# Android NDK configuration - hardcoded since it never changes
ANDROID_NDK_ROOT="/opt/android-sdk/ndk/22.1.7171670"
API_LEVEL=21

# Debug: Print NDK root path  
echo "DEBUG: ANDROID_NDK_ROOT is set to: $ANDROID_NDK_ROOT"

# Architecture targets
ARCHITECTURES=("arm64-v8a")

# Color output functions
log_info() {
    echo -e "\e[0;34m[INFO]\e[0m $1"
}

log_success() {
    echo -e "\e[0;32m[SUCCESS]\e[0m $1"
}

log_error() {
    echo -e "\e[0;31m[ERROR]\e[0m $1"
}

log_warning() {
    echo -e "\e[0;33m[WARNING]\e[0m $1"
}

# Check requirements
check_requirements() {
    log_info "Checking build requirements..."
    
    # Check Android NDK
    if [ ! -d "$ANDROID_NDK_ROOT" ]; then
        log_error "Android NDK not found at: $ANDROID_NDK_ROOT"
        log_error "Please set ANDROID_NDK_ROOT environment variable"
        exit 1
    fi
    
    # Check required tools
    local required_tools=("wget" "make")
    for tool in "${required_tools[@]}"; do
        if ! command -v "$tool" >/dev/null 2>&1; then
            log_error "Required tool not found: $tool"
            exit 1
        fi
    done
    
    log_success "Android NDK found at: $ANDROID_NDK_ROOT"
    log_success "All required build tools found"
}

# Get NDK architecture mappings
get_ndk_arch() {
    local arch="$1"
    case "$arch" in
        "arm64-v8a") echo "aarch64" ;;
        "armeabi-v7a") echo "armv7a" ;;
        "x86_64") echo "x86_64" ;;
        "x86") echo "i686" ;;
        *) log_error "Unknown architecture: $arch"; exit 1 ;;
    esac
}

get_ndk_triplet() {
    local arch="$1"
    case "$arch" in
        "arm64-v8a") echo "aarch64-linux-android" ;;
        "armeabi-v7a") echo "armv7a-linux-androideabi" ;;
        "x86_64") echo "x86_64-linux-android" ;;
        "x86") echo "i686-linux-android" ;;
        *) log_error "Unknown architecture: $arch"; exit 1 ;;
    esac
}

# Setup build environment
setup_build_env() {
    log_info "Setting up build environment..."
    
    # Create directories
    mkdir -p "$BUILD_DIR" "$DOWNLOADS_DIR"
    
    # Setup toolchains for each architecture
    for arch in "${ARCHITECTURES[@]}"; do
        local toolchain_dir="$BUILD_DIR/toolchain-$arch"
        
        if [ -d "$toolchain_dir" ]; then
            log_info "Toolchain for $arch already exists, skipping..."
            continue
        fi
        
        local ndk_arch=$(get_ndk_arch "$arch")
        local ndk_triplet=$(get_ndk_triplet "$arch")
        
        # For NDK 22+, we'll use the toolchains directly
        export CC="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/${ndk_triplet}${API_LEVEL}-clang"
        export CXX="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/${ndk_triplet}${API_LEVEL}-clang++"
        export AR="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar"
        export AS="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-as"
        export LD="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/ld"
        export RANLIB="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ranlib"
        export STRIP="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
        
        # Verify toolchain
        if [ ! -f "$CC" ]; then
            log_error "Clang compiler not found for $arch: $CC"
            exit 1
        fi
        
        # Save toolchain info
        mkdir -p "$toolchain_dir"
        cat > "$toolchain_dir/env.sh" << EOF
# Toolchain environment for $arch
export CC="$CC"
export CXX="$CXX"
export AR="$AR"
export AS="$AS"
export LD="$LD"
export RANLIB="$RANLIB"
export STRIP="$STRIP"
export ARCH="$arch"
export NDK_ARCH="$ndk_arch"
export NDK_TRIPLET="$ndk_triplet"
export API_LEVEL="$API_LEVEL"
EOF
        
        log_success "Toolchain for $arch configured"
    done
    
    log_success "Build environment setup complete"
}

# Download source packages
download_sources() {
    log_info "Downloading source packages..."
    
    cd "$DOWNLOADS_DIR"
    
    # Download Toybox
    if [ ! -f "toybox-${TOYBOX_VERSION}.tar.gz" ]; then
        wget "https://github.com/landley/toybox/archive/refs/tags/${TOYBOX_VERSION}.tar.gz" -O "toybox-${TOYBOX_VERSION}.tar.gz"
    fi
    
    # Download OpenSSL
    if [ ! -f "openssl-${OPENSSL_VERSION}.tar.gz" ]; then
        wget "https://www.openssl.org/source/openssl-${OPENSSL_VERSION}.tar.gz"
    fi
    
    # Download Dropbear
    if [ ! -f "dropbear-${DROPBEAR_VERSION}.tar.bz2" ]; then
        wget "https://matt.ucc.asn.au/dropbear/releases/dropbear-${DROPBEAR_VERSION}.tar.bz2"
    fi
    
    log_success "Source packages downloaded"
}

# Extract source packages
extract_sources() {
    log_info "Extracting source packages..."
    
    cd "$BUILD_DIR"
    
    # Extract Toybox if not already extracted
    if [ ! -d "toybox-${TOYBOX_VERSION}" ]; then
        tar -xzf "$DOWNLOADS_DIR/toybox-${TOYBOX_VERSION}.tar.gz"
        
        # Note: Toybox doesn't need Android seccomp patches like BusyBox did
        echo "[INFO] Toybox extracted - no compatibility patches needed"
        cd "toybox-${TOYBOX_VERSION}"
        
        # Toybox is designed to be more portable and doesn't need these patches
        echo "[SUCCESS] Toybox extracted and ready to build"
        cd ..
    fi
    
    # Toybox doesn't require complex patching like BusyBox
    echo "[INFO] Toybox source extraction complete - no patching required"
    
    # Extract OpenSSL if not already extracted
    if [ ! -d "openssl-${OPENSSL_VERSION}" ]; then
        tar -xzf "$DOWNLOADS_DIR/openssl-${OPENSSL_VERSION}.tar.gz"
    fi
    
    # Extract Dropbear if not already extracted
    if [ ! -d "dropbear-${DROPBEAR_VERSION}" ]; then
        tar -xjf "$DOWNLOADS_DIR/dropbear-${DROPBEAR_VERSION}.tar.bz2"
    fi
    
    log_success "Source packages extracted"
}

# Build OpenSSL for architecture
build_openssl() {
    local arch="$1"
    local build_dir="$BUILD_DIR/openssl-$arch"
    local install_dir="$BUILD_DIR/openssl-install-$arch"
    
    log_info "Building OpenSSL for $arch..."
    
    if [ -d "$install_dir" ]; then
        log_info "OpenSSL for $arch already built, skipping..."
        return 0
    fi
    
    # Source toolchain environment
    source "$BUILD_DIR/toolchain-$arch/env.sh"
    
    # Copy source and configure
    cp -r "$BUILD_DIR/openssl-${OPENSSL_VERSION}" "$build_dir"
    cd "$build_dir"
    
    local openssl_arch
    case "$arch" in
        "arm64-v8a") openssl_arch="android-arm64" ;;
        "armeabi-v7a") openssl_arch="android-arm" ;;
        "x86_64") openssl_arch="android-x86_64" ;;
        "x86") openssl_arch="android-x86" ;;
    esac
    
    # Configure OpenSSL with explicit toolchain paths
    export PATH="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH"
    ./Configure "$openssl_arch" no-shared no-tests no-fuzz-libfuzzer no-fuzz-afl --prefix="$install_dir" --openssldir="$install_dir/ssl" \
        -D__ANDROID_API__=$API_LEVEL
    
    # Build and install
    make -j$(nproc)
    make install_sw
    
    log_success "OpenSSL for $arch built successfully"
}

# Build Dropbear SSH for architecture
build_dropbear() {
    local arch="$1"
    local build_dir="$BUILD_DIR/dropbear-$arch"
    local install_dir="$BUILD_DIR/dropbear-install-$arch"
    local openssl_dir="$BUILD_DIR/openssl-install-$arch"
    
    log_info "Building Dropbear SSH for $arch..."
    
    if [ -d "$install_dir" ]; then
        log_info "Dropbear for $arch already built, skipping..."
        return 0
    fi
    
    # Map architecture to NDK triplet
    local NDK_TRIPLET
    case "$arch" in
        "arm64-v8a") NDK_TRIPLET="aarch64-linux-android" ;;
        "armeabi-v7a") NDK_TRIPLET="armv7a-linux-androideabi" ;;
        "x86_64") NDK_TRIPLET="x86_64-linux-android" ;;
        "x86") NDK_TRIPLET="i686-linux-android" ;;
    esac
    
    # Clean and copy source
    rm -rf "$build_dir" "$install_dir"
    cp -r "$BUILD_DIR/dropbear-${DROPBEAR_VERSION}" "$build_dir"
    mkdir -p "$install_dir"
    
    cd "$build_dir"
    
    # Apply minimal Android compatibility patches for Dropbear
    log_info "Applying Android compatibility patches for Dropbear..."
    
    # Create a custom options header that disables problematic features
    cat > android_options.h << 'EOF'
/* Android-specific Dropbear configuration */
#ifndef ANDROID_OPTIONS_H
#define ANDROID_OPTIONS_H

#ifdef __ANDROID__
/* Disable server functionality - we only need SSH client */
#define DROPBEAR_SERVER 0
#define DROPBEAR_CLIENT 1

/* Disable server authentication methods that need crypt() */
#define DROPBEAR_SVR_PASSWORD_AUTH 0
#define DROPBEAR_SVR_PAM_AUTH 0

/* Disable logging and utmp features */
#define DO_MOTD 0
#define LOG_COMMANDS 0

/* Use simpler crypto - avoid libtommath/libtomcrypt issues */
#define DROPBEAR_RSA 1
#define DROPBEAR_DSS 0
#define DROPBEAR_ECDSA 0
#define DROPBEAR_ED25519 0

/* Simplify ciphers */
#define DROPBEAR_AES128 1
#define DROPBEAR_AES256 1
#define DROPBEAR_3DES 0
#define DROPBEAR_TWOFISH256 0
#define DROPBEAR_TWOFISH128 0

/* Basic Android compatibility */
#define HAVE_CRYPT 0  /* Explicitly disable crypt requirement */
#define HAVE_SHADOW_H 0
#define HAVE_LASTLOG_H 0
#define HAVE_UTMP_H 0
#define HAVE_UTMPX_H 0
#endif

#endif /* ANDROID_OPTIONS_H */
EOF

    # Create stub headers for missing Android NDK headers
    log_info "Creating Android NDK compatibility stub headers..."
    
    # Create utmp.h stub
    cat > utmp.h << 'EOF'
/*
 * Stub utmp.h header for Android NDK compatibility
 * Android doesn't have traditional Unix utmp functionality
 */
#ifndef _UTMP_H
#define _UTMP_H

/* Empty stub - utmp functionality disabled in Dropbear config */

#endif /* _UTMP_H */
EOF

    # Create utmpx.h stub
    cat > utmpx.h << 'EOF'
/*
 * Stub utmpx.h header for Android NDK compatibility  
 * Android doesn't have traditional Unix utmpx functionality
 */
#ifndef _UTMPX_H
#define _UTMPX_H

/* Empty stub - utmpx functionality disabled in Dropbear config */

#endif /* _UTMPX_H */
EOF

    # Create shadow.h stub with complete interface
    cat > shadow.h << 'EOF'
/*
 * Stub shadow.h header for Android NDK compatibility
 * Android doesn't have traditional Unix shadow password functionality
 */
#ifndef _SHADOW_H
#define _SHADOW_H

#include <sys/types.h>

/* Stub shadow password structure */
struct spwd {
    char *sp_namp;     /* username */
    char *sp_pwdp;     /* password */
    long sp_lstchg;    /* date of last change */
    long sp_min;       /* minimum days between changes */
    long sp_max;       /* maximum days between changes */ 
    long sp_warn;      /* warning period before expiry */
    long sp_inact;     /* inactivity period before disable */
    long sp_expire;    /* date of expiry */
    unsigned long sp_flag; /* unused */
};

/* Stub function - always return NULL (no shadow support on Android) */
static inline struct spwd *getspnam(const char *name) {
    (void)name; /* suppress unused parameter warning */
    return NULL;
}

#endif /* _SHADOW_H */
EOF

    # Include our Android options and force configuration overrides
    cat > localoptions.h << 'EOF'
#include "android_options.h"

/* Force Android crypto configuration overrides */
#ifdef __ANDROID__
#undef DROPBEAR_ECDSA
#define DROPBEAR_ECDSA 0

#undef DROPBEAR_ED25519
#define DROPBEAR_ED25519 0

#undef DROPBEAR_DSS
#define DROPBEAR_DSS 0

#undef DROPBEAR_SK_KEYS
#define DROPBEAR_SK_KEYS 0

/* Disable password authentication - no getpass() on Android */
#undef DROPBEAR_CLI_PASSWORD_AUTH
#define DROPBEAR_CLI_PASSWORD_AUTH 0

#undef DROPBEAR_CLI_INTERACT_AUTH
#define DROPBEAR_CLI_INTERACT_AUTH 0
#endif
EOF
    
    # Setup Android build environment
    export PATH="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH"
    export CC="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/${NDK_TRIPLET}${API_LEVEL}-clang"
    export CXX="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/${NDK_TRIPLET}${API_LEVEL}-clang++"
    export AR="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar"
    export RANLIB="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ranlib"
    export STRIP="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
    
    # Set Android-friendly CFLAGS
    export CFLAGS="-I$openssl_dir/include -D__ANDROID_API__=$API_LEVEL -D__ANDROID__ -DDISABLE_SYSLOG -DDISABLE_UTMP -DDISABLE_UTMPX -DDISABLE_LASTLOG -DDISABLE_WTMP -DDISABLE_WTMPX"
    export LDFLAGS="-L$openssl_dir/lib -static"
    export LIBS="-lssl -lcrypto"
    
    # Configure Dropbear with client-only focus
    ./configure \
        --host="$NDK_TRIPLET" \
        --prefix="$install_dir" \
        --disable-zlib \
        --disable-syslog \
        --disable-shadow \
        --disable-lastlog \
        --disable-utmp \
        --disable-utmpx \
        --disable-wtmp \
        --disable-wtmpx \
        --disable-pututline \
        --disable-pututxline \
        --disable-openpty \
        --disable-pam \
        --enable-bundled-libtom \
        --enable-static
    
    # Fix config.h to ensure our stub headers work properly
    log_info "Fixing config.h for Android NDK compatibility..."
    if [ -f config.h ]; then
        # Ensure HAVE_UTMP_H is undefined to match our stub
        sed -i 's/#define HAVE_UTMP_H 1/\/\* #undef HAVE_UTMP_H \*\//' config.h
    fi
    
    # Build only client tools - avoid server completely
    log_info "Building Dropbear client tools..."
    make -j$(nproc) dbclient dropbearkey scp
    
    # Install binaries
    mkdir -p "$install_dir/bin"
    cp dbclient "$install_dir/bin/ssh"      # dbclient is the SSH client
    cp dropbearkey "$install_dir/bin/"      # Key generation tool  
    cp scp "$install_dir/bin/"              # SCP for file transfer
    
    log_success "Dropbear SSH for $arch built successfully"
}

# Build Toybox for architecture
build_toybox() {
    local arch="$1"
    local build_dir="$BUILD_DIR/toybox-$arch"
    
    log_info "Building Toybox for $arch..."
    
    if [ -f "$build_dir/toybox" ]; then
        log_info "Toybox for $arch already built, skipping..."
        return 0
    fi
    
    # Source toolchain environment
    source "$BUILD_DIR/toolchain-$arch/env.sh"
    
    # Copy source
    cp -r "$BUILD_DIR/toybox-${TOYBOX_VERSION}" "$build_dir"
    cd "$build_dir"
    
    # Configure Toybox build for Android
    export CROSS_COMPILE=""
    export CFLAGS="${CFLAGS:-} -D__ANDROID__ -DANDROID -Os"
    export LDFLAGS="${LDFLAGS:-} -static"
    
    # Create stub header for missing SELinux functionality
    mkdir -p selinux
    cat > selinux/selinux.h << 'EOF'
/* Stub selinux.h for Android NDK compatibility */
#ifndef _SELINUX_SELINUX_H_
#define _SELINUX_SELINUX_H_

/* Stub functions - SELinux not available in Android NDK */
static inline int is_selinux_enabled(void) { return 0; }
static inline char *selinux_context_path(void) { return NULL; }
static inline int getcon(char **context) { *context = NULL; return -1; }
static inline int setcon(const char *context) { return -1; }
static inline void freecon(char *context) { }

#endif
EOF
    
    # Create Android NDK compatibility stubs
    cat > android_compat.h << 'EOF'
/* Android NDK compatibility stubs */
#ifndef _ANDROID_COMPAT_H_
#define _ANDROID_COMPAT_H_

#include <grp.h>
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>
#include <langinfo.h>
#include <ifaddrs.h>
#include <iconv.h>
#include <stdio.h>

/* Android API level constants */
#ifndef __ANDROID_API_U__
#define __ANDROID_API_U__ 34
#endif

/* Thread-safe group functions not available in Android NDK */
static inline int getgrnam_r(const char *name, struct group *grp, 
                           char *buffer, size_t bufsize, struct group **result) {
    struct group *g = getgrnam(name);
    if (!g) {
        *result = NULL;
        return errno ? errno : ENOENT;
    }
    *grp = *g;
    *result = grp;
    return 0;
}

static inline int getgrgid_r(gid_t gid, struct group *grp,
                           char *buffer, size_t bufsize, struct group **result) {
    struct group *g = getgrgid(gid);
    if (!g) {
        *result = NULL;
        return errno ? errno : ENOENT;
    }
    *grp = *g;
    *result = grp;
    return 0;
}

/* getentropy function not available in older Android NDK */
static inline int getentropy(void *buffer, size_t length) {
    /* Simple fallback using /dev/urandom */
    static int fd = -1;
    if (fd < 0) {
        fd = open("/dev/urandom", O_RDONLY);
        if (fd < 0) return -1;
    }
    return read(fd, buffer, length) == length ? 0 : -1;
}

/* nl_langinfo function not available in Android NDK */
#ifndef CODESET
#define CODESET 14
#endif

static inline char *nl_langinfo(int item) {
    /* Android always uses UTF-8 for apps */
    if (item == CODESET) return "UTF-8";
    return "";
}

/* sethostname function not available in Android NDK */
static inline int sethostname(const char *name, size_t len) {
    /* Android doesn't allow changing hostname from apps */
    errno = EPERM;
    return -1;
}

/* getifaddrs/freeifaddrs functions not available in Android NDK */
static inline int getifaddrs(struct ifaddrs **ifap) {
    /* Network interface enumeration not available */
    *ifap = NULL;
    errno = ENOSYS;
    return -1;
}

static inline void freeifaddrs(struct ifaddrs *ifa) {
    /* Nothing to free since getifaddrs always fails */
    (void)ifa;
}

/* iconv functions not available in Android NDK */
static inline iconv_t iconv_open(const char *tocode, const char *fromcode) {
    /* Character encoding conversion not available */
    errno = ENOSYS;
    return (iconv_t)-1;
}

static inline size_t iconv(iconv_t cd, char **inbuf, size_t *inbytesleft,
                          char **outbuf, size_t *outbytesleft) {
    /* Character encoding conversion not available */
    errno = ENOSYS;
    return (size_t)-1;
}

static inline int iconv_close(iconv_t cd) {
    /* Nothing to close since iconv_open always fails */
    (void)cd;
    return 0;
}

/* fmemopen function not available in Android NDK */
static inline FILE *fmemopen(void *buffer, size_t size, const char *mode) {
    /* Create temporary file as fallback */
    FILE *fp = tmpfile();
    if (fp && buffer && size > 0 && (mode[0] == 'r' || mode[0] == 'w')) {
        if (mode[0] == 'r') {
            fwrite(buffer, 1, size, fp);
            rewind(fp);
        }
    }
    return fp;
}

#endif
EOF
    
    # Add include path for our stub headers and include the compatibility header
    # Also add OpenSSL include path
    export CFLAGS="$CFLAGS -I. -include android_compat.h -I../openssl-$ARCH/include"
    
    # Add OpenSSL library path
    export LDFLAGS="$LDFLAGS -L../openssl-$ARCH -lcrypto"
    
    # Build Toybox with Android defaults
    make android_defconfig >/dev/null 2>&1 || make defconfig >/dev/null 2>&1
    
    # Disable SELinux support to avoid Android NDK compatibility issues
    sed -i 's/CONFIG_TOYBOX_SELINUX=y/# CONFIG_TOYBOX_SELINUX is not set/' .config
    sed -i 's/CONFIG_CHCON=y/# CONFIG_CHCON is not set/' .config
    sed -i 's/CONFIG_GETENFORCE=y/# CONFIG_GETENFORCE is not set/' .config
    sed -i 's/CONFIG_LOAD_POLICY=y/# CONFIG_LOAD_POLICY is not set/' .config
    sed -i 's/CONFIG_RESTORECON=y/# CONFIG_RESTORECON is not set/' .config
    sed -i 's/CONFIG_RUNCON=y/# CONFIG_RUNCON is not set/' .config
    sed -i 's/CONFIG_SETENFORCE=y/# CONFIG_SETENFORCE is not set/' .config
    
    # For now, keep shell disabled due to Android NDK compatibility issues
    # The app will need to be configured to use a different shell approach
    # sed -i 's/# CONFIG_SH is not set/CONFIG_SH=y/' .config
    
    # Disable programs that use xfork() which isn't available in Android NDK
    sed -i 's/CONFIG_NETCAT=y/# CONFIG_NETCAT is not set/' .config
    sed -i 's/CONFIG_NBD_CLIENT=y/# CONFIG_NBD_CLIENT is not set/' .config
    
    # Build with explicit cross-compilation variables
    make -j$(nproc) \
        CC="$CC" \
        CXX="$CXX" \
        AR="$AR" \
        RANLIB="$RANLIB" \
        STRIP="$STRIP" \
        CROSS_COMPILE="$CROSS_COMPILE" \
        CFLAGS="$CFLAGS" \
        LDFLAGS="$LDFLAGS" \
        toybox
    
    # Strip the binary
    $STRIP toybox
    
    log_success "Toybox for $arch built successfully"
}

# Create bootstrap package for an architecture
create_package() {
    local arch=$1
    log_info "Creating bootstrap package for $arch..."
    
    local pkg_dir="$BUILD_DIR/package-$arch"
    local pkg_name="$BOOTSTRAP_DIR/xport-bootstrap-$arch.zip"
    
    # Create temporary package directory
    rm -rf "$pkg_dir"
    mkdir -p "$pkg_dir/bin"
    
    # Copy binaries
    cp "$BUILD_DIR/toybox-$arch/toybox" "$pkg_dir/bin/"
    cp "$BUILD_DIR/dropbear-$arch/dbclient" "$pkg_dir/bin/"
    cp "$BUILD_DIR/dropbear-$arch/scp" "$pkg_dir/bin/"
    
    # Copy from install directory if available
    if [ -d "$BUILD_DIR/dropbear-install-$arch/bin" ]; then
        cp "$BUILD_DIR/dropbear-install-$arch/bin/"* "$pkg_dir/bin/" 2>/dev/null || true
    fi
    
    # Create a simple shell script for basic shell functionality
    cat > "$pkg_dir/bin/sh" << 'SHELL_EOF'
#!/system/bin/sh
# Simple shell wrapper for XPort Terminal
# Since Toybox shell has Android NDK compatibility issues, 
# we use the system shell as a fallback
exec /system/bin/sh "$@"
SHELL_EOF
    chmod +x "$pkg_dir/bin/sh"
    
    # Create symbolic link for ssh (points to dbclient) if not already present
    cd "$pkg_dir/bin"
    if [ ! -e "ssh" ]; then
        ln -s dbclient ssh
    fi
    cd - >/dev/null
    
    # Create ZIP package
    cd "$pkg_dir"
    zip -r "$pkg_name" . >/dev/null
    cd - >/dev/null
    
    # Cleanup temporary directory
    rm -rf "$pkg_dir"
    
    log_success "Package created: $(basename "$pkg_name") ($(stat -c%s "$pkg_name") bytes)"
}

# Build all packages
build_all() {
    log_info "Building minimal bootstrap for all architectures..."
    
    for arch in "${ARCHITECTURES[@]}"; do
        log_info "Building for $arch..."
        
        # Build OpenSSL, Dropbear, and Toybox
        build_openssl "$arch"
        build_dropbear "$arch"
        build_toybox "$arch"
        
        # Create bootstrap package
        create_package "$arch"
        
        log_success "Build for $arch completed"
    done
    
    log_success "All architectures built successfully!"
}

# Main execution
main() {
    check_requirements
    setup_build_env
    download_sources
    extract_sources
    build_all
    
    log_success "Minimal bootstrap build completed!"
    log_info "Binaries are available in: $BUILD_DIR"
}

# Execute main function
main "$@"