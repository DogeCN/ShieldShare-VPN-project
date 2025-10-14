#!/bin/bash

echo "========================================"
echo "   ShieldShare Build Setup"
echo "========================================"
echo

echo "Creating necessary build directories..."

# Create build/tmp directory
BUILD_TMP_DIR="build/tmp"
if [ ! -d "$BUILD_TMP_DIR" ]; then
    mkdir -p "$BUILD_TMP_DIR"
    echo "✅ Created: $BUILD_TMP_DIR"
else
    echo "✅ Already exists: $BUILD_TMP_DIR"
fi

# Create app/build/tmp directory
APP_BUILD_TMP_DIR="app/build/tmp"
if [ ! -d "$APP_BUILD_TMP_DIR" ]; then
    mkdir -p "$APP_BUILD_TMP_DIR"
    echo "✅ Created: $APP_BUILD_TMP_DIR"
else
    echo "✅ Already exists: $APP_BUILD_TMP_DIR"
fi

echo
echo "Build directories are ready!"
echo "You can now run: ./gradlew assembleDebug"
echo
