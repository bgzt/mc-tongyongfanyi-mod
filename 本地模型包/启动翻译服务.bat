@echo off
cd /d "%~dp0"
title TongyongFanyi - Local Translation Service
set "OLLAMA_MODELS=%~dp0models"
set "OLLAMA_NUM_PARALLEL=4"
if not exist "%OLLAMA_MODELS%" mkdir "%OLLAMA_MODELS%"
REM 自动检测本地加速器 SOCKS5 代理（v2rayN/Clash 等），让模型下载也能走代理
for %%P in (10808 10809 7890 7897 1080 2080 1081 7891 8888 8080) do (
    powershell -NoProfile -Command "$c=New-Object Net.Sockets.TcpClient; try{$c.Connect('127.0.0.1',%%P);$s=$c.GetStream();$s.Write([byte[]](5,1,0),0,3);$b=New-Object byte[] 2;if($s.Read($b,0,2)-eq 2 -and $b[0]-eq 5 -and $b[1]-eq 0){'SOCKS'};$c.Close()}catch{}" | findstr /C:"SOCKS" >nul 2>&1 && (
        set "ALL_PROXY=socks5://127.0.0.1:%%P"
        set "HTTP_PROXY=socks5://127.0.0.1:%%P"
        set "HTTPS_PROXY=socks5://127.0.0.1:%%P"
        echo Detected local proxy at 127.0.0.1:%%P, downloads will use it.
        goto :proxyfound
    )
)
:proxyfound
echo Starting local translation model service...
start "" /min "%~dp0ollama\ollama.exe" serve
echo Waiting for model service...
:wait
timeout /t 1 /nobreak >nul
curl -s --max-time 2 http://127.0.0.1:11434/api/tags >nul 2>&1
if errorlevel 1 goto wait
echo.
echo Model service is ready. Available models:
"%~dp0ollama\ollama.exe" list
echo.
echo You can now start the game. Keep this window open.
echo Close this window or run stop-translation-service.bat to stop.
pause >nul
