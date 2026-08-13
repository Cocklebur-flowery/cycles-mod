@echo off
setlocal EnableExtensions

set "PROJECT_DIR=%~dp0"
set "JAVA_HOME=C:\Program Files\Java\jdk-25.0.4"

pushd "%PROJECT_DIR%" || goto :project_error
if not exist "%JAVA_HOME%\bin\java.exe" goto :java_error
set "PATH=%JAVA_HOME%\bin;%PATH%"

if not exist ".deps\cycles-dlss-install\cycles.exe" goto :setup_dlss
if not exist ".deps\cycles-dlss-build\lib\Release\cycles_integrator.lib" goto :setup_dlss
if not exist ".deps\cycles-dlss-install\nvngx_dlssd.dll" goto :setup_dlss
goto :run_client

:setup_dlss
echo [DLSS Experimental] Preparing the isolated Cycles and NVIDIA DLSS runtime...
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\scripts\setup-cycles.ps1" -ExperimentalDlss
if errorlevel 1 goto :setup_error

:run_client
echo [DLSS Experimental] Starting the Vulkan client with DLSS Ray Reconstruction enabled...
call ".\gradlew.bat" --no-daemon runClient -PexperimentalDlss=true
set "RESULT=%ERRORLEVEL%"
popd
exit /b %RESULT%

:project_error
echo [error] Cannot enter project directory: "%PROJECT_DIR%"
exit /b 1

:java_error
echo [error] JDK 25 was not found: "%JAVA_HOME%\bin\java.exe"
popd
exit /b 1

:setup_error
echo [error] The experimental DLSS Cycles setup failed. The client was not started.
popd
exit /b 1
