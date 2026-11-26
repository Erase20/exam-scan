# ============================================================
# v3: 修复服务器端公钥登录被拒
# 根因链：v1 管道安装把 BOM 带进 authorized_keys 首行 → v2 清洗时
#         grep "^ssh-" 匹配不到带 BOM 的行 → 文件被清空。
# v3 方案：公钥 base64 编码后直接内嵌进 ssh 命令重写文件——
#         无管道、无编码转换，从根上杜绝 BOM/\r 污染。
# 运行需输入一次服务器密码。
# ============================================================
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$envFile = Join-Path $root 'deploy\windows\aliyun.env'
$cfg = @{}
Get-Content $envFile | ForEach-Object {
  if ($_ -match '^\s*([A-Z_]+)=(.*)$') { $cfg[$Matches[1]] = $Matches[2].Trim() }
}
$sshTarget = "$($cfg['SSH_USER'])@$($cfg['SSH_HOST'])"
$sshPort = $cfg['SSH_PORT']

# 本机公钥 → base64（纯 ASCII，任何编码通道都无损）
$keyPath = "$env:USERPROFILE\.ssh\id_ed25519.pub"
if (!(Test-Path $keyPath)) {
  Write-Host '[错误] 本机公钥不存在，请先运行「首次免密配置.bat」' -ForegroundColor Red
  Read-Host '回车退出'; exit 1
}
$key = (Get-Content $keyPath -Raw).Trim()
$b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($key + "`n"))

# 远程重写 authorized_keys（PS 单引号拼接，零插值污染；base64 解码写回）
$cmd = 'mkdir -p ~/.ssh && chmod 700 ~/.ssh && echo ' + $b64 +
       ' | base64 -d > ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys && chmod 700 /root /root/.ssh' +
       ' && (restorecon -R /root/.ssh 2>/dev/null; true)' +
       ' && echo "===== file check =====" && wc -c ~/.ssh/authorized_keys && cat -A ~/.ssh/authorized_keys && echo REWRITE_DONE'

Write-Host "`nConnecting $sshTarget ... (input server password)" -ForegroundColor Cyan
ssh -p $sshPort -o StrictHostKeyChecking=accept-new $sshTarget $cmd

Write-Host "`n===== Verify passwordless login =====" -ForegroundColor Cyan
ssh -p $sshPort -o BatchMode=yes -o ConnectTimeout=10 $sshTarget "echo FREE_PASSWORD_OK && hostname"
if ($LASTEXITCODE -eq 0) {
  Write-Host "`nPasswordless login FIXED! Go back to the AI assistant to continue server troubleshooting." -ForegroundColor Green
} else {
  Write-Host "`nStill failing - copy ALL output above to the AI assistant." -ForegroundColor Yellow
}
Read-Host 'Press Enter to exit'
