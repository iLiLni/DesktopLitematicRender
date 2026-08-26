@echo off
setlocal EnableExtensions DisableDelayedExpansion
set "APP_ROOT=%~dp0"
set "EXIT_CODE=1"

cd /d "%APP_ROOT%"
if errorlevel 1 goto :folder_error

title DsLR Controller
echo.
echo [DsLR Controller]
echo Checking Node.js...
where node.exe >nul 2>&1
if errorlevel 1 goto :node_missing

for /f "tokens=1 delims=." %%V in ('node --version') do set "NODE_MAJOR=%%V"
set "NODE_MAJOR=%NODE_MAJOR:v=%"
echo %NODE_MAJOR% | findstr /r "^[0-9][0-9]*$" >nul
if errorlevel 1 goto :node_version_error
if %NODE_MAJOR% LSS 20 goto :node_too_old

if not exist "%APP_ROOT%src\shell\server.mjs" goto :source_missing

echo Node.js:
node --version
echo.
echo Starting local UI...
echo Keep this window open while using DsLR.
echo.

node "%APP_ROOT%src\shell\server.mjs" --open --port=0
set "EXIT_CODE=%ERRORLEVEL%"

echo.
echo Server stopped. Exit code: %EXIT_CODE%.
goto :finish

:node_missing
echo.
echo ERROR: Node.js was not found in PATH.
echo Install Node.js 20 or newer, then run this file again.
goto :finish

:node_version_error
echo.
echo ERROR: Node.js version could not be read.
goto :finish

:node_too_old
echo.
echo ERROR: Node.js %NODE_MAJOR% is too old. Node.js 20 or newer is required.
goto :finish

:source_missing
echo.
echo ERROR: src\shell\server.mjs was not found.
echo Extract the complete ZIP before running this file.
goto :finish

:folder_error
echo.
echo ERROR: Could not enter the application folder.

:finish
echo.
echo Press any key to close this window.
pause >nul
exit /b %EXIT_CODE%
