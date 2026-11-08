@echo off
rem Shows the Windows service's state.
rem Usage: queryService.bat [wrapper.conf], by default ..\conf\wrapper.conf
call "%~dp0nyajsw.bat" -q %*
exit /b %ERRORLEVEL%
