@echo off
setlocal EnableExtensions DisableDelayedExpansion
set "APP_ROOT=%~dp0"
set "REPORT=%APP_ROOT%DsLR-PCL-info.txt"
set "VERSION_DIR=%~1"

echo.
if not defined VERSION_DIR (
  echo Example: .minecraft\versions\26.1.2-Fabric 0.19.3
  set /p "VERSION_DIR=Enter Minecraft version folder path: "
)
if not defined VERSION_DIR (
  echo No Minecraft version folder supplied.
  exit /b 1
)

(
  echo ===== DsLR PCL environment report =====
  echo.
  echo [Minecraft version folder]
  echo %VERSION_DIR%
  echo.
  echo [Version JSON files]
  if exist "%VERSION_DIR%\*.json" (
    for %%F in ("%VERSION_DIR%\*.json") do echo %%~fF
  ) else (
    echo No JSON found. Use a versions\version-name folder.
  )
  echo.
  echo [Java found in PATH]
  where java.exe
  where javaw.exe
  echo.
  echo [Java version from PATH]
  java -version
  echo.
  echo [PCL actual Java]
  echo In PCL open Settings - Game - Game Java.
  echo Also check the target version settings for per-version Java.
  echo After launching the target version, search PCL logs for javaw.exe.
) > "%REPORT%" 2>&1

notepad "%REPORT%"
echo.
echo Report saved to: %REPORT%
echo Send the JSON path and actual javaw.exe path to DsLR.
echo.
pause
exit /b 0
