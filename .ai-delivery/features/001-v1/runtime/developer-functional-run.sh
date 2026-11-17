#!/usr/bin/env bash
# 开发者功能验证（design「快速验证指南」、T021）：
# 生成运行前缀、种子管理员随机密码与 32 字符合成设置口令（只存在于进程环境，不输出、不写入文件）→ 构建
# → 后台启动两个实例（均连接注入的 ticket-db dev 实例）：
#     8080：默认 public schema，执行 IQA-001～IQA-014 开发者功能流程（chromium project）
#     8081：一次性 schema e2e_setup_<运行前缀>（TD-016）+ 合成设置口令（TD-019），执行 IQA-016（initial-admin project）
# → 分别轮询 /actuator/health 至 UP（各超时 120 秒）→ Playwright（Chromium）
# → 无论成败都按前缀清理合成数据并删除一次性 schema → 停止两个实例 → 以 Playwright 退出码退出。
# 任何场景失败都以非零状态退出；兼容 bash -euo pipefail 与 stdin 为 /dev/null。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
cd "$ROOT"

for var in AIDH_DEP_TICKET_DB_HOST AIDH_DEP_TICKET_DB_PORT AIDH_DEP_TICKET_DB_DATABASE AIDH_DEP_TICKET_DB_USER; do
  if [ -z "${!var:-}" ]; then
    echo "缺少依赖配置 ${var}（应由平台按 docs/dependencies.json 注入）" >&2
    exit 2
  fi
done

PORT=8080
SETUP_PORT=8081
for p in "${PORT}" "${SETUP_PORT}"; do
  if (exec 3<>"/dev/tcp/127.0.0.1/${p}") 2>/dev/null; then
    echo "端口 ${p} 已被占用，无法启动待测应用" >&2
    exit 2
  fi
done

rand_hex() { od -An -N"$1" -tx1 /dev/urandom | tr -d ' \n'; }

E2E_RUN_PREFIX="e2e-$(date +%s)-$(rand_hex 3)-"
E2E_ADMIN_PASSWORD="Pw-$(rand_hex 12)"
E2E_SETUP_TOKEN="$(rand_hex 16)"
SCHEMA_SUFFIX="$(printf '%s' "${E2E_RUN_PREFIX}" | tr 'A-Z' 'a-z' | sed -e 's/[^a-z0-9]\{1,\}/_/g' -e 's/^_*//' -e 's/_*$//')"
E2E_SETUP_SCHEMA="e2e_setup_${SCHEMA_SUFFIX}"
export E2E_RUN_PREFIX E2E_ADMIN_PASSWORD E2E_SETUP_TOKEN E2E_SETUP_SCHEMA
export E2E_BASE_URL="http://localhost:${PORT}"
export E2E_SETUP_BASE_URL="http://localhost:${SETUP_PORT}"
echo "运行前缀：${E2E_RUN_PREFIX}"
echo "一次性 schema：${E2E_SETUP_SCHEMA}"

WORK_DIR="$(mktemp -d)"
APP_LOG="${WORK_DIR}/app.log"
SETUP_LOG="${WORK_DIR}/app-setup.log"
APP_PID=""
SETUP_PID=""

stop_pid() {
  local pid="$1"
  if [ -n "${pid}" ] && kill -0 "${pid}" 2>/dev/null; then
    kill "${pid}" 2>/dev/null || true
    for _ in $(seq 1 30); do
      kill -0 "${pid}" 2>/dev/null || break
      sleep 1
    done
    kill -9 "${pid}" 2>/dev/null || true
  fi
}

CLEANED=0
cleanup_data() {
  if [ "${CLEANED}" -eq 1 ] || [ ! -d e2e/node_modules ]; then
    return 0
  fi
  CLEANED=1
  echo "== 按前缀清理合成数据并删除一次性 schema（保底）"
  local rc=0
  (cd e2e && npx playwright test --project=cleanup --project=initial-admin-cleanup --reporter=line) < /dev/null || rc=$?
  return "${rc}"
}

on_exit() {
  cleanup_data || true
  stop_pid "${APP_PID}"
  stop_pid "${SETUP_PID}"
  rm -rf "${WORK_DIR}"
}
trap on_exit EXIT

wait_up() {
  local name="$1" pid="$2" url="$3" log="$4" status=""
  for _ in $(seq 1 120); do
    if ! kill -0 "${pid}" 2>/dev/null; then
      echo "${name} 启动失败，日志末尾：" >&2
      tail -n 40 "${log}" >&2
      return 1
    fi
    status="$(curl -s -m 2 "${url}/actuator/health" || true)"
    case "${status}" in
      *'"status":"UP"'*) echo "${name} 就绪：${status}"; return 0 ;;
    esac
    sleep 1
  done
  echo "${name} 120 秒内健康检查未达到 UP：${status}" >&2
  tail -n 40 "${log}" >&2
  return 1
}

echo "== 构建应用"
mvn -B -ntp -q -DskipTests package

if [ ! -d e2e/node_modules ]; then
  echo "== 安装功能测试依赖"
  (cd e2e && npm ci --no-audit --no-fund) < /dev/null
fi

echo "== 启动应用实例（localhost:${PORT}，public schema）"
java -jar target/helpdesk.jar --server.port="${PORT}" > "${APP_LOG}" 2>&1 < /dev/null &
APP_PID=$!

echo "== 启动设置流程实例（localhost:${SETUP_PORT}，一次性 schema）"
java -jar target/helpdesk.jar --server.port="${SETUP_PORT}" --app.db.schema="${E2E_SETUP_SCHEMA}" \
  --app.setup.token="${E2E_SETUP_TOKEN}" > "${SETUP_LOG}" 2>&1 < /dev/null &
SETUP_PID=$!

wait_up "应用实例" "${APP_PID}" "${E2E_BASE_URL}" "${APP_LOG}"
wait_up "设置流程实例" "${SETUP_PID}" "${E2E_SETUP_BASE_URL}" "${SETUP_LOG}"

set +e
(cd e2e && npx playwright test --project=chromium --project=initial-admin) < /dev/null
PW_EXIT=$?
cleanup_data
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
