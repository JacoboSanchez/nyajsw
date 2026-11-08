@echo off
rem Installs the Windows service. Run it as Administrator.
rem Usage: installService.bat [wrapper.conf], by default ..\conf\wrapper.conf
call "%~dp0nyajsw.bat" -i %*
exit /b %ERRORLEVEL%
