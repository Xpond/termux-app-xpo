#!/bin/bash

# Build minimal bootstrap for Android terminals
# Creates a minimal BusyBox with essential tools for SSH functionality

set -e

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
BOOTSTRAP_DIR="$PROJECT_ROOT/bootstrap"
BUILD_DIR="$BOOTSTRAP_DIR/build"
DOWNLOADS_DIR="$BOOTSTRAP_DIR/downloads"

# Package versions
BUSYBOX_VERSION="1.36.1"
OPENSSL_VERSION="3.1.1"
DROPBEAR_VERSION="2024.85"

# Android NDK configuration
ANDROID_NDK_ROOT="${ANDROID_NDK_ROOT:-/opt/android-sdk/ndk/22.1.7171670}"
API_LEVEL=21

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
    
    # Download BusyBox
    if [ ! -f "busybox-${BUSYBOX_VERSION}.tar.bz2" ]; then
        wget "https://busybox.net/downloads/busybox-${BUSYBOX_VERSION}.tar.bz2"
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
    
    # Extract BusyBox if not already extracted
    if [ ! -d "busybox-${BUSYBOX_VERSION}" ]; then
        tar -xjf "$DOWNLOADS_DIR/busybox-${BUSYBOX_VERSION}.tar.bz2"
    fi
    
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

