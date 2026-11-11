@echo off
rem Dino Walker: "dino start" / "dino exit" for cmd.exe and PowerShell.
setlocal
set "DINO_CMD="
if /i "%~1"=="start" set "DINO_CMD=start"
if /i "%~1"=="exit" set "DINO_CMD=exit"
if /i "%~1"=="stop" set "DINO_CMD=exit"
if not defined DINO_CMD (
  echo usage: dino start ^| dino exit 1>&2
  exit /b 2
)
if not defined DINO_WALKER_STATE_DIR goto notconnected
if not exist "%DINO_WALKER_STATE_DIR%\" goto notconnected
>"%DINO_WALKER_STATE_DIR%\command.tmp" echo %DINO_CMD%
move /y "%DINO_WALKER_STATE_DIR%\command.tmp" "%DINO_WALKER_STATE_DIR%\command" >nul
exit /b %ERRORLEVEL%
:notconnected
echo dino: not connected to the IDE (open a new terminal tab, or use Find Action: Dino: Start) 1>&2
exit /b 1
