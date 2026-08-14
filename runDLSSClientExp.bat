@echo off
setlocal EnableExtensions

set "PROJECT_DIR=%~dp0"
set "JAVA_HOME=C:\Program Files\Java\jdk-25.0.4"
set "DLSS_SETUP_ATTEMPTED=0"

pushd "%PROJECT_DIR%" || goto :project_error
if not exist "%JAVA_HOME%\bin\java.exe" goto :java_error
set "PATH=%JAVA_HOME%\bin;%PATH%"

if not exist ".deps\cycles-dlss-install\cycles.exe" goto :setup_dlss
if not exist ".deps\cycles-dlss-build\lib\Release\cycles_integrator.lib" goto :setup_dlss
if not exist ".deps\cycles-dlss-install\nvngx_dlssd.dll" goto :setup_dlss
goto :check_dlss_payload

:setup_dlss
if "%DLSS_SETUP_ATTEMPTED%"=="1" goto :setup_error
set "DLSS_SETUP_ATTEMPTED=1"
echo [DLSS Experimental] Preparing the isolated Cycles and NVIDIA DLSS runtime...
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\scripts\setup-cycles.ps1" -ExperimentalDlss
if errorlevel 1 goto :setup_error

:check_dlss_payload
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$pairs=@(@('.deps\cycles-dlss-build\src\kernel\device\cuda\kernel_sm_120.cubin.zst','.deps\cycles-dlss-install\lib\kernel_sm_120.cubin.zst'),@('.deps\cycles-dlss-build\src\kernel\device\optix\kernel_optix.ptx.zst','.deps\cycles-dlss-install\lib\kernel_optix.ptx.zst'),@('.deps\cycles-dlss-build\src\kernel\device\optix\kernel_optix_mnee.ptx.zst','.deps\cycles-dlss-install\lib\kernel_optix_mnee.ptx.zst'),@('.deps\cycles-dlss-build\src\kernel\device\optix\kernel_optix_shader_raytrace.ptx.zst','.deps\cycles-dlss-install\lib\kernel_optix_shader_raytrace.ptx.zst')); foreach($pair in $pairs){if(-not (Test-Path -LiteralPath $pair[0] -PathType Leaf) -or -not (Test-Path -LiteralPath $pair[1] -PathType Leaf)){Write-Host ('[DLSS Experimental] Missing kernel payload: '+$pair[0]+' or '+$pair[1]); exit 2}; if((Get-FileHash -LiteralPath $pair[0] -Algorithm SHA256).Hash -ne (Get-FileHash -LiteralPath $pair[1] -Algorithm SHA256).Hash){Write-Host ('[DLSS Experimental] Stale installed kernel: '+$pair[1]); exit 1}}; exit 0"
if errorlevel 2 goto :setup_dlss
if errorlevel 1 goto :sync_dlss_install
goto :sync_dlss_runtime

:sync_dlss_install
echo [DLSS Experimental] Synchronizing rebuilt Cycles kernels into the install tree...
cmake.exe --install ".deps\cycles-dlss-build" --config Release
if errorlevel 1 goto :sync_error

:sync_dlss_runtime
echo [DLSS Experimental] Synchronizing Cycles kernels into the client runtime...
cmake.exe -E copy_directory ".deps\cycles-dlss-install\lib" "build\native-dlss\bin\lib"
if errorlevel 1 goto :sync_error

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

:sync_error
echo [error] The experimental DLSS kernel payload could not be synchronized. The client was not started.
popd
exit /b 1
