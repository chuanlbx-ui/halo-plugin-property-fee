#!/usr/bin/env bash
# 物业费插件·升级前自检（本地执行，不改动任何东西）
# 用法: ops/preflight.sh [jar 路径]   —— 不传则用 build/libs 下最新 jar
set -uo pipefail

HOST="${PF_HOST:-root@162.14.114.224}"
PLUGIN_DIR="/opt/1panel/apps/halo/halo/data/plugins"
PROFILE="${PF_PROFILE:-wenbita}"
PAT_FILE="${PF_PAT_FILE:-$HOME/.hermes/skills/halo-cli-wenbita/scripts/halo_pat_token.txt}"
SITE="${PF_SITE:-https://wenbita.cn}"
PROXY_SITE="${PF_PROXY_SITE:-https://aiedu.yn.cn}"

JAR="${1:-$(ls -t build/libs/property-fee-*.jar 2>/dev/null | head -1)}"
FAIL=0

chk() { printf '%-46s' "$1"; }
ok()  { echo "✓ ${1:-}"; }
bad() { echo "✗ ${1:-}"; FAIL=1; }
warn(){ echo "! ${1:-}"; }

echo "=== 物业费插件 · 升级前自检 ==="
echo "目标 jar: ${JAR:-（未找到）}"
echo

chk "1. jar 文件存在"
[ -n "${JAR:-}" ] && [ -f "$JAR" ] && ok "$(basename "$JAR") $(stat -c%s "$JAR")B" || bad "未找到 jar，请先 ./gradlew build -x test"

chk "2. jar 内插件清单版本"
V_JAR=$(unzip -p "$JAR" plugin.yaml 2>/dev/null | grep -m1 '^  version:' | tr -d ' "' | cut -d: -f2)
V_LOCAL=$(grep -m1 '^version=' gradle.properties 2>/dev/null | cut -d= -f2)
[ -n "$V_JAR" ] && [ "$V_JAR" = "$V_LOCAL" ] && ok "$V_JAR（与 gradle.properties 一致）" || bad "jar=$V_JAR 本地=$V_LOCAL 不一致"

chk "3. 控制台产物完整（console/main.js）"
unzip -l "$JAR" 2>/dev/null | grep -q "console/main.js" && ok "$(unzip -l "$JAR" | awk '/console\/main.js/{print $1"B"}')" || bad "缺 console/main.js（UI 未构建）"

chk "4. 控制台样式已内联（Halo 不加载 style.css）"
N_CSS=$(unzip -p "$JAR" console/main.js 2>/dev/null | grep -c "data-property-fee-plugin\|pf-card\|pf-view")
[ "${N_CSS:-0}" -gt 0 ] && ok "命中 ${N_CSS} 处" || bad "main.js 内无内联样式串（页面会裸奔）"

chk "5. 前台页面已打包"
unzip -l "$JAR" 2>/dev/null | grep -q "frontend/property-fee.html" && ok "✓" || bad "缺 frontend/property-fee.html"

chk "5b. jar 是否比源码新（防部署旧包）"
NEWEST_SRC=$(find src -type f \( -name "*.java" -o -name "*.vue" -o -name "*.ts" -o -name "*.yaml" -o -name "*.html" -o -name "*.css" \) -newer "$JAR" 2>/dev/null | head -3)
if [ -z "$NEWEST_SRC" ]; then ok "jar 比源码新"
else bad "jar 落后于源码，先 ./gradlew build -x test（改动文件：$(echo "$NEWEST_SRC" | tr '\n' ' '))"; fi

chk "6. 生产插件状态"
ST=$(halo plugin list --profile "$PROFILE" 2>/dev/null | awk '/^property-fee/{print $NF}')
[ "$ST" = "STARTED" ] && ok "STARTED" || { [ -z "$ST" ] && bad "生产未安装该插件" || warn "当前状态 $ST（升级会重启，属正常）"; }

H_LOCAL=$(sha256sum "$JAR" 2>/dev/null | cut -c1-16)
H_PROD=$(ssh -o ConnectTimeout=10 "$HOST" "sha256sum $PLUGIN_DIR/property-fee-*.jar 2>/dev/null | head -1 | cut -c1-16")
if [ -n "${H_LOCAL:-}" ] && [ "$H_LOCAL" = "${H_PROD:-}" ]; then
  chk "7. 生产已部署 jar 与本地是否相同"; warn "内容相同（$H_LOCAL）：升级不会改变行为"
else
  chk "7. 生产已部署 jar 与本地是否相同"; ok "本地 $H_LOCAL / 生产 ${H_PROD:-未安装}：有差异"
fi

chk "8. 前台缴费页可用"
C=$(curl -s -o /dev/null -w '%{http_code}' -m 20 "$SITE/apis/api.propertyfee.halo.run/v1alpha1/pages/property-fee")
[ "$C" = "200" ] && ok "HTTP 200" || bad "HTTP $C"

chk "9. aiedu 代理页可用（/wuye）"
C=$(curl -s -o /dev/null -w '%{http_code}' -m 20 "$PROXY_SITE/wuye")
[ "$C" = "200" ] && ok "HTTP 200" || bad "HTTP $C"

chk "10. 后台控制台接口"
if [ -f "$PAT_FILE" ]; then
  C=$(curl -s -o /dev/null -w '%{http_code}' -m 20 -H "Authorization: Bearer $(tr -d '\n' < "$PAT_FILE")" \
      "$SITE/apis/console.api.propertyfee.halo.run/v1alpha1/properties?page=0&size=1")
  [ "$C" = "200" ] && ok "HTTP 200" || bad "HTTP $C"
else
  warn "无 PAT 文件，跳过"
fi

echo
[ "$FAIL" = "0" ] && echo "✅ 自检通过，可以升级（ops/deploy.sh）" || echo "❌ 自检未通过，先修上面标 ✗ 的项"
exit "$FAIL"
