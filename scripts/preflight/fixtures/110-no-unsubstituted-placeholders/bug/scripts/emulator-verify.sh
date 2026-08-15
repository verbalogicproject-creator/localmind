#!/usr/bin/env bash
PKG={{APPLICATION_ID}}
adb shell am start -n "$PKG/.MainActivity"
