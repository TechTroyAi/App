@echo off
rem Builds ScreenRecorder.exe natively on Windows.
rem Requires Go: https://go.dev/dl/ (winget install GoLang.Go)
setlocal
cd /d "%~dp0"

for /f "tokens=3" %%v in ('findstr /c:"const version" main.go') do set VERSION=%%~v
set VERSION=%VERSION:"=%

go build -trimpath -ldflags "-s -w" -o "..\artifacts\ScreenRecorder-v%VERSION%.exe" .
if errorlevel 1 (
  echo Build failed. Is Go installed? https://go.dev/dl/
  exit /b 1
)
echo Built ..\artifacts\ScreenRecorder-v%VERSION%.exe
endlocal
