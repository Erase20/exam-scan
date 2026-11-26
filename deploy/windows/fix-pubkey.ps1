# ============================================================
# 修复服务器端公钥登录被拒问题（公钥已写入但验证不通过时使用）
# 自动检查并修复三大常见原因：
#   1. /root、/root/.ssh、authorized_keys 权限过宽（sshd StrictModes 拒绝）
#   2. SELinux 安全上下文错误（CentOS 常见）
#   3. sshd_config 禁用了公钥登录（PubkeyAuthentication no）
# 运行需输入一次服务器密码；结束后自动验证免密是否已通。
# ============================================================
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$envFile = Join-Path $root 'deploy\windows\aliyun.env'
$cfg = @{}
Get-Content $envFile | ForEach-Object {
  if ($_ -match '^\s*([A-Z_]+)=(.*)$') { $cfg[$Matches[1]] = $Matches[2].Trim() }
}
$sshTarget = "$($cfg['SSH_USER'])@$($cfg['SSH_HOST'])"
$sshPort = $cfg['SSH_PORT']

$fix = @'
echo "===== sshd 相关配置 ====="
grep -Ei "^#?(PubkeyAuthentication|PermitRootLogin|AuthorizedKeysFile|StrictModes)" /etc/ssh/sshd_config
echo "===== 权限检查 ====="
ls -ld /root /root/.ssh 2>&1
ls -l /root/.ssh/authorized_keys 2>&1
echo "===== 公钥文件尾部（看是否多行/损坏） ====="
wc -l /root/.ssh/authorized_keys 2>&1
tail -c 120 /root/.ssh/authorized_keys; echo ""
echo "===== sshd 日志尾部（拒绝原因） ====="
(grep -i "publickey\|authentication" /var/log/secure 2>/dev/null || journalctl -u sshd --no-pager -n 10 2>/dev/null) | tail -8
echo "===== 开始修复 ====="
chmod 700 /root 2>/dev/null; chmod 700 /root/.ssh 2>/dev/null
chmod 600 /root/.ssh/authorized_keys 2>/dev/null
restorecon -R /root/.ssh 2>/dev/null && echo "SELinux 上下文已修复" || echo "(无 SELinux 或已跳过)"
if grep -qiE "^PubkeyAuthentication\s+no" /etc/ssh/sshd_config; then
  sed -i "s/^PubkeyAuthentication\s\+no/PubkeyAuthentication yes/" /etc/ssh/sshd_config
  systemctl restart sshd 2>/dev/null || service sshd restart 2>/dev/null || service ssh restart 2>/dev/null
  echo "已启用公钥登录并重启 sshd"
else
  echo "(PubkeyAuthentication 未禁用，无需改配置)"
fi
echo "FIX_DONE"
'@

Write-Host "`n连接 $sshTarget 诊断并修复（输入服务器密码）..." -ForegroundColor Cyan
$fix | ssh -p $sshPort -o StrictHostKeyChecking=accept-new $sshTarget "sh -s"

Write-Host "`n===== 验证免密连接 =====" -ForegroundColor Cyan
ssh -p $sshPort -o BatchMode=yes -o ConnectTimeout=10 $sshTarget "echo FREE_PASSWORD_OK && hostname"
if ($LASTEXITCODE -eq 0) {
  Write-Host "`n免密登录已修复成功！回到 AI 助手对话让它继续排查服务器。" -ForegroundColor Green
} else {
  Write-Host "`n仍未通过——请把本窗口上方【全部输出】复制发给 AI 助手（诊断信息里能看到服务器拒绝的具体原因）。" -ForegroundColor Yellow
}
Read-Host '回车退出'
