@echo off
setlocal EnableExtensions

set "PROJECT_DIR=%~dp0"
set "GRADLE_VERSION=9.2.1"
set "GRADLE_ZIP=%USERPROFILE%\Downloads\gradle-9.2.1-bin.zip"
set "GRADLE_ROOT=%PROJECT_DIR%.tools"
set "GRADLE_HOME=%GRADLE_ROOT%\gradle-%GRADLE_VERSION%"
set "GRADLE_EXE=%GRADLE_HOME%\bin\gradle.bat"
set "EXPECTED_GRADLE_SHA256=72F44C9F8EBCB1AF43838F45EE5C4AA9C5444898B3468AB3F4AF7B6076C5BC3F"
set "JAVA_HOME=C:\Program Files\Java\jdk-17"
if not defined GRADLE_USER_HOME set "GRADLE_USER_HOME=%USERPROFILE%\.gradle"

pushd "%PROJECT_DIR%" || goto :project_error
if not exist "%JAVA_HOME%\bin\java.exe" goto :java_error
if exist "%GRADLE_EXE%" goto :ready
if exist "%GRADLE_HOME%\" goto :partial_install_error
if not exist "%GRADLE_ZIP%" goto :zip_error

echo [setup] Verifying Gradle %GRADLE_VERSION% archive...
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$actual=(Get-FileHash -LiteralPath $env:GRADLE_ZIP -Algorithm SHA256).Hash; if($actual -ne $env:EXPECTED_GRADLE_SHA256){throw ('Gradle ZIP SHA-256 mismatch: '+$actual)}"
if errorlevel 1 goto :hash_error

if not exist "%GRADLE_ROOT%\" mkdir "%GRADLE_ROOT%"
if errorlevel 1 goto :extract_error

echo [setup] Extracting to "%GRADLE_HOME%"...
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; Expand-Archive -LiteralPath $env:GRADLE_ZIP -DestinationPath $env:GRADLE_ROOT"
if errorlevel 1 goto :extract_error
if not exist "%GRADLE_EXE%" goto :extract_error

:ready
set "PATH=%JAVA_HOME%\bin;%PATH%"
echo [ready] Gradle: "%GRADLE_HOME%"
echo [ready] Gradle cache: "%GRADLE_USER_HOME%"
echo [ready] Gradle launcher JVM: "%JAVA_HOME%"

if /I "%~1"=="setup" goto :setup_only
if "%~1"=="" goto :run_client
call "%GRADLE_EXE%" --no-daemon %*
goto :finish

:setup_only
call "%GRADLE_EXE%" --no-daemon --version
goto :finish

:run_client
call "%GRADLE_EXE%" --no-daemon runClient
goto :finish

:finish
set "RESULT=%ERRORLEVEL%"
popd
exit /b %RESULT%

:project_error
echo [error] Cannot enter project directory: "%PROJECT_DIR%"
exit /b 1

:java_error
echo [error] JDK 17 was not found: "%JAVA_HOME%\bin\java.exe"
popd
exit /b 1

:zip_error
echo [error] Gradle archive was not found: "%GRADLE_ZIP%"
popd
exit /b 1

:hash_error
echo [error] Gradle archive verification failed. Nothing was extracted.
popd
exit /b 1

:partial_install_error
echo [error] An incomplete Gradle directory already exists: "%GRADLE_HOME%"
echo [error] Inspect it manually. This script will not overwrite or delete it.
popd
exit /b 1

:extract_error
echo [error] Gradle extraction failed. This script will not delete partial files.
popd
exit /b 1
