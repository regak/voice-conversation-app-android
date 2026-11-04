#!/bin/bash
export JAVA_HOME=/opt/data/android-toolchain/jdk-17.0.13+11
export ANDROID_HOME=/opt/data/android-toolchain/sdk
export PATH="$JAVA_HOME/bin:/opt/data/android-toolchain/gradle-8.9/bin:$PATH"
cd /opt/data/cache/scratch/voice-conversation-app-android
gradle assembleDebug --console=plain > build_output.log 2>&1
echo "EXIT:$?" >> build_output.log
