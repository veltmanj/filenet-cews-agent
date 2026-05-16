@echo off
setlocal

if "%~3"=="" (
  echo Usage: %~nx0 ^<cell^> ^<node^> ^<server^> [agent_dir]
  exit /b 1
)

set "CELL=%~1"
set "NODE=%~2"
set "SERVER=%~3"
set "AGENT_DIR=%~4"
if "%AGENT_DIR%"=="" set "AGENT_DIR=C:\IBM\Agent\filenet-cews-agent"

set "AGENT_JAR=%AGENT_DIR%\filenet-cews-agent-0.1.5.jar"
set "AGENT_JAR=%AGENT_JAR:\=/%"
set "SCRIPT_DIR=%~dp0"
set "WSADMIN_CMD=%WSADMIN_CMD%"
if "%WSADMIN_CMD%"=="" set "WSADMIN_CMD=wsadmin.bat"

call %WSADMIN_CMD% %WSADMIN_ARGS% -lang jython -f "%SCRIPT_DIR%configure-javaagent.py" remove "%CELL%" "%NODE%" "%SERVER%" "%AGENT_JAR%"
exit /b %ERRORLEVEL%