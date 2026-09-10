# !bin/bash
export JAVA_HOME=~/.jdks/corretto-17.0.20.1/
./gradlew clean
./gradlew buildPlugin
# output:

# run in sandbox
# ./gradlew runIde

# runIde 沙箱（推荐做功能验证）
#export JAVA_HOME=/home/yangrenshuai/Documents/Tools/pycharm-2025.3.1.1/jbr
#export GRADLE_USER_HOME=$PWD/.tools/gradle-home
#export TMPDIR=$PWD/.tools/tmp
#.tools/gradle-9.7.1/bin/gradle runIde --no-daemon --console=plain "-PplatformLocalPath=$PWD/.tools/android-studio"


# Test
# JAVA_HOME="$PWD/.tools/android-studio/jbr" ./gradlew test --tests "com.aicode.plugin.chat.SessionRepositoryTest" 2>&1 | grep -E "^e:.*Session|FAILED|BUILD" | head -5
# JAVA_HOME="$PWD/.tools/android-studio/jbr" ./gradlew test buildPlugin 2>&1 | grep -E "^e:|FAILED|BUILD|tests completed" | head -10

# compile
# JAVA_HOME="$PWD/.tools/android-studio/jbr" ./gradlew compileKotlin --rerun-tasks 2>&1 | grep -E "^e:|FAILED|BUILD" | head -5; sed -n '210,240p' src/main/kotlin/com/aicode/plugin/chat/ChatController.kt
