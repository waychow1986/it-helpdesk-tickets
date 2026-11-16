#!/usr/bin/env bash
# 开发者功能验证（design「快速验证指南」、T021）：
# 生成运行前缀与随机密码（只存在于进程环境，不输出）→ 构建 → 后台启动应用（localhost:8080，连接注入的 ticket-db dev 实例）
# → 轮询 /actuator/health 至 UP（超时 120 秒）→ Playwright（Chromium）执行 IQA-001～IQA-014 开发者功能流程
# → 无论成败都按前缀清理合成数据 → 停止应用 → 以 Playwright 退出码退出。任何场景失败都以非零状态退出。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
cd "$ROOT"

for var in AIDH_DEP_TICKET_DB_HOST AIDH_DEP_TICKET_DB_DATABASE AIDH_DEP_TICKET_DB_USER; do
  if [ -z "${!var:-}" ]; then
    echo "缺少依赖配置 ${var}（应由平台按 docs/dependencies.json 注入）" >&2
    exit 2
  fi
done

PORT=8080
if (exec 3<>"/dev/tcp/127.0.0.1/${PORT}") 2>/dev/null; then
  echo "端口 ${PORT} 已被占用，无法启动待测应用" >&2
  exit 2
fi

E2E_RUN_PREFIX="e2e-$(date +%s)-$(od -An -N3 -tx1 /dev/urandom | tr -d ' \n')-"
E2E_ADMIN_PASSWORD="Pw-$(od -An -N12 -tx1 /dev/urandom | tr -d ' \n')"
export E2E_RUN_PREFIX E2E_ADMIN_PASSWORD
export E2E_BASE_URL="http://localhost:${PORT}"
echo "运行前缀：${E2E_RUN_PREFIX}"

WORK_DIR="$(mktemp -d)"
APP_LOG="${WORK_DIR}/app.log"
APP_PID=""

stop_app() {
  if [ -n "${APP_PID}" ] && kill -0 "${APP_PID}" 2>/dev/null; then
    kill "${APP_PID}" 2>/dev/null || true
    for _ in $(seq 1 30); do
      kill -0 "${APP_PID}" 2>/dev/null || break
      sleep 1
    done
    kill -9 "${APP_PID}" 2>/dev/null || true
  fi
  rm -rf "${WORK_DIR}"
}
trap stop_app EXIT

echo "== 构建应用"
mvn -B -ntp -q -DskipTests package

if [ ! -d e2e/node_modules ]; then
  echo "== 安装功能测试依赖"
  (cd e2e && npm ci --no-audit --no-fund)
fi

echo "== 启动应用（localhost:${PORT}）"
java -jar target/helpdesk.jar --server.port="${PORT}" > "${APP_LOG}" 2>&1 < /dev/null &
APP_PID=$!

STATUS=""
for _ in $(seq 1 120); do
  if ! kill -0 "${APP_PID}" 2>/dev/null; then
    echo "应用启动失败，日志末尾：" >&2
    tail -n 40 "${APP_LOG}" >&2
    exit 1
  fi
  STATUS="$(curl -s -m 2 "${E2E_BASE_URL}/actuator/health" || true)"
  case "${STATUS}" in
    *'"status":"UP"'*) break ;;
  esac
  sleep 1
done
case "${STATUS}" in
  *'"status":"UP"'*) echo "应用就绪：${STATUS}" ;;
  *) echo "120 秒内健康检查未达到 UP：${STATUS}" >&2; tail -n 40 "${APP_LOG}" >&2; exit 1 ;;
esac

set +e
(cd e2e && npx playwright test --project=chromium)
PW_EXIT=$?
echo "== 按前缀清理合成数据（保底）"
(cd e2e && npx playwright test --project=cleanup --reporter=line)
CLEAN_EXIT=$?
set -e

if [ "${PW_EXIT}" -ne 0 ]; then
  echo "功能测试失败（Playwright 退出码 ${PW_EXIT}）" >&2
  exit "${PW_EXIT}"
fi
if [ "${CLEAN_EXIT}" -ne 0 ]; then
  echo "测试数据清理失败（退出码 ${CLEAN_EXIT}）" >&2
  exit "${CLEAN_EXIT}"
fi
echo "功能测试全部通过"
exit 0
