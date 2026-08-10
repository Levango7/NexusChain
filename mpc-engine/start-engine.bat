@echo off
echo Starting MPC Engine...
cd /d "%~dp0"
set PATH=D:\msys64\mingw64\bin;%PATH%
target\debug\mpc-engine.exe