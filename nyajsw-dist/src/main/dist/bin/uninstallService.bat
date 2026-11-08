@echo off
rem Removes the Windows service. Run it as Administrator.
rem Usage: uninstallService.bat [wrapper.conf], by default ..\conf\wrapper.conf
call "%~dp0nyajsw.bat" -r %*
exit /b %ERRORLEVEL%
