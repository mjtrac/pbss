@REM ----------------------------------------------------------------------------
@REM Licensed to the Apache Software Foundation (ASF) under one
@REM or more contributor license agreements.  See the NOTICE file
@REM distributed with this work for additional information
@REM regarding copyright ownership.  The ASF licenses this file
@REM to you under the Apache License, Version 2.0 (the
@REM "License"); you may not use this file except in compliance
@REM with the License.  You may obtain a copy of the License at
@REM
@REM    https://www.apache.org/licenses/LICENSE-2.0
@REM
@REM Unless required by applicable law or agreed to in writing,
@REM software distributed under the License is distributed on an
@REM "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
@REM KIND, either express or implied.  See the License for the
@REM specific language governing permissions and limitations
@REM under the License.
@REM ----------------------------------------------------------------------------

@REM Maven Wrapper for Windows — compatible with cmd.exe on Windows 7+
@REM Downloads Apache Maven on first run; delegates all args to mvn.cmd.
@REM
@REM Usage:  mvnw.cmd [maven goals and options]
@REM Example: mvnw.cmd spring-boot:run

@echo off
setlocal enabledelayedexpansion

@REM ── Read distributionUrl from maven-wrapper.properties ────────────────────
set "PROPS=%~dp0.mvn\wrapper\maven-wrapper.properties"
if not exist "%PROPS%" (
    echo ERROR: Cannot find %PROPS% >&2
    exit /b 1
)

set "DIST_URL="
for /f "usebackq tokens=1,* delims==" %%A in ("%PROPS%") do (
    set "KEY=%%A"
    set "VAL=%%B"
    if "!KEY!"=="distributionUrl" set "DIST_URL=!VAL!"
)

if "%DIST_URL%"=="" (
    echo ERROR: distributionUrl not found in %PROPS% >&2
    exit /b 1
)

@REM ── Derive Maven version and cache directory ──────────────────────────────
@REM DIST_URL ends with something like apache-maven-3.9.6-bin.zip
for %%F in ("%DIST_URL%") do set "DIST_FILE=%%~nxF"
@REM Strip -bin.zip to get the stem (apache-maven-3.9.6)
set "DIST_STEM=%DIST_FILE:-bin.zip=%"

set "MAVEN_HOME=%USERPROFILE%\.m2\wrapper\dists\%DIST_STEM%\%DIST_STEM%"

@REM ── Download and unpack if not already cached ─────────────────────────────
if not exist "%MAVEN_HOME%\bin\mvn.cmd" (
    echo Downloading Apache Maven from %DIST_URL% ...

    set "PARENT_DIR=%USERPROFILE%\.m2\wrapper\dists\%DIST_STEM%"
    if not exist "!PARENT_DIR!" mkdir "!PARENT_DIR!"

    set "TMP_ZIP=%TEMP%\%DIST_STEM%-bin.zip"

    @REM Try curl first (available on Windows 10 1803+), then PowerShell
    where curl >nul 2>&1
    if %errorlevel%==0 (
        curl -fsSL -o "!TMP_ZIP!" "%DIST_URL%"
    ) else (
        powershell -NoProfile -ExecutionPolicy Bypass -Command ^
            "Invoke-WebRequest -Uri '%DIST_URL%' -OutFile '!TMP_ZIP!'"
    )

    if not exist "!TMP_ZIP!" (
        echo ERROR: Download failed. >&2
        exit /b 1
    )

    @REM Unpack with PowerShell Expand-Archive (Windows 10+) or jar (older)
    powershell -NoProfile -ExecutionPolicy Bypass -Command ^
        "Expand-Archive -LiteralPath '!TMP_ZIP!' -DestinationPath '!PARENT_DIR!' -Force" ^
        2>nul
    if not exist "%MAVEN_HOME%\bin\mvn.cmd" (
        @REM Fallback: use jar tool (available with any JDK)
        jar xf "!TMP_ZIP!" -C "!PARENT_DIR!"
    )

    del /q "!TMP_ZIP!" 2>nul
    echo Maven installed to %MAVEN_HOME%
)

if not exist "%MAVEN_HOME%\bin\mvn.cmd" (
    echo ERROR: Maven executable not found at %MAVEN_HOME%\bin\mvn.cmd >&2
    exit /b 1
)

@REM ── Delegate to Maven ─────────────────────────────────────────────────────
"%MAVEN_HOME%\bin\mvn.cmd" %*
