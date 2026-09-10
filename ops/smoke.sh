#!/usr/bin/env bash
# 物业费插件·冒烟验证（升级/回滚后跑，也可单独跑）
# 用法: ops/smoke.sh
set -uo pipefail

SITE="${PF_SITE:-https://wenbita.cn}"
PROXY_SITE="${PF_PROXY_SITE:-https://aiedu.yn.cn}"
PAT_FILE="${PF_PAT_FILE:-$HOME/.hermes/skills/halo-cli-wenbita/scripts/halo_pat_token.txt}"
CONSOLE="$SITE/apis/console.api.propertyfee.halo.run/v1alpha1"
API="$SITE/apis/api.propertyfee.halo.run/v1alpha1"

CURL_AUTH=()
[ -f "$PAT_FILE" ] && CURL_AUTH=(-H "Authorization: Bearer $(tr -d '\n' < "$PAT_FILE")")

PASS=0; TOTAL=0
smoke() { # 名称 URL 期望码 [需要鉴权]
  TOTAL=$((TOTAL + 1))
  local auth=()
  [ "${4:-}" = "auth" ] && auth=("${CURL_AUTH[@]}")
  local C
  C=$(curl -s -o /dev/null -w '%{http_code}' -m 25 "${auth[@]}" "$2")
  if [ "$C" = "$3" ]; then printf '  %-40s ✓ %s\n' "$1" "$C"; PASS=$((PASS + 1));
  else printf '  %-40s ✗ %s（期望 %s）\n' "$1" "$C" "$3"; fi
}

echo "=== 物业费插件 · 冒烟验证 ==="
smoke "前台缴费页"        "$API/pages/property-fee" 200
smoke "aiedu 代理 /wuye"  "$PROXY_SITE/wuye" 200
smoke "小区选项接口"      "$API/properties/options" 200
smoke "后台-房屋"         "$CONSOLE/properties?page=0&size=1" 200 auth
smoke "后台-小区"         "$CONSOLE/communities" 200 auth
smoke "后台-收费标准"     "$CONSOLE/feestandards?page=0&size=1" 200 auth
smoke "后台-缴费记录"     "$CONSOLE/feerecords?page=0&size=1" 200 auth
smoke "后台-收款渠道"     "$CONSOLE/paymentconfigs?page=0&size=1" 200 auth
smoke "后台-系统配置"     "$CONSOLE/systemconfig" 200 auth
smoke "后台-报表"         "$CONSOLE/reports/summary?year=2026" 200 auth

# 鉴权必须拦得住（无令牌 → 400/401）
TOTAL=$((TOTAL + 1))
C=$(curl -s -o /dev/null -w '%{http_code}' -m 25 -X POST -H 'Content-Type: application/json' -d '{}' "$API/owner/houses")
if [ "$C" = "400" ] || [ "$C" = "401" ]; then printf '  %-40s ✓ %s（无令牌被拒）\n' "业主接口鉴权" "$C"; PASS=$((PASS + 1));
else printf '  %-40s ✗ %s（应被拒）\n' "业主接口鉴权" "$C"; fi

# 免验证码绑定：非业主手机号必须拒绝（用虚拟号，避免给真实业主累计失败次数）
TOTAL=$((TOTAL + 1))
C=$(curl -s -o /dev/null -w '%{http_code}' -m 25 -X POST -H 'Content-Type: application/json' \
    -d '{"openid":"smoke-test","phone":"13700000000","name":"冒烟测试"}' "$API/wx/bind")
if [ "$C" = "400" ]; then printf '  %-40s ✓ %s（非业主被拒）\n' "业主绑定校验" "$C"; PASS=$((PASS + 1));
else printf '  %-40s ✗ %s（应被拒）\n' "业主绑定校验" "$C"; fi

echo
if [ "$PASS" = "$TOTAL" ]; then echo "✅ 冒烟 $PASS/$TOTAL 全通过"; else echo "⚠️  冒烟 $PASS/$TOTAL，请检查 ✗ 项"; exit 1; fi
