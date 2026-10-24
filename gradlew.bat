@rem Gradle Wrapper Windows Batch Script
@if "%DEBUG%"=="" @echo off
where gradle >nul 2>nul
if %ERRORLEVEL% equ 0 (
    gradle %*
    exit /b %ERRORLEVEL%
)
set DIRNAME=%~dp0
set CLASSPATH=%DIRNAME%gradle\wrapper\gradle-wrapper.jar
if exist "%CLASSPATH%" (
    java -Xmx2048m -jar "%CLASSPATH%" %*
    exit /b %ERRORLEVEL%
)
echo Gradle is not installed and wrapper JAR is missing. Please install Gradle.
exit /b 1
