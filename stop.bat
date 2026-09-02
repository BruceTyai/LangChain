@echo off
setlocal EnableExtensions
cd /d "%~dp0"

set "PID_FILE=%~dp0.run\localmind.pid"
set "CHROMA_PID_FILE=%~dp0.run\chroma.pid"

if exist "%PID_FILE%" (
    set /p APP_PID=<"%PID_FILE%"
    call :stop_app || exit /b 1
    del /q "%PID_FILE%" 2>nul
) else (
    echo No LocalMind PID file found; the application may already be stopped.
)

echo Stopping Chroma...
if exist "%CHROMA_PID_FILE%" (
    set /p CHROMA_PID=<"%CHROMA_PID_FILE%"
    call :stop_chroma || exit /b 1
    del /q "%CHROMA_PID_FILE%" 2>nul
) else (
    echo Chroma PID file was not found; Chroma may already be stopped.
)

echo LocalMind and Chroma are stopped. MySQL is still running.
exit /b 0

:stop_app
tasklist /FI "PID eq %APP_PID%" 2>nul | findstr /R /C:"[ ]%APP_PID%[ ]" >nul
if errorlevel 1 (
    echo LocalMind process is not running.
) else (
    echo Stopping LocalMind ^(PID %APP_PID%^)...
    taskkill /PID %APP_PID% /T /F || exit /b 1
)
exit /b 0

:stop_chroma
tasklist /FI "PID eq %CHROMA_PID%" 2>nul | findstr /R /C:"[ ]%CHROMA_PID%[ ]" >nul
if errorlevel 1 (
    echo Chroma process is not running.
) else (
    taskkill /PID %CHROMA_PID% /T /F || exit /b 1
)
exit /b 0