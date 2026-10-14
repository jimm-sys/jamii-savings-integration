@echo off
rem Starts WSO2 API Manager with JDK 21
for /d %%d in ("C:\Program Files\Eclipse Adoptium\jdk-21*") do set "JAVA_HOME=%%d"
set "PATH=%JAVA_HOME%\bin;%PATH%"
cd /d C:\wso2\wso2am-4.6.0\bin
api-manager.bat
