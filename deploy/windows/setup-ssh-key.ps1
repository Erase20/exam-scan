# ============================================================
# 配置 SSH 免密登录阿里云服务器（只需运行一次，以后一键部署不再输密码）
# 流程：读取 aliyun.env 中的服务器信息 → 把本机公钥追加到服务器
#       ~/.ssh/authorized_keys（远程 tr 去除 Windows 换行符 \r）→ 测试免密连接。
# 运行过程中需要输入【最后一次】服务器密码。
# ============================================================
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$envFile = Join-Path $root 'deploy\windows\aliyun.env'

# ---- 读取或首次生成服务器配置（与一键部署脚本共用同一份） ----
if (!(Test-Path $envFile)) {
  Write-Host "请填写阿里云服务器信息（保存到 aliyun.env，一键部署共用）" -ForegroundColor Yellow
  $sshUser = Read-Host 'SSH 用户名（一般为 root）'
  $sshHost = Read-Host '服务器公网 IP'
  $sshPort = Read-Host 'SSH 端口（默认 22，直接回车即可）'
  if (-not $sshPort) { $sshPort = '22' }
  "SSH_USER=$sshUser`nSSH_HOST=$sshHost`nSSH_PORT=$sshPort" | Set-Content -Encoding ascii $envFile
}
$cfg = @{}
Get-Content $envFile | ForEach-Object {
  if ($_ -match '^\s*([A-Z_]+)=(.*)$') { $cfg[$Matches[1]] = $Matches[2].Trim() }
}
$sshTarget = "$($cfg['SSH_USER'])@$($cfg['SSH_HOST'])"
$sshPort = $cfg['SSH_PORT']

# ---- 本机公钥（没有则用临时 bat 生成，避免 PowerShell 引号转义导致带上口令） ----
$keyPath = "$env:USERPROFILE\.ssh\id_ed25519"
$pub = "$keyPath.pub"
if (!(Test-Path $pub)) {
  Write-Host "本机没有 SSH 密钥，正在生成 ..." -ForegroundColor Cyan
  New-Item -ItemType Directory -Force "$env:USERPROFILE\.ssh" | Out-Null
  $tmpBat = Join-Path $env:TEMP 'exam-scan-keygen.bat'
  "ssh-keygen -t ed25519 -N `"`" -C exam-scan-deploy -f `"$keyPath`"" | Set-Content -Encoding ascii $tmpBat
  & cmd.exe /c $tmpBat | Out-Null
  Remove-Item $tmpBat -Force -ErrorAction SilentlyContinue
  if (!(Test-Path $pub)) {
    Write-Host '[错误] SSH 密钥生成失败，请手动执行: ssh-keygen -t ed25519' -ForegroundColor Red
    Read-Host '回车退出'; exit 1
  }
}
$key = (Get-Content $pub -Raw).Trim()
if (-not $key) {
  Write-Host '[错误] 公钥文件为空' -ForegroundColor Red
  Read-Host '回车退出'; exit 1
}

# ---- 把公钥追加到服务器（需输入最后一次密码）----
# 公钥经管道传给远程 tr -d '\r' 再追加，避免 Windows 换行符 \r 混入 authorized_keys
# 导致 sshd 校验失败（经典坑）；用远程 cat 收 stdin，避免命令行引号转义问题。
Write-Host "`n正在把公钥写入服务器 $sshTarget ..." -ForegroundColor Cyan
Write-Host "（这是最后一次输服务器密码，输入后回车）" -ForegroundColor Yellow
Get-Content $pub | ssh -p $sshPort -o StrictHostKeyChecking=accept-new $sshTarget `
  "mkdir -p ~/.ssh && chmod 700 ~/.ssh && touch ~/.ssh/authorized_keys && cat >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys && sed -i 's/\r$//' ~/.ssh/authorized_keys && echo PUBKEY_WRITTEN"
if ($LASTEXITCODE -ne 0) {
  Write-Host '[错误] 公钥写入失败，请确认 IP/用户名/密码无误后重试' -ForegroundColor Red
  Read-Host '回车退出'; exit 1
}

# ---- 验证免密 ----
Write-Host "`n验证免密连接 ..." -ForegroundColor Cyan
ssh -p $sshPort -o BatchMode=yes -o ConnectTimeout=10 $sshTarget "echo OK && hostname"
if ($LASTEXITCODE -eq 0) {
  Write-Host "`n免密登录配置成功！以后双击「一键部署到阿里云.bat」全程免输密码。" -ForegroundColor Green
  Write-Host "配置完成后请回到 AI 助手对话，让它继续排查服务器问题。" -ForegroundColor Green
} else {
  Write-Host "`n[提示] 公钥已写入，但免密验证未通过（服务器可能禁用了公钥登录），请把上方输出发给开发者" -ForegroundColor Yellow
}
Read-Host '回车退出'
