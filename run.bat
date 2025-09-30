@echo off
REM Greenplum Assistant Run Script for Windows
REM Usage: run.bat [options]
REM Options:
REM   -b, --build     Build the project before running
REM   -c, --clean     Clean build (mvn clean compile)
REM   -h, --help      Show this help message

setlocal enabledelayedexpansion

set BUILD=false
set CLEAN=false

REM Parse command line arguments
:parse_args
if "%~1"=="" goto check_env
if /i "%~1"=="-b" (
    set BUILD=true
    shift
    goto parse_args
)
if /i "%~1"=="--build" (
    set BUILD=true
    shift
    goto parse_args
)
if /i "%~1"=="-c" (
    set BUILD=true
    set CLEAN=true
    shift
    goto parse_args
)
if /i "%~1"=="--clean" (
    set BUILD=true
    set CLEAN=true
    shift
    goto parse_args
)
if /i "%~1"=="-h" goto show_help
if /i "%~1"=="--help" goto show_help

echo Error: Unknown option %~1
echo Use -h or --help for usage information
exit /b 1

:show_help
echo Greenplum Assistant Run Script
echo.
echo Usage: run.bat [options]
echo.
echo Options:
echo   -b, --build     Build the project before running (mvn compile)
echo   -c, --clean     Clean build before running (mvn clean compile)
echo   -h, --help      Show this help message
echo.
echo Examples:
echo   run.bat                # Run without building
echo   run.bat -b             # Build and run
echo   run.bat -c             # Clean build and run
echo.
echo Environment Variables Required:
echo   OPENAI_API_KEY          Your OpenAI API key
echo.
echo Optional Environment Variables:
echo   MCP_CLIENT_ENABLED      Enable MCP client (default: false)
echo   DOCS_INGEST_ON_STARTUP  Ingest docs on startup (default: true)
exit /b 0

:check_env
REM Check if OPENAI_API_KEY is set
if "%OPENAI_API_KEY%"=="" (
    echo Error: OPENAI_API_KEY environment variable is not set
    echo Please set it with: set OPENAI_API_KEY=your-api-key
    exit /b 1
)

REM Build if requested
if "%BUILD%"=="true" (
    if "%CLEAN%"=="true" (
        echo ==^> Running clean build...
        call mvnw.cmd clean compile -DskipTests
    ) else (
        echo ==^> Building project...
        call mvnw.cmd compile -DskipTests
    )
    
    if errorlevel 1 (
        echo X Build failed
        exit /b 1
    )
    echo V Build successful
    echo.
)

REM Display configuration
echo ==^> Starting Greenplum Assistant
echo Version: Spring Boot 3.5.6, Spring AI 1.1.0-SNAPSHOT
if "%MCP_CLIENT_ENABLED%"=="" (
    echo MCP Client: false
) else (
    echo MCP Client: %MCP_CLIENT_ENABLED%
)
if "%DOCS_INGEST_ON_STARTUP%"=="" (
    echo Ingest on Startup: true
) else (
    echo Ingest on Startup: %DOCS_INGEST_ON_STARTUP%
)
echo.

REM Run the application
echo ==^> Running application...
call mvnw.cmd spring-boot:run
