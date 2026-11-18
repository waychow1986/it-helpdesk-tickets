#!/usr/bin/env bash
# V2 开发者功能验证（design「快速验证指南」步骤 1～5、「验证环境责任矩阵」功能测试与升级演练行；T002、T053）：
# 1. 校验 AIDH_DEP_TICKET_DB_*；检查 8080/8081/8082 未占用；生成运行前缀、种子管理员随机密码、IQA-016 管理员随机密码、
#    升级演练账号随机密码与 32 字符合成设置口令（只存在于进程环境，不输出、不写入文件）；派生一次性 schema
#    e2e_setup_<suffix> 与 e2e_upgrade_<suffix>。
# 2. 构建 jar；按需 npm ci。
# 3. 启动 8080（public schema）与 8081（e2e_setup_<suffix> + 合成口令），health UP 后运行 chromium 与 initial-admin
#    projects（V1 回归 + V2 IQA-001～008）。
# 4. 升级演练（TD-030）：8082 以 --spring.flyway.target=1 启动至 UP 后停止（V1 结构）→ upgrade-seed（V1 数据集与快照）
#    → 以默认配置启动同一 schema 至 UP，日志须含迁移到版本 2 → upgrade-verify（IQA-009 + IQA-010 准备）
#    → 停止并以相同参数重启至 UP，日志须含 up to date（重复执行升级）→ upgrade-repeat（IQA-010 比对）。
# 5. 无论成败：运行 cleanup、initial-admin-cleanup、upgrade-cleanup → 停止全部实例 → 以首个失败的退出码退出。
# spring.flyway.target 只以命令行参数传入，不写入 application.yml。兼容 bash -euo pipefail 与 stdin 为 /dev/null。
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
UPGRADE_PORT=8082
for p in "${PORT}" "${SETUP_PORT}" "${UPGRADE_PORT}"; do
  if (exec 3<>"/dev/tcp/127.0.0.1/${p}") 2>/dev/null; then
    echo "端口 ${p} 已被占用，无法启动待测应用" >&2
    exit 2
  fi
done

rand_hex() { od -An -N"$1" -tx1 /dev/urandom | tr -d ' \n'; }

E2E_RUN_PREFIX="e2e-$(date +%s)-$(rand_hex 3)-"
E2E_ADMIN_PASSWORD="Pw-$(rand_hex 12)"
E2E_SETUP_ADMIN_PASSWORD="Pw-$(rand_hex 12)"
E2E_UPGRADE_PASSWORD="Pw-$(rand_hex 12)"
E2E_SETUP_TOKEN="$(rand_hex 16)"
SCHEMA_SUFFIX="$(printf '%s' "${E2E_RUN_PREFIX}" | tr 'A-Z' 'a-z' | sed -e 's/[^a-z0-9]\{1,\}/_/g' -e 's/^_*//' -e 's/_*$//')"
E2E_SETUP_SCHEMA="e2e_setup_${SCHEMA_SUFFIX}"
E2E_UPGRADE_SCHEMA="e2e_upgrade_${SCHEMA_SUFFIX}"
WORK_DIR="$(mktemp -d)"
E2E_UPGRADE_STATE_DIR="${WORK_DIR}/upgrade-state"
export E2E_RUN_PREFIX E2E_ADMIN_PASSWORD E2E_SETUP_ADMIN_PASSWORD E2E_UPGRADE_PASSWORD E2E_SETUP_TOKEN
export E2E_SETUP_SCHEMA E2E_UPGRADE_SCHEMA E2E_UPGRADE_STATE_DIR
export E2E_BASE_URL="http://localhost:${PORT}"
export E2E_SETUP_BASE_URL="http://localhost:${SETUP_PORT}"
export E2E_UPGRADE_BASE_URL="http://localhost:${UPGRADE_PORT}"
echo "运行前缀：${E2E_RUN_PREFIX}"
echo "一次性 schema：${E2E_SETUP_SCHEMA}、${E2E_UPGRADE_SCHEMA}"

APP_LOG="${WORK_DIR}/app.log"
SETUP_LOG="${WORK_DIR}/app-setup.log"
UPGRADE_LOG="${WORK_DIR}/app-upgrade.log"
APP_PID=""
SETUP_PID=""
UPGRADE_PID=""
FIRST_FAILURE=0

record_failure() {
  local code="$1" what="$2"
  echo "失败：${what}（退出码 ${code}）" >&2
  if [ "${FIRST_FAILURE}" -eq 0 ]; then
    FIRST_FAILURE="${code}"
  fi
}

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
  echo "== 按前缀清理合成数据并删除一次性 schema"
  local rc=0
  (cd e2e && npx playwright test --project=cleanup --project=initial-admin-cleanup --project=upgrade-cleanup \
    --reporter=line) < /dev/null || rc=$?
  return "${rc}"
}

on_exit() {
  cleanup_data || true
  stop_pid "${APP_PID}"
  stop_pid "${SETUP_PID}"
  stop_pid "${UPGRADE_PID}"
  rm -rf "${WORK_DIR}"
}
trap on_exit EXIT

wait_up() {
  local name="$1" pid="$2" url="$3" log="$4" status=""
  for _ in $(seq 1 180); do
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
  echo "${name} 180 秒内健康检查未达到 UP：${status}" >&2
  tail -n 40 "${log}" >&2
  return 1
}

