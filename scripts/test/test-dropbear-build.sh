#!/bin/bash

# Test script to build Dropbear SSH for Android - much simpler than OpenSSH
set -e

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIR"
BOOTSTRAP_DIR="$PROJECT_ROOT/bootstrap"
BUILD_DIR="$BOOTSTRAP_DIR/build"

# Component versions
OPENSSL_VERSION="3.1.1"
DROPBEAR_VERSION="2022.83"

# Android NDK configuration
NDK_VERSION="22.1.7171670"
API_LEVEL=21
TEST_ARCH="arm64-v8a"

# Check for Android NDK
if [ -z "${ANDROID_NDK_ROOT:-}" ]; then
    if [ -z "${ANDROID_HOME:-}" ]; then
        echo "Error: Neither ANDROID_NDK_ROOT nor ANDROID_HOME is set"
        exit 1
    fi
    ANDROID_NDK_ROOT="$ANDROID_HOME/ndk/$NDK_VERSION"
fi

echo "Testing Dropbear SSH build for $TEST_ARCH..."
echo "Using Android NDK: $ANDROID_NDK_ROOT"

# Download Dropbear if needed
download_dropbear() {
    local src_dir="$BUILD_DIR/src"
    mkdir -p "$src_dir"
    cd "$src_dir"
    
    if [ ! -f "dropbear-$DROPBEAR_VERSION.tar.bz2" ]; then
        echo "Downloading Dropbear $DROPBEAR_VERSION..."
        curl -L "https://matt.ucc.asn.au/dropbear/releases/dropbear-$DROPBEAR_VERSION.tar.bz2" \
             -o "dropbear-$DROPBEAR_VERSION.tar.bz2"
    fi
    
    if [ ! -d "dropbear-$DROPBEAR_VERSION" ]; then
        echo "Extracting Dropbear..."
        tar -xf "dropbear-$DROPBEAR_VERSION.tar.bz2"
    fi
}

# Build Dropbear SSH
build_dropbear_test() {
    local arch="$TEST_ARCH"
    local src_dir="$BUILD_DIR/src/dropbear-$DROPBEAR_VERSION"
    local build_dir="$BUILD_DIR/dropbear-$arch-test"
    local install_dir="$BUILD_DIR/install/dropbear-$arch-test"
    local openssl_dir="$BUILD_DIR/install/openssl-$arch"
    
    # Map architecture to NDK triplet
    case "$arch" in
        "arm64-v8a") NDK_TRIPLET="aarch64-linux-android" ;;
        "armeabi-v7a") NDK_TRIPLET="armv7a-linux-androideabi" ;;
        "x86_64") NDK_TRIPLET="x86_64-linux-android" ;;
        "x86") NDK_TRIPLET="i686-linux-android" ;;
    esac
    
    echo "Building Dropbear SSH for $arch..."
    
    # Clean and copy source
    rm -rf "$build_dir" "$install_dir"
    cp -r "$src_dir" "$build_dir"
    mkdir -p "$install_dir"
    
    cd "$build_dir"
    
    # Apply minimal Android compatibility patches for Dropbear
    echo "Applying Android compatibility patches for Dropbear..."
    
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
    echo "Creating Android NDK compatibility stub headers..."
    
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
    echo "Fixing config.h for Android NDK compatibility..."
    if [ -f config.h ]; then
        # Ensure HAVE_UTMP_H is undefined to match our stub
        sed -i 's/#define HAVE_UTMP_H 1/\/\* #undef HAVE_UTMP_H \*\//' config.h
    fi
    
    # Build only client tools - avoid server completely
    echo "Building Dropbear client tools..."
    make -j$(nproc) dbclient dropbearkey scp
    
    # Install binaries
    mkdir -p "$install_dir/bin"
    cp dbclient "$install_dir/bin/ssh"      # dbclient is the SSH client
    cp dropbearkey "$install_dir/bin/"      # Key generation tool  
    cp scp "$install_dir/bin/"              # SCP for file transfer
    
    echo "Dropbear SSH built successfully for $arch!"
    ls -la "$install_dir/bin/"
}

# Run the test
if [ -f "$BUILD_DIR/install/openssl-$TEST_ARCH/lib/libssl.a" ]; then
    echo "OpenSSL already built, proceeding with Dropbear test..."
else
    echo "OpenSSL not found, need to build it first..."
    echo "Run: ./scripts/build-minimal-bootstrap.sh download"
    echo "Then build OpenSSL manually."
    exit 1
fi

# Download and build
download_dropbear
build_dropbear_test

echo "Dropbear SSH build test completed successfully!"
echo "Dropbear is much simpler and more compatible with Android than OpenSSH."