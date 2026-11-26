# ============================================================
# exam-scan 一键部署到阿里云 ECS
# 流程：提交并推送最新代码到 GitHub → SSH 到服务器 git pull → 构建 → 重启
# 首次运行会询问服务器 IP/用户名/端口并保存到 aliyun.env，以后不再询问。
# 密码：ssh 会提示输入服务器登录密码；如需免密，把本机公钥追加到服务器的
#       ~/.ssh/authorized_keys（见 README 或把公钥内容发给服务器管理员配置）。
# ============================================================
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)   # 仓库根目录
$envFile = Join-Path $PSScriptRoot 'aliyun.env'

# ---- 读取或首次生成服务器配置 ----
if (!(Test-Path $envFile)) {
  Write-Host "首次使用，请配置阿里云服务器信息（保存到 deploy/windows/aliyun.env，以后不再询问）" -ForegroundColor Yellow
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

Set-Location $root

# ---- [1/3] 提交并推送最新代码 ----
Write-Host "`n[1/3] 提交并推送最新代码到 GitHub ..." -ForegroundColor Cyan
git add -A
$staged = git diff --cached --name-only
if ($staged) { git commit -m "deploy: 一键部署自动提交" | Out-Null }
git push origin main 2>$null
if ($LASTEXITCODE -ne 0) {
  Write-Host "  推送遇到网络波动，10 秒后自动重试 ..." -ForegroundColor Yellow
  Start-Sleep 10
  git push origin main
  if ($LASTEXITCODE -ne 0) {
    Write-Host '[错误] 代码推送失败（GitHub 网络问题），请稍后重试' -ForegroundColor Red
    Read-Host '回车退出'; exit 1
  }
}

# ---- [2/3] 服务器拉取 + 构建 + 重启 ----
Write-Host "`n[2/3] 连接 $sshTarget，服务器拉取代码并构建部署（大改动约 5~10 分钟，请勿关闭窗口）..." -ForegroundColor Cyan
ssh -p $sshPort -o ServerAliveInterval=30 -o ServerAliveCountMax=10 $sshTarget `
  "cd /opt/exam-scan && git pull && bash deploy/linux/deploy-from-git.sh"
if ($LASTEXITCODE -ne 0) {
  Write-Host '[错误] 服务器部署失败，请把上方输出发给开发者排查' -ForegroundColor Red
  Read-Host '回车退出'; exit 1
}

# ---- [3/3] 完成 ----
Write-Host "`n[3/3] 部署完成！" -ForegroundColor Green
Write-Host "访问：http://$($cfg['SSH_HOST']):8091/    手机拍照：https://$($cfg['SSH_HOST']):8443/（自签证书，选继续访问）"
Write-Host "提示：浏览器记得 Ctrl+F5 强制刷新，否则看到的是旧页面。"
Read-Host '回车退出'
