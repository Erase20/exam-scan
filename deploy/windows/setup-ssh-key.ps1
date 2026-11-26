# ============================================================
# 配置 SSH 免密登录阿里云服务器（只需运行一次，以后一键部署不再输密码）
# 流程：读取 aliyun.env 中的服务器信息 → 把本机公钥追加到服务器
#       ~/.ssh/authorized_keys → 测试免密连接。
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

# ---- 本机公钥（没有则自动生成） ----
$pub = "$env:USERPROFILE\.ssh\id_ed25519.pub"
if (!(Test-Path $pub)) {
  Write-Host "本机没有 SSH 密钥，正在生成 ..." -ForegroundColor Cyan
  ssh-keygen -t ed25519 -N '""' -C 'exam-scan-deploy' -f "$env:USERPROFILE\.ssh\id_ed25519" | Out-Null
}
$key = (Get-Content $pub -Raw).Trim()

# ---- 把公钥追加到服务器（需输入最后一次密码） ----
Write-Host "`n正在把公钥写入服务器 $sshTarget ..." -ForegroundColor Cyan
Write-Host "（这是最后一次输服务器密码，输入后回车）" -ForegroundColor Yellow
$cmd = 'mkdir -p ~/.ssh && chmod 700 ~/.ssh && (grep -qF "' + $key + '" ~/.ssh/authorized_keys 2>/dev/null || echo "' + $key + '" >> ~/.ssh/authorized_keys) && chmod 600 ~/.ssh/authorized_keys'
type $pub | ssh -p $sshPort $sshTarget $cmd
if ($LASTEXITCODE -ne 0) {
  Write-Host '[错误] 公钥写入失败，请确认 IP/用户名/密码无误后重试' -ForegroundColor Red
  Read-Host '回车退出'; exit 1
}

# ---- 验证免密 ----
Write-Host "`n验证免密连接 ..." -ForegroundColor Cyan
ssh -p $sshPort -o BatchMode=yes -o ConnectTimeout=10 $sshTarget "echo OK && hostname"
if ($LASTEXITCODE -eq 0) {
  Write-Host "`n免密登录配置成功！以后双击「一键部署到阿里云.bat」不再需要密码。" -ForegroundColor Green
} else {
  Write-Host "`n[提示] 公钥已写入，但免密验证未通过（服务器可能禁用了公钥登录），请把上方输出发给开发者" -ForegroundColor Yellow
}
Read-Host '回车退出'
