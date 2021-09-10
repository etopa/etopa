#!/bin/bash

# versions
export ANDROID_PLATFORM=31
export BUILDTOOLS_VERSION=31.0.0
export NDK_VERSION=23.0.7599858
export BUNDLETOOL_VERSION=1.8.0
export MINIFY_VERSION=2.9.21

if [ $# -eq 0 ]; then
  return
elif [ $1 == "ANDROID_PLATFORM" ]; then
  echo "$ANDROID_PLATFORM"
elif [ $1 == "BUILDTOOLS_VERSION" ]; then
  echo "$BUILDTOOLS_VERSION"
elif [ $1 == "NDK_VERSION" ]; then
  echo "$NDK_VERSION"
elif [ $1 == "BUNDLETOOL_VERSION" ]; then
  echo "$BUNDLETOOL_VERSION"
elif [ $1 == "MINIFY_VERSION" ]; then
  echo "$MINIFY_VERSION"
fi