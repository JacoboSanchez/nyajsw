@echo off
rem Runs the application in this console; Ctrl+C stops it.
rem Usage: runConsole.bat [wrapper.conf], by default ..\conf\wrapper.conf
call "%~dp0nyajsw.bat" -c %*
exit /b %ERRORLEVEL%
