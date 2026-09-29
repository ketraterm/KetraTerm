@echo off
setlocal EnableExtensions DisableDelayedExpansion
if "%1"=="version" goto run_version
if "%1"=="config" goto run_config
if "%1"=="info" goto run_info
if "%1"=="help" goto run_help
if "%1"=="--help" goto run_help
if "%1"=="-h" goto run_help
if "%1"=="" goto run_help

echo Usage: ketra [version ^| config ^| info ^| help]
exit /b 1

:run_version
setlocal EnableDelayedExpansion
echo KetraTerm version !KetraTerm_VERSION!
goto end

:run_config
if not defined KetraTerm_CONFIG_PATH (
    echo KetraTerm_CONFIG_PATH is not set.
    exit /b 1
)
if not defined EDITOR goto default_editor
if exist "%EDITOR%" goto editor_executable
%EDITOR% "%KetraTerm_CONFIG_PATH%"
exit /b %errorlevel%

:editor_executable
"%EDITOR%" "%KetraTerm_CONFIG_PATH%"
exit /b %errorlevel%

:default_editor
notepad "%KetraTerm_CONFIG_PATH%"
exit /b %errorlevel%

:run_info
setlocal EnableDelayedExpansion
echo KetraTerm System Information:
echo   Version:       !KetraTerm_VERSION!
echo   Config Path:   !KetraTerm_CONFIG_PATH!
echo   OS:            !KetraTerm_OS!
echo   JVM:           !KetraTerm_JVM!
echo.
echo Environment Variables:
echo   KetraTerm_VERSION       Active version of the terminal application
echo   KetraTerm_CONFIG_PATH   Path to standalone settings file (config.toml)
echo   KetraTerm_OS            Current OS and architecture details
echo   KetraTerm_JVM           Java runtime version and vendor details
echo.
echo Useful Commands:
echo   notepad "!KetraTerm_CONFIG_PATH!"      - Edit configuration in Notepad
goto end

:run_help
echo KetraTerm Companion CLI Tool
echo.
echo Usage:
echo   ketra ^<command^> [options]
echo.
echo Commands:
echo   version           Display active KetraTerm version
echo   config            Open config.toml in your default editor
echo   info              Print system diagnostic and path information
echo   help              Display this help
goto end

:end
exit /b 0
