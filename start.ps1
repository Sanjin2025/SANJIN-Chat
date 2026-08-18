# ==================== SanjinChat 启动脚本 ====================
# 功能：自动检测/安装 Node.js + 自动检测/安装依赖 + 彩色界面 + 启动服务

# ====================================================================
# TLS / WSS 开关：设为 $true 启用 HTTPS + wss://，设为 $false 使用 HTTP + ws://
# 启用后若证书缺失，将自动用 openssl 生成自签名证书（仅用于内网/测试）
# 生产环境请用正式证书替换 certs\fullchain.pem 和 certs\privkey.pem
# ====================================================================
$EnableTLS = $false

# 证书路径（相对脚本目录）
$CertFile = "certs\fullchain.pem"
$KeyFile  = "certs\privkey.pem"

# ===== 彩色输出辅助函数 =====
function Write-OK($msg)    { Write-Host "  [OK] " -ForegroundColor Green  -NoNewline; Write-Host $msg }
function Write-Warn($msg)  { Write-Host "  [警告] " -ForegroundColor Yellow -NoNewline; Write-Host $msg }
function Write-Err($msg)   { Write-Host "  [错误] " -ForegroundColor Red    -NoNewline; Write-Host $msg }
function Write-Info($msg)  { Write-Host "  [信息] " -ForegroundColor Cyan   -NoNewline; Write-Host $msg }
function Write-Step($msg)  { Write-Host ""; Write-Host "  ▶ $msg" -ForegroundColor Magenta }

# ===== 检测命令是否存在 =====
function Test-Cmd($cmd) { return [bool](Get-Command $cmd -ErrorAction SilentlyContinue) }

# ===== 查找 openssl（PATH 不可用时回退到 Git for Windows 自带） =====
function Find-Openssl {
    $c = Get-Command openssl -ErrorAction SilentlyContinue
    if ($c) { return $c.Source }
    $g = Get-Command git -ErrorAction SilentlyContinue
    if ($g) {
        $gitDir = Split-Path $g.Source
        $cands = @(
            "$gitDir\..\mingw64\bin\openssl.exe",
            "$gitDir\..\mingw32\bin\openssl.exe",
            "$gitDir\openssl.exe"
        )
        foreach ($p in $cands) { if (Test-Path $p) { return $p } }
    }
    return $null
}

# ===== 刷新 PATH（安装 Node.js 后调用） =====
function Refresh-Path {
    $m = [Environment]::GetEnvironmentVariable("Path", "Machine")
    $u = [Environment]::GetEnvironmentVariable("Path", "User")
    $env:Path = "$m;$u"
}

# ===== Banner =====
Write-Host ""
Write-Host "  ============================================" -ForegroundColor Cyan
Write-Host "            SanjinChat 聊天室" -ForegroundColor Cyan
Write-Host "         WebSocket Chat System v2.0" -ForegroundColor Cyan
Write-Host "  ============================================" -ForegroundColor Cyan

# ================================================================
# 0. 选择传输模式：ws / wss
# ================================================================
Write-Host ""
Write-Host "  请选择传输模式：" -ForegroundColor Cyan
Write-Host "    [1] ws   (HTTP  明文，内网/测试推荐)" -ForegroundColor White
Write-Host "    [2] wss  (HTTPS 加密，公网/生产推荐)" -ForegroundColor White
$choice = Read-Host "  输入 1 或 2 (默认 1)"
switch ("$choice".Trim()) {
    "2" { $EnableTLS = $true }
    default { $EnableTLS = $false }
}
if ($EnableTLS) {
    Write-OK "已选择 wss (HTTPS + TLS 加密)"
} else {
    Write-OK "已选择 ws (HTTP 明文)"
}

# ================================================================
# 1. 检测 / 安装 Node.js
# ================================================================
Write-Step "检测 Node.js"

if (Test-Cmd node) {
    $nodeVer = (node -v 2>$null)
    Write-OK "Node.js $nodeVer"
} else {
    Write-Warn "未检测到 Node.js"
    $nodeInstalled = $false

    # ---- 方法1: winget ----
    if (Test-Cmd winget) {
        Write-Info "使用 winget 安装 Node.js LTS..."
        try {
            winget install OpenJS.NodeJS.LTS --accept-package-agreements --accept-source-agreements 2>$null
            Refresh-Path
            if (Test-Cmd node) {
                $nodeInstalled = $true
                Write-OK "Node.js $(node -v 2>$null)"
            }
        } catch {
            Write-Warn "winget 安装失败，尝试备用方案..."
        }
    }

    # ---- 方法2: 下载便携版（无需管理员权限） ----
    if (-not $nodeInstalled) {
        Write-Info "下载 Node.js 便携版..."
        try {
            # 获取最新 LTS 版本号
            $ProgressPreference = 'SilentlyContinue'
            $idx = Invoke-RestMethod -Uri "https://nodejs.org/dist/index.json" -UseBasicParsing
            $ver = ($idx | Where-Object { $_.lts } | Select-Object -First 1).version
            Write-Info "最新 LTS 版本: $ver"

            $arch = if ([Environment]::Is64BitOperatingSystem) { "win-x64" } else { "win-x86" }
            $url  = "https://nodejs.org/dist/$ver/node-$ver-$arch.zip"
            $zip  = "$env:TEMP\sanjinchat-node.zip"
            $dest = "$PSScriptRoot\nodejs"

            Write-Info "下载中 (可能需要1-2分钟)..."
            Invoke-WebRequest -Uri $url -OutFile $zip -UseBasicParsing

            Write-Info "解压中..."
            if (Test-Path $dest) { Remove-Item $dest -Recurse -Force }
            Expand-Archive -Path $zip -DestinationPath $PSScriptRoot -Force
            Rename-Item "$PSScriptRoot\node-$ver-$arch" "nodejs" -Force
            Remove-Item $zip -Force

            # 加入 PATH
            $env:Path = "$dest;$env:Path"

            if (Test-Cmd node) {
                $nodeInstalled = $true
                Write-OK "Node.js $(node -v 2>$null) (便携版)"
            }
        } catch {
            Write-Err "下载失败: $($_.Exception.Message)"
        }
    }

    # ---- 安装失败 ----
    if (-not $nodeInstalled) {
        Write-Host ""
        Write-Err "Node.js 自动安装失败"
        Write-Host "  请手动安装: " -NoNewline
        Write-Host "https://nodejs.org" -ForegroundColor Yellow
        Read-Host "`n  按回车键退出"
        exit 1
    }
}

