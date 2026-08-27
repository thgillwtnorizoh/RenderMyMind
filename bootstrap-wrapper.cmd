@echo off
setlocal
set VERSION=9.5.0
set CACHE=%TEMP%\rhythm-tracker-gradle-%VERSION%
set ZIP=%CACHE%\gradle-%VERSION%-bin.zip
set DIST=%CACHE%\gradle-%VERSION%

if not exist "%CACHE%" mkdir "%CACHE%"

if not exist "%DIST%\bin\gradle.bat" (
  echo Downloading Gradle %VERSION% from services.gradle.org...
  powershell -NoProfile -Command "Invoke-WebRequest -UseBasicParsing -Uri 'https://services.gradle.org/distributions/gradle-%VERSION%-bin.zip' -OutFile '%ZIP%'"
  if errorlevel 1 exit /b 1

  echo Extracting Gradle...
  powershell -NoProfile -Command "Expand-Archive -Path '%ZIP%' -DestinationPath '%CACHE%' -Force"
  if errorlevel 1 exit /b 1
)

echo Generating Gradle wrapper...
call "%DIST%\bin\gradle.bat" wrapper --gradle-version %VERSION%
if errorlevel 1 exit /b 1

echo.
echo Wrapper ready. You can now run: gradlew.bat assembleDebug
endlocal
