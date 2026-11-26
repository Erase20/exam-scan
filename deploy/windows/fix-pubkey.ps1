# ============================================================
# v2: 修复服务器端公钥登录被拒
# 实锤根因（v1 诊断发现）：authorized_keys 公钥行开头被 BOM(U+FEFF) 污染 + 可能含 \r，
# sshd 无法解析该行导致 Permission denied。
# v2 改进：修复脚本纯 ASCII + LF 行尾，先清洗 authorized_keys 再验证。
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

# 纯 ASCII + LF 行尾（写临时文件再经管道上传，避免编码/换行污染远程脚本）
$fix = @'
echo "===== sshd config ====="
grep -Ei "^#?(PubkeyAuthentication|PermitRootLogin|AuthorizedKeysFile|StrictModes)" /etc/ssh/sshd_config
echo "===== perms ====="
ls -ld /root /root/.ssh /root/.ssh/authorized_keys
echo "===== authorized_keys before ====="
cat -A /root/.ssh/authorized_keys | head -c 300; echo ""
echo "===== cleaning ====="
grep -h "^ssh-" /root/.ssh/authorized_keys | sed "s/\r$//" | sed "1s/^\xef\xbb\xbf//" | sort -u > /root/.ssh/ak.clean
mv /root/.ssh/ak.clean /root/.ssh/authorized_keys
chmod 700 /root /root/.ssh
chmod 600 /root/.ssh/authorized_keys
restorecon -R /root/.ssh 2>/dev/null && echo "selinux context fixed" || echo "(no selinux, skipped)"
echo "===== authorized_keys after ====="
cat -A /root/.ssh/authorized_keys
if grep -qiE "^PubkeyAuthentication[[:space:]]+no" /etc/ssh/sshd_config; then
  sed -i "s/^PubkeyAuthentication[[:space:]]\+no/PubkeyAuthentication yes/" /etc/ssh/sshd_config
  systemctl restart sshd 2>/dev/null || service sshd restart 2>/dev/null || service ssh restart 2>/dev/null
  echo "pubkey auth enabled, sshd restarted"
else
  echo "(pubkey auth not disabled)"
fi
echo "FIX_DONE"
'@
$tmp = Join-Path $env:TEMP 'exam-scan-fix.sh'
[IO.File]::WriteAllText($tmp, ($fix -replace "`r`n", "`n") + "`n", (New-Object System.Text.UTF8Encoding $false))

Write-Host "`nConnecting $sshTarget ... (input server password)" -ForegroundColor Cyan
Get-Content $tmp -Raw | ssh -p $sshPort -o StrictHostKeyChecking=accept-new $sshTarget "cat > /tmp/exam-scan-fix.sh && sh /tmp/exam-scan-fix.sh; rm -f /tmp/exam-scan-fix.sh"
Remove-Item $tmp -Force -ErrorAction SilentlyContinue

Write-Host "`n===== Verify passwordless login =====" -ForegroundColor Cyan
ssh -p $sshPort -o BatchMode=yes -o ConnectTimeout=10 $sshTarget "echo FREE_PASSWORD_OK && hostname"
if ($LASTEXITCODE -eq 0) {
  Write-Host "`nPasswordless login FIXED! Go back to the AI assistant to continue server troubleshooting." -ForegroundColor Green
} else {
  Write-Host "`nStill failing - copy ALL output above to the AI assistant." -ForegroundColor Yellow
}
Read-Host 'Press Enter to exit'
