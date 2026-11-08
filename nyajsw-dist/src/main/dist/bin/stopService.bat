@echo off
rem Stops the Windows service and waits until it has stopped.
rem Usage: stopService.bat [wrapper.conf], by default ..\conf\wrapper.conf
call "%~dp0nyajsw.bat" -p %*
exit /b %ERRORLEVEL%
