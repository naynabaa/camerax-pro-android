#!/bin/sh
# Gradle Wrapper Unix Script
APP_HOME=$(cd "`dirname "$0"`" && pwd)
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

if [ ! -f "$CLASSPATH" ]; then
    echo "Downloading Gradle Wrapper..."
    mkdir -p "$APP_HOME/gradle/wrapper"
    curl -sLo "$CLASSPATH" "https://raw.githubusercontent.com/gradle/gradle/v8.4.0/gradle/wrapper/gradle-wrapper.jar" || true
fi

exec java -Xmx2048m -jar "$CLASSPATH" "$@"
