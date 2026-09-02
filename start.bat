@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"

set "RUNTIME_DIR=%~dp0.run"
set "PID_FILE=%RUNTIME_DIR%\localmind.pid"
set "CHROMA_PID_FILE=%RUNTIME_DIR%\chroma.pid"
set "OUT_LOG=%RUNTIME_DIR%\localmind.out.log"
set "ERR_LOG=%RUNTIME_DIR%\localmind.err.log"
set "CHROMA_OUT_LOG=%RUNTIME_DIR%\chroma.out.log"
set "CHROMA_ERR_LOG=%RUNTIME_DIR%\chroma.err.log"
set "CHROMA_DATA=%~dp0chroma-data"

where mvn.cmd >nul 2>&1 || (echo [ERROR] Maven was not found in PATH. & exit /b 1)
set "CHROMA_EXE="
for /f "delims=" %%I in ('where chroma.exe 2^>nul') do if not defined CHROMA_EXE set "CHROMA_EXE=%%I"
if not defined CHROMA_EXE for /f "delims=" %%I in ('py -c "import os,site,sys; print(os.path.join(site.USER_BASE,'Python'+str(sys.version_info.major)+str(sys.version_info.minor),'Scripts','chroma.exe'))"') do set "CHROMA_EXE=%%I"
if not exist "!CHROMA_EXE!" (echo [ERROR] Chroma CLI was not found. Install it with: py -m pip install chromadb==1.0.20 & exit /b 1)
if not exist "%RUNTIME_DIR%" mkdir "%RUNTIME_DIR%"
if not exist "%CHROMA_DATA%" mkdir "%CHROMA_DATA%"

if exist "%PID_FILE%" (
    set /p APP_PID=<"%PID_FILE%"
    tasklist /FI "PID eq !APP_PID!" 2>nul | findstr /R /C:"[ ]!APP_PID![ ]" >nul && (
        echo [OK] LocalMind is already running ^(PID !APP_PID!^).
        exit /b 0
    )
    del /q "%PID_FILE%"
)

echo Starting Chroma...
if exist "%CHROMA_PID_FILE%" (
    set /p CHROMA_PID=<"%CHROMA_PID_FILE%"
    tasklist /FI "PID eq !CHROMA_PID!" 2>nul | findstr /R /C:"[ ]!CHROMA_PID![ ]" >nul || del /q "%CHROMA_PID_FILE%"
)
if not exist "%CHROMA_PID_FILE%" (
    powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$p = Start-Process -FilePath '!CHROMA_EXE!' -ArgumentList @('run','--path','%CHROMA_DATA%','--host','127.0.0.1','--port','8000') -WorkingDirectory '%~dp0' -RedirectStandardOutput '%CHROMA_OUT_LOG%' -RedirectStandardError '%CHROMA_ERR_LOG%' -WindowStyle Hidden -PassThru; $p.Id | Set-Content -Encoding ascii '%CHROMA_PID_FILE%'" || exit /b 1
)
call :wait_port Chroma 8000 90 || exit /b 1
call :wait_port MySQL 3306 30 || exit /b 1
echo Starting LocalMind...
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$p = Start-Process -FilePath 'mvn.cmd' -ArgumentList 'spring-boot:run' -WorkingDirectory '%~dp0' -RedirectStandardOutput '%OUT_LOG%' -RedirectStandardError '%ERR_LOG%' -WindowStyle Hidden -PassThru; $p.Id | Set-Content -Encoding ascii '%PID_FILE%'" || exit /b 1
call :wait_port LocalMind 8080 120 || (
    echo [ERROR] LocalMind did not start. Check logs in %RUNTIME_DIR%.
    exit /b 1
)

echo.
echo LocalMind is running at http://localhost:8080
echo Logs: %OUT_LOG% and %ERR_LOG%
exit /b 0

:wait_port
set "SERVICE_NAME=%~1"
set "SERVICE_PORT=%~2"
set /a "RETRIES=%~3 / 2"
for /L %%I in (1,1,!RETRIES!) do (
    powershell.exe -NoProfile -Command "$c = New-Object Net.Sockets.TcpClient; try { $c.Connect('127.0.0.1', %SERVICE_PORT%); exit 0 } catch { exit 1 } finally { $c.Dispose() }" >nul 2>&1 && (
        echo [OK] %SERVICE_NAME% is ready on port %SERVICE_PORT%.
        exit /b 0
    )
    ping 127.0.0.1 -n 3 >nul
)
echo [ERROR] %SERVICE_NAME% did not become ready on port %SERVICE_PORT%.
exit /b 1

