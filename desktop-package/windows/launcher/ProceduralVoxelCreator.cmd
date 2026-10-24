@echo off
setlocal
set "PVC_ROOT=%~dp0..\..\.."
"%PVC_ROOT%\windows\runtime\bin\java.exe" --enable-native-access=ALL-UNNAMED -Xmx2g -jar "%PVC_ROOT%\jars\ProceduralVoxelCreator.jar" %*
if errorlevel 1 pause
endlocal
