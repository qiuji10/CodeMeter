@echo off
setlocal
set "APP_HOME=%~dp0"
set "WRAPPER_JAR=%APP_HOME%gradle\wrapper\gradle-wrapper.jar"
set "WRAPPER_URL=https://raw.githubusercontent.com/gradle/gradle/v9.4.1/gradle/wrapper/gradle-wrapper.jar"
set "WRAPPER_SHA256=55243ef57851f12b070ad14f7f5bb8302daceeebc5bce5ece5fa6edb23e1145c"

if not exist "%WRAPPER_JAR%" (
  echo Bootstrapping verified Gradle wrapper...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $dir=Split-Path -Parent '%WRAPPER_JAR%'; New-Item -ItemType Directory -Force -Path $dir | Out-Null; $tmp='%WRAPPER_JAR%.tmp'; Invoke-WebRequest -UseBasicParsing -Uri '%WRAPPER_URL%' -OutFile $tmp; $actual=(Get-FileHash $tmp -Algorithm SHA256).Hash.ToLower(); if ($actual -ne '%WRAPPER_SHA256%') { Remove-Item $tmp -Force; throw ('Gradle wrapper checksum mismatch. Actual: ' + $actual) }; Move-Item $tmp '%WRAPPER_JAR%' -Force"
  if errorlevel 1 exit /b 1
)

java -jar "%WRAPPER_JAR%" %*
exit /b %ERRORLEVEL%
