# !bin/bash
export JAVA_HOME=~/.jdks/corretto-17.0.20.1/
./gradlew clean
./gradlew buildPlugin

# run in sandbox
# ./gradlew runIde

# runIde 沙箱（推荐做功能验证）
#export JAVA_HOME=/home/yangrenshuai/Documents/Tools/pycharm-2025.3.1.1/jbr
#export GRADLE_USER_HOME=$PWD/.tools/gradle-home
#export TMPDIR=$PWD/.tools/tmp
#.tools/gradle-9.7.1/bin/gradle runIde --no-daemon --console=plain "-PplatformLocalPath=$PWD/.tools/android-studio"
