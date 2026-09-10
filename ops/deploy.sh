#!/usr/bin/env bash
# 物业费插件·一键升级（自检 → 备份 → 升级 → 等 STARTED → 冒烟验证 → 报告）
# 用法: ops/deploy.sh [jar 路径]
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

HOST="${PF_HOST:-root@162.14.114.224}"
PLUGIN_DIR="/opt/1panel/apps/halo/halo/data/plugins"
BACKUP_DIR="$PLUGIN_DIR/_backup"
PROFILE="${PF_PROFILE:-wenbita}"
SITE="${PF_SITE:-https://wenbita.cn}"
PROXY_SITE="${PF_PROXY_SITE:-https://aiedu.yn.cn}"
PAT_FILE="${PF_PAT_FILE:-$HOME/.hermes/skills/halo-cli-wenbita/scripts/halo_pat_token.txt}"

JAR="${1:-$(ls -t build/libs/property-fee-*.jar 2>/dev/null | head -1)}"
VERSION=$(basename "${JAR:-none}" | sed -E 's/property-fee-(.*)\.jar/\1/')

echo "=== 升级 ${VERSION} ==="
bash ops/preflight.sh "$JAR" || { echo "⛔ 自检未通过，已中止（未做任何改动）"; exit 1; }

echo
echo "--- 备份生产现有 jar ---"
ssh -o ConnectTimeout=15 "$HOST" "mkdir -p $BACKUP_DIR && cp -n $PLUGIN_DIR/property-fee-*.jar $BACKUP_DIR/ 2>/dev/null; ls -1 $BACKUP_DIR/ | tail -5" \
  || { echo "⛔ 备份失败，中止"; exit 1; }

echo
echo "--- 上传并升级 ---"
halo plugin upgrade property-fee --profile "$PROFILE" --file "$JAR" -y || { echo "⛔ 升级命令失败"; exit 1; }

echo
echo "--- 等待插件 STARTED（最多 180s）---"
for i in $(seq 1 36); do
  ST=$(halo plugin list --profile "$PROFILE" 2>/dev/null | awk '/^property-fee/{print $NF}')
  printf '\r  第 %02ds 状态: %-10s' $((i * 5)) "${ST:-?}"
  [ "$ST" = "STARTED" ] && { echo; break; }
  [ "$ST" = "FAILED" ] && { echo; echo "⛔ 插件启动失败，立即回滚：ops/rollback.sh"; exit 1; }
  sleep 5
done
[ "$ST" = "STARTED" ] || { echo; echo "⛔ 超时未启动，考虑回滚：ops/rollback.sh"; exit 1; }

echo
echo "--- 冒烟验证 ---"
bash ops/smoke.sh
SMOKE_RC=$?

echo
echo "--- 生产日志（最近插件相关）---"
ssh -o ConnectTimeout=15 "$HOST" "grep -h 'property-fee' $PLUGIN_DIR/../logs/halo.log 2>/dev/null | tail -3 | cut -c1-140"

echo
if [ "${SMOKE_RC:-1}" = "0" ]; then
  echo "✅ 升级完成：${VERSION}（冒烟全通过）"
  echo "   回滚命令： ops/rollback.sh <版本，如 1.1.0>"
else
  echo "⚠️  升级完成：${VERSION}，但冒烟未全通过，请查上面 ✗ 项；必要时 ops/rollback.sh"
  exit 1
fi
