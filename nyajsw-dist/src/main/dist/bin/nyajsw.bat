@echo off
rem Runs nyajsw with the given arguments, for example: nyajsw.bat -q ..\conf\wrapper.conf
rem A switch given without a configuration file (-c -i -r -t -p -q and their long forms) gets
rem conf\wrapper.conf of this installation.
rem
rem The wrapper needs Java 21 or later. NYAJSW_JAVA names the java.exe to run it with; otherwise
rem JAVA_HOME's, otherwise java from the PATH. -i registers the service with the javaw.exe of that
rem Java unless wrapper.ntservice.java.command names another one. NYAJSW_OPTS holds options for the
rem wrapper JVM of this command, such as -Dwrapper.debug=true.
setlocal EnableExtensions
for %%i in ("%~dp0..") do set "NYAJSW_HOME=%%~fi"
rem No parenthesised blocks: a ")" in a path, as in "Program Files (x86)", would end them.
set "_nyajsw_java=java"
if defined JAVA_HOME set "_nyajsw_java=%JAVA_HOME:"=%\bin\java.exe"
if defined NYAJSW_JAVA set "_nyajsw_java=%NYAJSW_JAVA:"=%"
set _nyajsw_args=%*
if "%~2"=="" for %%s in (-c -i -r -t -p -q --console --install --remove --start --stop --query) do (
    if "%~1"=="%%s" set _nyajsw_args=%1 "%NYAJSW_HOME%\conf\wrapper.conf"
)
"%_nyajsw_java%" %NYAJSW_OPTS% -jar "%NYAJSW_HOME%\nyajsw.jar" %_nyajsw_args%
exit /b %ERRORLEVEL%
