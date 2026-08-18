@echo off
chcp 65001 >nul
title SanjinChat
cd /d "%~dp0"
powershell -ExecutionPolicy Bypass -NoProfile -File "%~dp0start.ps1"
pause
