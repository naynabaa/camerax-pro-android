#!/bin/sh
# Gradle Wrapper Script
APP_HOME=$(cd "`dirname "$0"`" && pwd)
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

# If real gradle is installed in PATH (like on GitHub Actions or local dev), use it directly
if command -v gradle >/dev/null 2>&1; then
    exec gradle "$@"
fi

# Fetch official Gradle wrapper JAR if missing
if [ ! -f "$CLASSPATH" ]; then
    mkdir -p "$APP_HOME/gradle/wrapper"
    curl -sLo "$CLASSPATH" "https://raw.githubusercontent.com/gradle/gradle/v8.4.0/gradle/wrapper/gradle-wrapper.jar" 2>/dev/null || \
    wget -qO "$CLASSPATH" "https://raw.githubusercontent.com/gradle/gradle/v8.4.0/gradle/wrapper/gradle-wrapper.jar" 2>/dev/null || true
fi

if [ -f "$CLASSPATH" ]; then
    exec java -Xmx2048m -jar "$CLASSPATH" "$@"
fi

echo "Gradle is not installed and wrapper JAR is missing. Please install Gradle 8.4 or use GitHub Actions."
exit 1
