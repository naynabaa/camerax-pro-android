@rem Gradle Wrapper Windows Batch Script
@if "%DEBUG%"=="" @echo off
set DIRNAME=%~dp0
set CLASSPATH=%DIRNAME%gradle\wrapper\gradle-wrapper.jar
if not exist "%CLASSPATH%" (
    echo Downloading Gradle Wrapper...
    powershell -Command "New-Item -ItemType Directory -Force -Path '%DIRNAME%gradle\wrapper'; (New-Object Net.WebClient).DownloadFile('https://raw.githubusercontent.com/gradle/gradle/v8.4.0/gradle/wrapper/gradle-wrapper.jar', '%CLASSPATH%')"
)
java -Xmx2048m -jar "%CLASSPATH%" %*
