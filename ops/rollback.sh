#!/usr/bin/env bash
# 物业费插件·一键回滚（把生产恢复到指定版本 jar）
# 用法: ops/rollback.sh 1.1.0        # 从生产备份目录 _backup/ 取该版本
#       ops/rollback.sh /path/x.jar  # 直接指定本地 jar
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

HOST="${PF_HOST:-root@162.14.114.224}"
PLUGIN_DIR="/opt/1panel/apps/halo/halo/data/plugins"
BACKUP_DIR="$PLUGIN_DIR/_backup"
PROFILE="${PF_PROFILE:-wenbita}"
SITE="${PF_SITE:-https://wenbita.cn}"
ARG="${1:-}"

[ -n "$ARG" ] || { echo "用法: ops/rollback.sh <版本号如1.1.0 | 本地jar路径>"; 
  echo "生产备份目录现有："; ssh -o ConnectTimeout=10 "$HOST" "ls -1 $BACKUP_DIR/ 2>/dev/null" ; exit 1; }

TMP="/tmp/pf-rollback-$(date +%s).jar"
if [ -f "$ARG" ]; then
  cp "$ARG" "$TMP"; echo "使用本地 jar: $ARG"
else
  echo "从生产备份取版本 $ARG …"
  ssh -o ConnectTimeout=15 "$HOST" "ls $BACKUP_DIR/property-fee-$ARG.jar" >/dev/null 2>&1 \
    || { echo "⛔ 备份里没有 $ARG，现有："; ssh -o ConnectTimeout=10 "$HOST" "ls -1 $BACKUP_DIR/"; exit 1; }
  scp -q -o ConnectTimeout=15 "$HOST:$BACKUP_DIR/property-fee-$ARG.jar" "$TMP" || { echo "⛔ 拉取失败"; exit 1; }
fi

echo "--- 回滚（Halo 允许安装更低版本 jar）---"
halo plugin upgrade property-fee --profile "$PROFILE" --file "$TMP" -y || { echo "⛔ 回滚命令失败"; rm -f "$TMP"; exit 1; }

echo "--- 等待 STARTED（最多 180s）---"
for i in $(seq 1 36); do
  ST=$(halo plugin list --profile "$PROFILE" 2>/dev/null | awk '/^property-fee/{print $NF}')
  printf '\r  第 %02ds 状态: %-10s' $((i * 5)) "${ST:-?}"
  [ "$ST" = "STARTED" ] && { echo; break; }
  [ "$ST" = "FAILED" ] && { echo; echo "⛔ 回滚后仍启动失败，请人工介入（看 data/logs/halo.log）"; rm -f "$TMP"; exit 1; }
  sleep 5
done
[ "$ST" = "STARTED" ] || { echo; echo "⛔ 超时未启动"; rm -f "$TMP"; exit 1; }

echo "--- 冒烟验证 ---"
for u in "$SITE/apis/api.propertyfee.halo.run/v1alpha1/pages/property-fee" "$SITE/apis/api.propertyfee.halo.run/v1alpha1/properties/options"; do
  C=$(curl -s -o /dev/null -w '%{http_code}' -m 25 "$u")
  [ "$C" = "200" ] && printf '  %-60s ✓ %s\n' "$(basename "$u")" "$C" || printf '  %-60s ✗ %s\n' "$(basename "$u")" "$C"
done
VER=$(halo plugin list --profile "$PROFILE" 2>/dev/null | awk '/^property-fee/{print $3}')
echo
echo "✅ 回滚完成，当前版本：${VER}"
rm -f "$TMP"
