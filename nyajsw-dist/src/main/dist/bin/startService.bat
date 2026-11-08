@echo off
rem Starts the Windows service and waits until it runs.
rem Usage: startService.bat [wrapper.conf], by default ..\conf\wrapper.conf
call "%~dp0nyajsw.bat" -t %*
exit /b %ERRORLEVEL%
