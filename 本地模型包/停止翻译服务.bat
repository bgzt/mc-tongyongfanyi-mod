@echo off
taskkill /IM ollama.exe /F >nul 2>&1
taskkill /IM "ollama app.exe" /F >nul 2>&1
echo Local translation service stopped.
timeout /t 2 /nobreak >nul
