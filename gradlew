#!/bin/sh
# Gradle Wrapper Script
APP_HOME=$(cd "`dirname "$0"`" && pwd)
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

# If real gradle is installed in PATH (like on GitHub Actions or local dev), use it directly
if command -v gradle >/dev/null 2>&1; then
    exec gradle "$@"
fi

if [ -f "$CLASSPATH" ]; then
    exec java -Xmx2048m -jar "$CLASSPATH" "$@"
fi

echo "Gradle is not installed and wrapper JAR is missing. Please install Gradle 8.4 or use GitHub Actions."
exit 1
