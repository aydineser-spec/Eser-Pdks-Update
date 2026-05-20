@echo off 
chcp 65001 > nul 
title ESER PDKS - Seri Port RFID Okuyucu Kurulum Sihirbazi 
echo ======================================================== 
echo   ESER PDKS - SERI PORT (COM) DESTEGI YUKLEME ARACI 
echo ======================================================== 
echo. 
echo Bu arac, fiziksel RFID kart okuyucuyu bilgisayarin 
echo COM (Seri Port) arayuzu uzerinden okutabilmeniz icin 
echo gerekli olan "serialport" modulunu sisteme kuracaktir. 
echo. 
echo Devam etmek icin herhangi bir tusa basin... 
pause > nul 
echo. 
echo Lutfen bekleyin, modul indiriliyor ve kuruluyor... 
echo. 
call npm install serialport --no-audit --no-fund 
if %ERRORLEVEL% NEQ 0 ( 
    echo. 
    echo [HATA] Kurulum sirasinda bir hata olustu 
    echo Internet baglantinizi kontrol edin veya node/npm'in kurulu oldugundan emin olun. 
) else ( 
    echo. 
    echo [OK] Seri port surucusu basariyla yuklendi 
    echo Artik Ayarlar - Cihazlar sekmesinden COM portunu secebilirsiniz. 
) 
echo. 
pause 
