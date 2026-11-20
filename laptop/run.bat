@echo off
setlocal
cd /d "%~dp0"

echo === Onyx Pad receiver ==============================================
where python >nul 2>nul
if errorlevel 1 (
    echo Python 3 was not found on PATH.
    echo Install it from https://www.python.org/downloads/ and tick "Add python.exe to PATH".
    pause
    exit /b 1
)

echo Installing requirements...
python -m pip install --upgrade pip >nul
python -m pip install -r requirements.txt
if errorlevel 1 (
    echo.
    echo Failed to install the requirements.
    pause
    exit /b 1
)

echo.
python receiver.py
echo.
pause
