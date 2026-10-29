#!/bin/bash
# core 沙箱驱动（抽核手术产物——stdin/stdout JSON 行协议）
export JAVA_HOME=/home/z/dl/jdk21
CLASSES=/home/z/my-project/autochess/app/build/tmp/kotlin-classes/release
CHESSLIB=$(find /home/z/.gradle/caches -name "chesslib-1.3.7.jar" | grep -v sources | head -1)
KSTD=$(find /home/z/.gradle/caches -name "kotlin-stdlib-1.9.2*.jar" | grep -v sources | head -1)
CL3=$(find /home/z/.gradle/caches -name "commons-lang3-3*.jar" | grep -v sources | head -1)
exec $JAVA_HOME/bin/java -cp "$CLASSES:$CHESSLIB:$KSTD:$CL3" com.rogic.autochess.core.MainKt
