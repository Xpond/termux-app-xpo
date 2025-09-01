#!/bin/bash

# XPort NDK Environment Setup Script
# This script sets up the Android NDK environment for building the minimal bootstrap

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

log_info "Setting up Android NDK environment..."

# Check if ANDROID_HOME is set
if [ -z "${ANDROID_HOME:-}" ]; then
    log_error "ANDROID_HOME is not set"
    log_info "Please set ANDROID_HOME to your Android SDK path"
    log_info "Example: export ANDROID_HOME=/opt/android-sdk"
    exit 1
fi

log_info "Android SDK found at: $ANDROID_HOME"

# Set NDK path
NDK_VERSION="22.1.7171670"
ANDROID_NDK_ROOT="$ANDROID_HOME/ndk/$NDK_VERSION"

# Check if NDK exists
if [ ! -d "$ANDROID_NDK_ROOT" ]; then
    log_error "Android NDK $NDK_VERSION not found at: $ANDROID_NDK_ROOT"
    log_info "Please install it with: sdkmanager \"ndk;$NDK_VERSION\""
    exit 1
fi

log_success "Android NDK found at: $ANDROID_NDK_ROOT"

# Export environment variables
export ANDROID_NDK_ROOT="$ANDROID_NDK_ROOT"

log_info "Environment variables set:"
log_info "  ANDROID_HOME=$ANDROID_HOME"
log_info "  ANDROID_NDK_ROOT=$ANDROID_NDK_ROOT"

# Create environment file for sourcing
cat > "$(dirname "$0")/ndk-env.sh" << EOF
# Android NDK Environment Variables
# Source this file: source scripts/ndk-env.sh

export ANDROID_HOME="$ANDROID_HOME"
export ANDROID_NDK_ROOT="$ANDROID_NDK_ROOT"

echo "✅ Android NDK environment loaded"
echo "   ANDROID_NDK_ROOT=$ANDROID_NDK_ROOT"
EOF

log_success "Environment setup complete!"
log_info "To use in future sessions, run: source scripts/ndk-env.sh"

# Test NDK toolchain
log_info "Testing NDK toolchain..."
if [ -f "$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android21-clang" ]; then
    log_success "NDK toolchain verified - ready for cross-compilation"
else
    log_warning "NDK toolchain not found at expected location"
    log_info "This might indicate a different NDK structure"
fi

log_info "You can now run: ./scripts/build-minimal-bootstrap.sh check"