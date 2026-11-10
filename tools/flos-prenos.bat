@echo off
rem Flos: move ALL data (sales, products, settings, bank, EET certificate) from the old
rem Flos on the phone to the new Flos, once. Phone connected by USB with USB debugging on.
setlocal EnableExtensions
cd /d "%~dp0"
set PKG=cz.flos.pos
set ADB=%~dp0platform-tools\adb.exe
set BACKUP=%~dp0flos-data-backup.tar

echo.
echo ===== Flos data transfer =====
echo Phone: Settings - About phone - tap "Build number" 7x, then Developer options - USB debugging ON.
echo Connect the phone with a USB cable and allow "USB debugging" on the phone screen.
echo.

if not exist "%ADB%" (
  echo Downloading Android platform-tools from Google...
  curl.exe -L -o platform-tools.zip https://dl.google.com/android/repository/platform-tools-latest-windows.zip || goto :fail
  tar.exe -xf platform-tools.zip || goto :fail
)
"%ADB%" start-server
echo.
choice /c UW /m "Phone connected by USB cable (U) or over Wi-Fi (W)"
if errorlevel 2 call :wifi || goto :fail
echo Waiting for the phone...
"%ADB%" wait-for-device
"%ADB%" shell pm path %PKG% | find "package:" >nul || (echo Flos is not installed on this phone. & goto :fail)

rem ---- 1. Save the data of the installed Flos ----
if exist "%BACKUP%" (
  echo A backup already exists: %BACKUP%
  echo Keeping it. Delete it by hand only if you want a fresh one.
) else (
  echo [1/4] Saving the data...
  "%ADB%" shell am force-stop %PKG%
  "%ADB%" exec-out "run-as %PKG% sh -c 'cd /data/data/%PKG% && tar -cf - $(ls -d databases shared_prefs files no_backup 2>/dev/null)'" > "%BACKUP%"
)
tar.exe -tf "%BACKUP%" | findstr /b /c:"databases/flos.db" >nul || (echo The backup has no database. Nothing was changed on the phone. & del "%BACKUP%" & goto :fail)
tar.exe -tf "%BACKUP%" | findstr /b /c:"shared_prefs/flos_settings.xml" >nul || (echo The backup has no settings. Nothing was changed on the phone. & del "%BACKUP%" & goto :fail)
echo Backup OK:
tar.exe -tvf "%BACKUP%"
rem Old app too, so it could be put back if anything goes wrong.
for /f "tokens=2 delims=:" %%p in ('"%ADB%" shell pm path %PKG%') do "%ADB%" pull %%p flos-old.apk >nul

rem ---- 2. Download the new Flos ----
rem The repository is private: download flos.apk in the browser (signed in to GitHub) into this folder.
if exist flos.apk copy /y flos.apk flos-new.apk >nul
if not exist flos-new.apk (
  echo [2/4] Downloading the new Flos...
  curl.exe -f -L -o flos-new.apk https://github.com/hzm737-hash/flos-pos/releases/latest/download/flos.apk
)
if not exist flos-new.apk goto :wifi
rem Wireless debugging (Android 11+): phone and computer on the same Wi-Fi.
echo.
echo On the phone: Developer options - Wireless debugging ON - "Pair device with pairing code".
set /p PAIR=Type the IP address and port shown under the code (for example 192.168.1.20:37099): 
set /p CODE=Type the 6-digit Wi-Fi pairing code: 
"%ADB%" pair %PAIR% %CODE% || exit /b 1
echo.
echo Now look at the main Wireless debugging screen: "IP address and Port" (a different port).
set /p CONN=Type that IP address and port (for example 192.168.1.20:41235): 
"%ADB%" connect %CONN% | find "connected to" >nul || (echo Could not connect to %CONN%. & exit /b 1)
echo Connected over Wi-Fi.
exit /b 0

:nodownload
for %%A in (flos-new.apk) do set APKSIZE=%%~zA
if %APKSIZE% LSS 1000000 goto :nodownload
echo [2/4] New Flos ready.

echo.
echo [3/4] The old Flos will now be removed and the new one installed. Your data is saved in:
echo   %BACKUP%
choice /m "Continue"
if errorlevel 2 goto :fail
"%ADB%" uninstall %PKG%
"%ADB%" install flos-new.apk || (echo Install failed, putting the old app back. & "%ADB%" install flos-old.apk & goto :restore)

:restore
rem ---- 4. Put the data back ----
echo [4/4] Restoring the data...
"%ADB%" shell am force-stop %PKG%
"%ADB%" exec-in "run-as %PKG% sh -c 'cd /data/data/%PKG% && tar -xf -'" < "%BACKUP%"
"%ADB%" shell "run-as %PKG% sh -c 'cd /data/data/%PKG% && [ -f databases/flos.db ] && [ -f shared_prefs/flos_settings.xml ] && echo RESTORED_OK'" | find "RESTORED_OK" >nul && goto :restored
echo Trying another way...
"%ADB%" push "%BACKUP%" /data/local/tmp/flos-data.tar
"%ADB%" shell chmod 644 /data/local/tmp/flos-data.tar
"%ADB%" shell "run-as %PKG% sh -c 'cd /data/data/%PKG% && tar -xf /data/local/tmp/flos-data.tar'"
"%ADB%" shell rm /data/local/tmp/flos-data.tar
"%ADB%" shell "run-as %PKG% sh -c 'cd /data/data/%PKG% && [ -f databases/flos.db ] && [ -f shared_prefs/flos_settings.xml ] && echo RESTORED_OK'" | find "RESTORED_OK" >nul || (echo Data could not be restored. The backup is safe in %BACKUP%. & goto :fail)
:restored
"%ADB%" shell "run-as %PKG% ls databases shared_prefs"
"%ADB%" shell monkey -p %PKG% 1 >nul
echo.
echo DONE. Flos is open on the phone with your data. Keep flos-data-backup.tar somewhere safe.
pause
exit /b 0

:nodownload
del flos-new.apk 2>nul
echo.
echo The new Flos could not be downloaded here (the repository is private).
echo Open https://github.com/hzm737-hash/flos-pos/releases/latest in the browser,
echo download flos.apk into this folder: %~dp0
echo and start flos-prenos.bat again. Nothing was changed on the phone.
pause
exit /b 1

:fail
echo.
echo STOPPED. Send a photo of this window to Claude.
pause
exit /b 1
