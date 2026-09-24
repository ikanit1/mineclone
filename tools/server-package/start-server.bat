@echo off
setlocal
cd /d "%~dp0"
java -cp "Mineclone.jar;lib/*" com.mineclone.server.ServerMain %*
exit /b %errorlevel%