start_upgrade_instance() {
  # 参数：附加的命令行参数（如 --spring.flyway.target=1）；日志追加到 UPGRADE_LOG 并另存本次启动片段
  local part="$1"
  shift
  java -jar target/helpdesk.jar --server.port="${UPGRADE_PORT}" --app.db.schema="${E2E_UPGRADE_SCHEMA}" "$@" \
    > "${WORK_DIR}/upgrade-${part}.log" 2>&1 < /dev/null &
  UPGRADE_PID=$!
  wait_up "升级演练实例（${part}）" "${UPGRADE_PID}" "${E2E_UPGRADE_BASE_URL}" "${WORK_DIR}/upgrade-${part}.log"
}

flyway_lines() {
  grep -E 'Migrating schema|is up to date|Successfully applied|Current version of schema' "$1" \
    | sed -e 's/^.*o\.f\.core[^:]*: //' || true
}

run_playwright() {
  local what="$1"
  shift
  local rc=0
  (cd e2e && npx playwright test "$@") < /dev/null || rc=$?
  if [ "${rc}" -ne 0 ]; then
    record_failure "${rc}" "${what}"
  fi
  return 0
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
echo "应用实例迁移日志："
flyway_lines "${APP_LOG}"

echo "== 功能测试：V1 回归 + V2 IQA-001～008（chromium、initial-admin）"
run_playwright "chromium / initial-admin 功能测试" --project=chromium --project=initial-admin

stop_pid "${APP_PID}"
APP_PID=""
stop_pid "${SETUP_PID}"
SETUP_PID=""

echo "== 升级演练（TD-030，localhost:${UPGRADE_PORT}，一次性 schema ${E2E_UPGRADE_SCHEMA}）"
UPGRADE_OK=1
echo "-- ① 以 --spring.flyway.target=1 启动，构造 V1 结构"
if start_upgrade_instance v1 --spring.flyway.target=1; then
  flyway_lines "${WORK_DIR}/upgrade-v1.log"
  if ! grep -q 'now at version v1' "${WORK_DIR}/upgrade-v1.log"; then
    record_failure 1 "V1 结构构造日志中没有迁移到版本 1"
    UPGRADE_OK=0
  fi
else
  record_failure 1 "升级演练实例（V1 结构）启动"
  UPGRADE_OK=0
fi
stop_pid "${UPGRADE_PID}"
UPGRADE_PID=""

if [ "${UPGRADE_OK}" -eq 1 ]; then
  echo "-- ② 写入 V1 合成数据集并保存快照"
  before="${FIRST_FAILURE}"
  run_playwright "upgrade-seed" --project=upgrade-seed --reporter=line
  [ "${FIRST_FAILURE}" -eq "${before}" ] || UPGRADE_OK=0
fi

if [ "${UPGRADE_OK}" -eq 1 ]; then
  echo "-- ③ 以默认配置启动同一 schema：执行 V1 → V2 升级"
  if start_upgrade_instance upgrade; then
    flyway_lines "${WORK_DIR}/upgrade-upgrade.log"
    if ! grep -q 'Migrating schema "'"${E2E_UPGRADE_SCHEMA}"'" to version "2' "${WORK_DIR}/upgrade-upgrade.log"; then
      record_failure 1 "升级启动日志中没有迁移到版本 2"
      UPGRADE_OK=0
    fi
  else
    record_failure 1 "升级演练实例（升级）启动"
    UPGRADE_OK=0
  fi
fi

if [ "${UPGRADE_OK}" -eq 1 ]; then
  echo "-- ③ 升级后核对（IQA-009）与 ④ V2 新增数据并保存快照（IQA-010 准备）"
  before="${FIRST_FAILURE}"
  run_playwright "upgrade-verify" --project=upgrade-verify --reporter=line
  [ "${FIRST_FAILURE}" -eq "${before}" ] || UPGRADE_OK=0
fi
stop_pid "${UPGRADE_PID}"
UPGRADE_PID=""

if [ "${UPGRADE_OK}" -eq 1 ]; then
  echo "-- ⑤ 以相同参数重启（重复执行升级）"
  if start_upgrade_instance repeat; then
    flyway_lines "${WORK_DIR}/upgrade-repeat.log"
    if ! grep -q 'is up to date' "${WORK_DIR}/upgrade-repeat.log"; then
      record_failure 1 "重复升级启动日志中没有 up to date"
      UPGRADE_OK=0
    fi
  else
    record_failure 1 "升级演练实例（重复升级）启动"
    UPGRADE_OK=0
  fi
  if [ "${UPGRADE_OK}" -eq 1 ]; then
    echo "-- 重复升级后逐项比对（IQA-010）"
    run_playwright "upgrade-repeat" --project=upgrade-repeat --reporter=line
  fi
fi
stop_pid "${UPGRADE_PID}"
UPGRADE_PID=""

set +e
cleanup_data
CLEAN_EXIT=$?
set -e
if [ "${CLEAN_EXIT}" -ne 0 ]; then
  record_failure "${CLEAN_EXIT}" "测试数据清理"
fi

if [ "${FIRST_FAILURE}" -ne 0 ]; then
  echo "功能验证失败（首个失败退出码 ${FIRST_FAILURE}）" >&2
  exit "${FIRST_FAILURE}"
fi
echo "功能测试与升级演练全部通过"
exit 0
