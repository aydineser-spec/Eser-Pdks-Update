@echo off
title Eser PDKS - Watchdog
cd /d "%~dp0"

echo ============================================
echo   ESER PDKS - WATCHDOG AKTIF
echo   Her 5 dakikada server kontrol edilir.
echo   Bu pencereyi kapatmayin!
echo ============================================
echo.

:loop
:: Port 5000'de LISTENING var mi kontrol et
netstat -ano | find ":5000" | find "LISTENING" > nul 2>&1
if errorlevel 1 (
    echo [%date% %time%] SERVER KAPALI - Baslatiliyor...
    start "" /min cmd /k "node server/index.cjs"
    echo [%date% %time%] Server baslatildi. 15 saniye bekleniyor...
    timeout /t 15 /nobreak > nul
) else (
    echo [%date% %time%] Server aktif. Sonraki kontrol 5 dakika sonra.
)

timeout /t 300 /nobreak > nul
goto loop