# Build BusyBox for architecture
build_busybox() {
    local arch="$1"
    local build_dir="$BUILD_DIR/busybox-$arch"
    
    log_info "Building BusyBox for $arch..."
    
    if [ -f "$build_dir/busybox" ]; then
        log_info "BusyBox for $arch already built, skipping..."
        return 0
    fi
    
    # Source toolchain environment
    source "$BUILD_DIR/toolchain-$arch/env.sh"
    
    # Copy source
    cp -r "$BUILD_DIR/busybox-${BUSYBOX_VERSION}" "$build_dir"
    cd "$build_dir"
    
    # Create Android-compatible stub headers
    mkdir -p include/sys
    
    # Create utmpx.h stub
    cat > include/utmpx.h << 'EOF'
/* Stub utmpx.h for Android NDK compatibility */
#ifndef _UTMPX_H
#define _UTMPX_H
#include <sys/time.h>
struct utmpx { 
    char ut_user[32]; 
    char ut_id[4]; 
    char ut_line[32]; 
    char ut_host[256];
    short ut_type; 
    pid_t ut_pid; 
    struct timeval ut_tv; 
};
#define EMPTY 0
#define USER_PROCESS 7
#define RUN_LVL 1
#define LOGIN_PROCESS 6
#define INIT_PROCESS 5
#define DEAD_PROCESS 8
static inline struct utmpx *getutxent(void) { return NULL; }
static inline struct utmpx *pututxline(const struct utmpx *ut) { (void)ut; return NULL; }
static inline void setutxent(void) {}
static inline void endutxent(void) {}
static inline void updwtmpx(const char *wtmpx_file, const struct utmpx *utx) { (void)wtmpx_file; (void)utx; }
static inline int utmpxname(const char *file) { (void)file; return 0; }
#define _PATH_UTMPX "/data/data/com.xport.terminal/files/var/log/utmpx"
#endif
EOF
    
    # Create utmp.h stub
    cat > include/utmp.h << 'EOF'
/* Stub utmp.h for Android NDK compatibility */
#ifndef _UTMP_H
#define _UTMP_H
struct utmp { char ut_user[32]; char ut_id[4]; char ut_line[32]; short ut_type; pid_t ut_pid; long ut_time; };
static inline struct utmp *getutent(void) { return NULL; }
static inline void setutent(void) {}
static inline void endutent(void) {}
#define _PATH_WTMP "/data/data/com.xport.terminal/files/var/log/wtmp"
#endif
EOF
    
    # Create sys/kd.h stub
    cat > include/sys/kd.h << 'EOF'
/* Stub sys/kd.h for Android NDK compatibility */
#ifndef _SYS_KD_H
#define _SYS_KD_H

/* Linux keyboard/console constants - minimal stubs for Android */
#define KDGETMODE    0x4B3B
#define KDSETMODE    0x4B3A
#define KD_TEXT      0x00
#define KD_GRAPHICS  0x01
#define VT_ACTIVATE  0x5606
#define VT_WAITACTIVE 0x5607

#endif /* _SYS_KD_H */
EOF
    
    # Use default config and customize
    make defconfig >/dev/null 2>&1
    
    # Configure environment for cross-compilation
    export CROSS_COMPILE=""
    export CFLAGS="${CFLAGS:-} -I$(pwd)/include"
    export LDFLAGS="${LDFLAGS:-}"
    
# Already made defconfig above
    
    # Disable problematic tools for Android build
    sed -i 's/CONFIG_LOADFONT=y/CONFIG_LOADFONT=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_SETFONT=y/CONFIG_SETFONT=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_DUMPKMAP=y/CONFIG_DUMPKMAP=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_LOADKMAP=y/CONFIG_LOADKMAP=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_RUNLEVEL=y/CONFIG_RUNLEVEL=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_WHO=y/CONFIG_WHO=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_GETTY=y/CONFIG_GETTY=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_TC=y/CONFIG_TC=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_FEATURE_TC_INGRESS=y/CONFIG_FEATURE_TC_INGRESS=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_LAST=y/CONFIG_LAST=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_CONSPY=y/CONFIG_CONSPY=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_IFCONFIG=y/CONFIG_IFCONFIG=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_ROUTE=y/CONFIG_ROUTE=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_ARP=y/CONFIG_ARP=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_FEATURE_IFUPDOWN_IPV6=y/CONFIG_FEATURE_IFUPDOWN_IPV6=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_IFUPDOWN=y/CONFIG_IFUPDOWN=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_FEATURE_IFCONFIG_STATUS=y/CONFIG_FEATURE_IFCONFIG_STATUS=n/' .config 2>/dev/null || true
    
    # Disable all networking utilities that depend on interface.c to avoid in6_ifreq conflicts
    sed -i 's/CONFIG_FEATURE_IPV6=y/CONFIG_FEATURE_IPV6=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_PING=y/CONFIG_PING=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_PING6=y/CONFIG_PING6=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_NETSTAT=y/CONFIG_NETSTAT=n/' .config 2>/dev/null || true
    
    # Disable syslog utilities - Android uses different logging system
    sed -i 's/CONFIG_SYSLOGD=y/CONFIG_SYSLOGD=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_LOGGER=y/CONFIG_LOGGER=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_LOGREAD=y/CONFIG_LOGREAD=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_KLOGD=y/CONFIG_KLOGD=n/' .config 2>/dev/null || true
    
    # Disable IPC utilities - they have semun union conflicts with Android NDK
    sed -i 's/CONFIG_IPCRM=y/CONFIG_IPCRM=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_IPCS=y/CONFIG_IPCS=n/' .config 2>/dev/null || true
    
    # Disable swap utilities - Android doesn't use traditional swap
    sed -i 's/CONFIG_SWAPON=y/CONFIG_SWAPON=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_SWAPOFF=y/CONFIG_SWAPOFF=n/' .config 2>/dev/null || true
    
    # Disable DNS resolution features that require libresolv (not available on Android NDK)
    sed -i 's/CONFIG_NSLOOKUP=y/CONFIG_NSLOOKUP=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_FEATURE_NSLOOKUP_BIG=y/CONFIG_FEATURE_NSLOOKUP_BIG=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_FEATURE_NSSWITCH=y/CONFIG_FEATURE_NSSWITCH=n/' .config 2>/dev/null || true
    
    # Disable all hostname/DNS related features that might require libresolv
    sed -i 's/CONFIG_HOSTNAME=y/CONFIG_HOSTNAME=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_DNSDOMAINNAME=y/CONFIG_DNSDOMAINNAME=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_FEATURE_ETC_NETWORKS=y/CONFIG_FEATURE_ETC_NETWORKS=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_FEATURE_PREFER_IPV4_ADDRESS=y/CONFIG_FEATURE_PREFER_IPV4_ADDRESS=n/' .config 2>/dev/null || true
    
    # Disable additional problematic networking components from Issue #2 solution
    sed -i 's/CONFIG_BRCTL=y/CONFIG_BRCTL=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_FEATURE_BRCTL_FANCY=y/CONFIG_FEATURE_BRCTL_FANCY=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_FEATURE_BRCTL_SHOW=y/CONFIG_FEATURE_BRCTL_SHOW=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_IFPLUGD=y/CONFIG_IFPLUGD=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_INETD=y/CONFIG_INETD=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_FEATURE_INETD_SUPPORT_BUILTIN_ECHO=y/CONFIG_FEATURE_INETD_SUPPORT_BUILTIN_ECHO=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_FEATURE_INETD_SUPPORT_BUILTIN_DISCARD=y/CONFIG_FEATURE_INETD_SUPPORT_BUILTIN_DISCARD=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_FEATURE_INETD_SUPPORT_BUILTIN_TIME=y/CONFIG_FEATURE_INETD_SUPPORT_BUILTIN_TIME=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_FEATURE_INETD_SUPPORT_BUILTIN_DAYTIME=y/CONFIG_FEATURE_INETD_SUPPORT_BUILTIN_DAYTIME=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_FEATURE_INETD_SUPPORT_BUILTIN_CHARGEN=y/CONFIG_FEATURE_INETD_SUPPORT_BUILTIN_CHARGEN=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_FEATURE_INETD_RPC=y/CONFIG_FEATURE_INETD_RPC=n/' .config 2>/dev/null || true
    
    # Disable utilities with Android NDK undefined symbols
    sed -i 's/CONFIG_SYNC=y/CONFIG_SYNC=n/' .config 2>/dev/null || true              # syncfs undefined
    sed -i 's/CONFIG_HOSTID=y/CONFIG_HOSTID=n/' .config 2>/dev/null || true          # gethostid undefined  
    sed -i 's/CONFIG_LOGNAME=y/CONFIG_LOGNAME=n/' .config 2>/dev/null || true        # getlogin_r undefined
    sed -i 's/CONFIG_SU=y/CONFIG_SU=n/' .config 2>/dev/null || true                  # getusershell undefined
    sed -i 's/CONFIG_SEEDRNG=y/CONFIG_SEEDRNG=n/' .config 2>/dev/null || true        # getrandom undefined
    sed -i 's/CONFIG_ETHER_WAKE=y/CONFIG_ETHER_WAKE=n/' .config 2>/dev/null || true  # ether_hostton undefined
    sed -i 's/CONFIG_FSCK_MINIX=y/CONFIG_FSCK_MINIX=n/' .config 2>/dev/null || true  # setbit/clrbit undefined
    sed -i 's/CONFIG_MKFS_MINIX=y/CONFIG_MKFS_MINIX=n/' .config 2>/dev/null || true  # setbit/clrbit undefined
    
    # Disable utilities that use setgid/setuid syscalls (blocked by Android seccomp)
    sed -i 's/CONFIG_LOGIN=y/CONFIG_LOGIN=n/' .config 2>/dev/null || true            # setgid/setuid syscalls
    sed -i 's/CONFIG_PASSWD=y/CONFIG_PASSWD=n/' .config 2>/dev/null || true          # setgid/setuid syscalls
    sed -i 's/CONFIG_ADDUSER=y/CONFIG_ADDUSER=n/' .config 2>/dev/null || true        # setgid/setuid syscalls
    sed -i 's/CONFIG_DELUSER=y/CONFIG_DELUSER=n/' .config 2>/dev/null || true        # setgid/setuid syscalls
    sed -i 's/CONFIG_ADDGROUP=y/CONFIG_ADDGROUP=n/' .config 2>/dev/null || true      # setgid syscalls
    sed -i 's/CONFIG_DELGROUP=y/CONFIG_DELGROUP=n/' .config 2>/dev/null || true      # setgid syscalls
    sed -i 's/CONFIG_CHPASSWD=y/CONFIG_CHPASSWD=n/' .config 2>/dev/null || true      # setgid/setuid syscalls
    sed -i 's/CONFIG_SULOGIN=y/CONFIG_SULOGIN=n/' .config 2>/dev/null || true        # setgid/setuid syscalls
    sed -i 's/CONFIG_VLOCK=y/CONFIG_VLOCK=n/' .config 2>/dev/null || true            # setgid/setuid syscalls
    sed -i 's/CONFIG_FEATURE_SHADOWPASSWDS=y/CONFIG_FEATURE_SHADOWPASSWDS=n/' .config 2>/dev/null || true
    
    # Disable shell glob features that need glob/globfree (not available in Android NDK)
    sed -i 's/CONFIG_HUSH_GLOB=y/CONFIG_HUSH_GLOB=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_FEATURE_SH_GLOB=y/CONFIG_FEATURE_SH_GLOB=n/' .config 2>/dev/null || true
    
    # Disable features that use unavailable signal functions (sigtimedwait, sigisemptyset)
    sed -i 's/CONFIG_INIT=y/CONFIG_INIT=n/' .config 2>/dev/null || true               # sigtimedwait undefined
    sed -i 's/CONFIG_MDEV=y/CONFIG_MDEV=n/' .config 2>/dev/null || true              # sigtimedwait undefined
    sed -i 's/CONFIG_RUN_INIT=y/CONFIG_RUN_INIT=n/' .config 2>/dev/null || true      # sigtimedwait undefined
    sed -i 's/CONFIG_FEATURE_WAIT_FOR_INIT=y/CONFIG_FEATURE_WAIT_FOR_INIT=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_FEATURE_USE_INITTAB=y/CONFIG_FEATURE_USE_INITTAB=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_FEATURE_INIT_SCTTY=y/CONFIG_FEATURE_INIT_SCTTY=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_FEATURE_INIT_SYSLOG=y/CONFIG_FEATURE_INIT_SYSLOG=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_FEATURE_INIT_QUIET=y/CONFIG_FEATURE_INIT_QUIET=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_FEATURE_INIT_MODIFY_CMDLINE=y/CONFIG_FEATURE_INIT_MODIFY_CMDLINE=n/' .config 2>/dev/null || true
    
    # Disable all utilities in init/ directory that might pull in init.c code
    sed -i 's/CONFIG_BOOTCHARTD=y/CONFIG_BOOTCHARTD=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_FEATURE_BOOTCHARTD_BLOATED_HEADER=y/CONFIG_FEATURE_BOOTCHARTD_BLOATED_HEADER=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_FEATURE_BOOTCHARTD_CONFIG_FILE=y/CONFIG_FEATURE_BOOTCHARTD_CONFIG_FILE=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_HALT=y/CONFIG_HALT=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_POWEROFF=y/CONFIG_POWEROFF=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_REBOOT=y/CONFIG_REBOOT=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_LINUXRC=y/CONFIG_LINUXRC=n/' .config 2>/dev/null || true
    
    # Disable hush shell completely - use ash instead (has fewer Android NDK conflicts)
    sed -i 's/CONFIG_HUSH=y/CONFIG_HUSH=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_ASH=n/CONFIG_ASH=y/' .config 2>/dev/null || true
    
    # Disable all shell advanced features that might cause issues
    sed -i 's/CONFIG_FEATURE_SH_STANDALONE=y/CONFIG_FEATURE_SH_STANDALONE=n/' .config 2>/dev/null || true
    
    # Disable ash shell features that might call setgid/setuid
    sed -i 's/CONFIG_ASH_JOB_CONTROL=y/CONFIG_ASH_JOB_CONTROL=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_ASH_ALIAS=y/CONFIG_ASH_ALIAS=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_ASH_GETOPTS=y/CONFIG_ASH_GETOPTS=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_ASH_BUILTIN_ECHO=y/CONFIG_ASH_BUILTIN_ECHO=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_ASH_BUILTIN_PRINTF=y/CONFIG_ASH_BUILTIN_PRINTF=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_ASH_BUILTIN_TEST=y/CONFIG_ASH_BUILTIN_TEST=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_ASH_HELP=y/CONFIG_ASH_HELP=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_ASH_CMDCMD=y/CONFIG_ASH_CMDCMD=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_ASH_MAIL=y/CONFIG_ASH_MAIL=n/' .config 2>/dev/null || true
    sed -i 's/CONFIG_ASH_OPTIMIZE_FOR_SIZE=y/CONFIG_ASH_OPTIMIZE_FOR_SIZE=n/' .config 2>/dev/null || true
    
    # Disable BusyBox platform compatibility functions that conflict with Android NDK r22
    sed -i 's/CONFIG_PLATFORM_LINUX=y/CONFIG_PLATFORM_LINUX=n/' .config 2>/dev/null || true
    
    # Add defines to prevent duplicate symbols with Android NDK r22
    export CFLAGS="$CFLAGS -DHAVE_STRCHRNUL"
    
    # Remove libresolv dependency by overriding EXTRA_LDLIBS  
    echo 'CONFIG_EXTRA_LDLIBS=""' >> .config
    
    # Manually patch libbb to avoid duplicate symbols with Android libc
    if [ -f libbb/missing_syscalls.c ]; then
        # Comment out functions that now exist in Android NDK r22 (getsid, sethostname, adjtimex)
        if ! grep -q "^/\*$" libbb/missing_syscalls.c; then
            sed -i '/^pid_t getsid/,/^}$/c\
/*\
pid_t getsid(pid_t pid)\
{\
	return syscall(__NR_getsid, pid);\
}\
\
int sethostname(const char *name, size_t len)\
{\
	return syscall(__NR_sethostname, name, len);\
}\
\
struct timex;\
int adjtimex(struct timex *buf)\
{\
	return syscall(__NR_adjtimex, buf);\
}\
*/' libbb/missing_syscalls.c
        fi
    fi
    
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
        busybox
    
    # Strip the binary
    $STRIP busybox
    
    log_success "BusyBox for $arch built successfully"
}

# Build all packages
build_all() {
    log_info "Building minimal bootstrap for all architectures..."
    
    for arch in "${ARCHITECTURES[@]}"; do
        log_info "Building for $arch..."
        
        # Build OpenSSL, Dropbear, and BusyBox
        build_openssl "$arch"
        build_dropbear "$arch"
        build_busybox "$arch"
        
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