#!/usr/bin/env bash
# 一键：在 GitHub 建仓库（若不存在）→ 推送 main 与当前 tag → 校验远端。
# 需要的细粒度 PAT 权限：Account: Administration(RW) + Repository: Contents(RW) + All repositories
# 用法：GH_TOKEN=github_pat_xxx ./scripts/bootstrap-github.sh [public|private]
# token 只用于本次调用，不写入 .git/config
set -euo pipefail

: "${GH_TOKEN:?需要 GH_TOKEN 环境变量}"
OWNER="Yuxiximyxx"
REPO="MagicXM4"
VIS="${1:-private}"
API="https://api.github.com"
AUTH=(-H "Authorization: Bearer ${GH_TOKEN}" -H "Accept: application/vnd.github+json" -H "X-GitHub-Api-Version: 2022-11-28")
CURL=(curl -sS --http1.1 --retry 8 --retry-delay 2 --retry-all-errors --connect-timeout 20)

echo "→ 检查仓库是否存在"
code=$("${CURL[@]}" -o /dev/null -w "%{http_code}" "${AUTH[@]}" "$API/repos/$OWNER/$REPO")
if [ "$code" = "200" ]; then
  echo "  已存在，跳创建"
elif [ "$code" = "404" ]; then
  echo "  不存在，创建中（${VIS}）"
  body=$(printf '{"name":"%s","description":"Sony WH-1000XM4 on Honor MagicOS 11: device-center ANC switch + battery (LSPosed; Sony SPP protocol port of MagicFree4 + SonyHeadphonesClient)","private":%s,"has_issues":true,"has_wiki":false,"auto_init":false}' "$REPO" "$([ "$VIS" = public ] && echo false || echo true)")
  out=$("${CURL[@]}" -X POST "${AUTH[@]}" -d "$body" "$API/user/repos" -w "\n%{http_code}")
  echo "$out" | tail -1 | grep -qx 201 || { echo "创建失败："; echo "$out" | head -20; exit 1; }
  echo "  创建成功"
else
  echo "  未知状态码 ${code}（可能是 token 权限或网络问题）"; exit 1
fi

PUSH_URL="https://x-access-token:${GH_TOKEN}@github.com/${OWNER}/${REPO}.git"
push_retry() {   # 代理节点会抽风，重试几次
  local ref="$1" i
  for i in 1 2 3 4 5; do
    if git push "$PUSH_URL" "$ref"; then return 0; fi
    echo "  第 $i 次失败，3 秒后重试"; sleep 3
  done
  return 1
}
echo "→ 推送 main"
push_retry main:main
echo "→ 推送 tag v2.0.0"
push_retry refs/tags/v2.0.0

echo "→ 远端校验"
"${CURL[@]}" "${AUTH[@]}" "$API/repos/$OWNER/$REPO" | python3 -c "import sys,json;d=json.load(sys.stdin);print('  html_url:',d.get('html_url'));print('  private:',d.get('private'));print('  default_branch:',d.get('default_branch'))"
"${CURL[@]}" "${AUTH[@]}" "$API/repos/$OWNER/$REPO/commits?per_page=1" | python3 -c "import sys,json;d=json.load(sys.stdin);print('  remote HEAD:',d[0]['sha'][:12],'-',d[0]['commit']['message'].splitlines()[0])"
echo "  本地 HEAD: $(git rev-parse --short=12 HEAD)"

# 远端地址保持干净（不含 token）
git remote set-url origin "https://github.com/${OWNER}/${REPO}.git"
echo "完成：https://github.com/${OWNER}/${REPO}"
