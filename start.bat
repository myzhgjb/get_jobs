@echo off
REM ============================================================
REM  Get Jobs - launcher
REM  Forces JDK 21 (project requires it) and starts the backend.
REM  Management UI: http://localhost:9527
REM ============================================================
setlocal
set "JAVA_HOME=C:\software\java\jdk-21"
set "PATH=%JAVA_HOME%\bin;%PATH%"

REM Guard: clear the injected SERVER__PORT so port 9527 is used
set "SERVER__PORT="
set "SERVER_PORT="

cd /d "%~dp0"

echo Using JDK:
"%JAVA_HOME%\bin\java" -version
echo.
echo Starting Get Jobs backend, UI will open at http://localhost:9527
echo Press Ctrl+C to stop.
echo.

call gradlew.bat bootRun --console=plain
endlocal
