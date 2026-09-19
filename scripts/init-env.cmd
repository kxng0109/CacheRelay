@echo off
rem Windows wrapper: runs the PowerShell bootstrap without changing the machine execution policy.
rem Only -Force is forwarded (no %* passthrough: arguments cannot inject commands).
rem Advanced options (e.g. -OutFile) call the script directly:
rem   powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0init-env.ps1" -OutFile <path>
if "%~1"=="" (
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0init-env.ps1"
  exit /b %ERRORLEVEL%
)
if /I "%~1"=="-Force" (
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0init-env.ps1" -Force
  exit /b %ERRORLEVEL%
)
echo Usage: %~nx0 [-Force] 1>&2
echo Advanced: powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0init-env.ps1" -OutFile ^<path^> 1>&2
exit /b 2
