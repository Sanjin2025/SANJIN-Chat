@echo off
chcp 65001 >nul
title 一键推送所有文件到 GitHub
echo ============================================
echo  一键推送 SanjinChat 所有文件到 GitHub
echo ============================================
echo.

cd /d "C:\Users\Guoha\Desktop\sanjinchat_new"

echo [1/5] 检查 Git 状态...
git status
echo.

echo [2/5] 添加所有文件到暂存区...
git add .
echo.

echo [3/5] 提交到本地仓库...
set /p commit_msg="请输入提交信息 (直接回车使用默认): "
if "%commit_msg%"=="" set commit_msg="更新：SanjinChat 全平台代码"
git commit -m %commit_msg%
echo.

echo [4/5] 推送到 GitHub...
git push -u origin main
echo.

echo [5/5] 推送完成！
echo.
echo ============================================
echo  如果推送成功，请访问:
echo  https://github.com/Sanjin2025/SANJIN-Chat
echo ============================================
echo.
pause