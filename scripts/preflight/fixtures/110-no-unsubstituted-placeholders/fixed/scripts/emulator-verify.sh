#!/usr/bin/env bash
PKG=com.example.demo
adb shell am start -n "$PKG/.MainActivity"