# ===== 检测 npm =====
if (-not (Test-Cmd npm)) {
    Write-Err "未找到 npm，请重新安装 Node.js"
    Read-Host "  按回车键退出"
    exit 1
}
Write-OK "npm $(npm -v 2>$null)"

# ================================================================
# 2. 检测 / 安装依赖包
# ================================================================
Write-Step "检测依赖包"

$deps = @("ws", "bcryptjs", "qrcode")
$missing = @()

foreach ($dep in $deps) {
    node -e "require('$dep')" 2>$null
    if ($LASTEXITCODE -eq 0) {
        Write-OK $dep
    } else {
        Write-Warn "缺失 $dep"
        $missing += $dep
    }
}

if ($missing.Count -gt 0) {
    Write-Host ""
    Write-Info "正在安装缺失依赖: $($missing -join ', ')"
    npm install $missing
    if ($LASTEXITCODE -ne 0) {
        Write-Err "依赖安装失败，请检查网络连接"
        Read-Host "  按回车键退出"
        exit 1
    }
    Write-OK "依赖安装完成"
} else {
    Write-OK "所有依赖包已就绪"
}

# ================================================================
# 3. 创建数据目录
# ================================================================
Write-Step "初始化数据目录"

$dirs = @(
    "data\uploads\avatars",
    "data\uploads\logo",
    "data\uploads\images",
    "data\uploads\audios"
)
foreach ($d in $dirs) {
    $p = Join-Path $PSScriptRoot $d
    if (-not (Test-Path $p)) { New-Item -ItemType Directory -Path $p -Force | Out-Null }
}
Write-OK "数据目录就绪"

# ================================================================
# 3.5 TLS / WSS 配置
# ================================================================
if ($EnableTLS) {
    Write-Step "配置 TLS / WSS 模式"
    $certsDir = Join-Path $PSScriptRoot "certs"
    if (-not (Test-Path $certsDir)) { New-Item -ItemType Directory -Path $certsDir -Force | Out-Null }
    $certPath = Join-Path $PSScriptRoot $CertFile
    $keyPath  = Join-Path $PSScriptRoot $KeyFile

    if ((Test-Path $certPath) -and (Test-Path $keyPath)) {
        Write-OK "已存在 TLS 证书"
    } else {
        # 尝试用 openssl 生成自签名证书（先找 PATH，再回退到 Git 自带）
        $opensslExe = Find-Openssl
        if ($opensslExe) {
            Write-Info "使用 openssl 生成自签名证书: $opensslExe"
            $subj = "/CN=localhost"
            & $opensslExe req -x509 -newkey rsa:2048 -nodes -days 3650 `
                -keyout $keyPath -out $certPath -subj $subj 2>$null
            if ((Test-Path $certPath) -and (Test-Path $keyPath)) {
                Write-OK "自签名证书已生成 (有效期 10 年)"
                Write-Warn "浏览器会提示不信任，属正常现象（自签名）"
            } else {
                Write-Err "openssl 生成证书失败"
                Write-Err "已关闭 TLS，回退到 ws:// 模式"
                $EnableTLS = $false
            }
        } else {
            Write-Err "未找到 openssl，无法自动生成证书"
            Write-Host "  请手动放置证书到: $CertFile / $KeyFile" -ForegroundColor Yellow
            Write-Err "已关闭 TLS，回退到 ws:// 模式"
            $EnableTLS = $false
        }
    }

    if ($EnableTLS) {
        $env:SANJIN_TLS = "1"
        $env:SANJIN_TLS_CERT = $certPath
        $env:SANJIN_TLS_KEY = $keyPath
        Write-OK "TLS 已启用 → https:// + wss://"
    }
} else {
    Write-Step "TLS 关闭 → http:// + ws://"
    $env:SANJIN_TLS = "0"
}

# ================================================================
# 4. 启动服务器
# ================================================================
Write-Host ""
Write-Host "  ============================================" -ForegroundColor Green
Write-Host "         SanjinChat 服务启动中..." -ForegroundColor Green
Write-Host "  ============================================" -ForegroundColor Green
Write-Host ""
if ($EnableTLS) {
    Write-Host "  访问地址: " -NoNewline; Write-Host "https://localhost:8181" -ForegroundColor Cyan
    Write-Host "  WebSocket: " -NoNewline; Write-Host "wss://localhost:8181/ws" -ForegroundColor Cyan
} else {
    Write-Host "  访问地址: " -NoNewline; Write-Host "http://localhost:8181" -ForegroundColor Cyan
    Write-Host "  WebSocket: " -NoNewline; Write-Host "ws://localhost:8181/ws" -ForegroundColor Cyan
}
Write-Host ""

node server.js

if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Err "服务异常退出 (退出码: $LASTEXITCODE)"
    Read-Host "  按回车键退出"
}
