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

set "AGENT_JAR=%AGENT_DIR%\filenet-cews-agent-0.1.4.jar"
set "AGENT_JAR=%AGENT_JAR:\=/%"
set "OUTPUT_FILE=%AGENT_DIR%\cews-capture.ndjson"
set "OUTPUT_FILE=%OUTPUT_FILE:\=/%"
set "AGENT_SPEC=-javaagent:%AGENT_JAR%=profile=filenet-cews-low-overhead,output=%OUTPUT_FILE%"
set "SCRIPT_DIR=%~dp0"
set "WSADMIN_CMD=%WSADMIN_CMD%"
if "%WSADMIN_CMD%"=="" set "WSADMIN_CMD=wsadmin.bat"

call %WSADMIN_CMD% %WSADMIN_ARGS% -lang jython -f "%SCRIPT_DIR%configure-javaagent.py" add "%CELL%" "%NODE%" "%SERVER%" "%AGENT_SPEC%"
exit /b %ERRORLEVEL%