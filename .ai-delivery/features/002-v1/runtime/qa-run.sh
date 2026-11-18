#!/usr/bin/env bash
# Independent QA 执行脚本（DLV-20261117-88E4DAE97F6A · 内部 IT 工单系统 V2 · Feature 002-v1）
# 契约：工作目录为空的临时目录；stdin 为 /dev/null；只写工作目录、QA_RESULT_PATH、QA_EVIDENCE_DIR、QA_STATE_DIR（分阶段时）。
# 平台变量：QA_DELIVERY_ID、QA_CANDIDATE_SHA、QA_BASE_URL、QA_HEALTH_PATH、QA_RESULT_PATH、QA_EVIDENCE_DIR；
#           分阶段执行时 QA_PHASE、QA_PHASES、QA_STATE_DIR、QA_REDEPLOY_EVIDENCE（runtime/qa-phases.json 声明 IQA-010 之前重新部署）；
#           ticket-db test 实例 AIDH_DEP_TICKET_DB_*（QA 工作负载身份 + Entra 令牌，TLS 校验开启）；
#           test 机密 AIDH_SECRET_INITIAL_ADMIN_LOGIN_NAME / _PASSWORD（只在内存中使用，结果、证据、截图一律遮挡）。
# 场景：spec.md「验收场景」IQA-001～IQA-010；计划见 ../tasks-qa.md。
# 阶段 1：前置 → IQA-009 → IQA-001～IQA-008 → IQA-010 前置（新增 V2 数据并记录快照到 QA_STATE_DIR）
# 阶段 2（平台对同一候选受控重新部署之后）：IQA-010 比对 → 清理。未分阶段执行时按顺序一次执行全部，IQA-010 走只读核对路径。
# 账号：只使用业务方提供的初始管理员 A1；其他账号由 A1 经「账号管理」页面创建、停用、启用，脚本不以 SQL 写入或修改 app_user。
# 合成账号密码 = HMAC(初始管理员密码, 登录名) 派生，不落盘；QA_STATE_DIR 中不含任何机密值。
set -uo pipefail

WORK_DIR="$(pwd)"

write_fallback_result() {
  # $1 = 类别（env | gap），$2 = 摘要，$3 = 诊断说明
  QA_FB_KIND="$1" QA_FB_SUMMARY="$2" QA_FB_DETAIL="$3" python3 - <<'PY'
import json, os
kind = os.environ["QA_FB_KIND"]
detail = os.environ["QA_FB_DETAIL"]
evd = os.environ.get("QA_EVIDENCE_DIR", "")
files = sorted(os.listdir(evd)) if evd and os.path.isdir(evd) else []
result = {
    "passed": False,
    "testedCommitSha": os.environ.get("QA_CANDIDATE_SHA", ""),
    "environmentUrl": os.environ.get("QA_BASE_URL", ""),
    "summary": os.environ["QA_FB_SUMMARY"],
    "defects": [],
    "testGaps": ([detail] if kind == "gap" else []),
    "evidence": [detail] + files,
    "bugfixResults": [],
    "scenarios": [],
}
with open(os.environ["QA_RESULT_PATH"], "w", encoding="utf-8") as fh:
    json.dump(result, fh, ensure_ascii=False, indent=2)
PY
}

if [ -z "${QA_RESULT_PATH:-}" ]; then
  echo "缺少 QA_RESULT_PATH，无法写出结果" >&2
  exit 1
fi
mkdir -p "${QA_EVIDENCE_DIR:-$WORK_DIR/evidence}" 2>/dev/null || true
missing=""
for v in QA_DELIVERY_ID QA_CANDIDATE_SHA QA_BASE_URL QA_HEALTH_PATH QA_RESULT_PATH QA_EVIDENCE_DIR \
         AIDH_DEP_TICKET_DB_HOST AIDH_DEP_TICKET_DB_PORT AIDH_DEP_TICKET_DB_DATABASE AIDH_DEP_TICKET_DB_USER \
         AIDH_SECRET_INITIAL_ADMIN_LOGIN_NAME AIDH_SECRET_INITIAL_ADMIN_PASSWORD; do
  if [ -z "${!v:-}" ]; then missing="$missing $v"; fi
done
if [ -n "${QA_PHASES:-}" ] && [ "${QA_PHASES:-1}" -gt 1 ] 2>/dev/null && [ -z "${QA_STATE_DIR:-}" ]; then
  missing="$missing QA_STATE_DIR"
fi
if [ -n "$missing" ]; then
  write_fallback_result env "执行环境缺少必需环境变量，未执行场景" "缺少环境变量:${missing}（执行环境问题，不计入产品缺陷）"
  exit 0
fi

BASE="${QA_BASE_URL%/}"
EVD="$QA_EVIDENCE_DIR"
RUNLOG="$EVD/run.log"
log() { echo "[$(date -u +%Y-%m-%dT%H:%M:%SZ)] $*" | tee -a "$RUNLOG" >&2; }

log "QA 开始：delivery=$QA_DELIVERY_ID sha=$QA_CANDIDATE_SHA base=$BASE health=$QA_HEALTH_PATH phase=${QA_PHASE:-}/${QA_PHASES:-} workdir=$WORK_DIR"

# ---------- 前置 1：健康检查（每 10 秒一次，最多重试 5 分钟） ----------
deadline=$(( $(date +%s) + 300 ))
health_ok=0
attempt=0
code="000"
while [ "$(date +%s)" -lt "$deadline" ]; do
  attempt=$((attempt + 1))
  code=$(curl -sS -m 15 -o "$EVD/health-body.txt" -w '%{http_code}' "$BASE$QA_HEALTH_PATH" 2>>"$EVD/health-attempts.log") || code="000"
  echo "attempt=$attempt http=$code at=$(date -u +%FT%TZ)" >> "$EVD/health-attempts.log"
  if [ "$code" = "200" ]; then health_ok=1; break; fi
  sleep 10
done
if [ "$health_ok" != "1" ]; then
  log "健康检查 5 分钟内未通过"
  write_fallback_result env "候选健康检查 5 分钟内未通过，未执行场景（执行环境问题）" \
    "health-attempts.log：$BASE$QA_HEALTH_PATH 在 5 分钟内未返回 200（最后状态 $code，共 $attempt 次）"
  exit 0
fi
log "健康检查通过（第 $attempt 次）"

# ---------- 前置 2：候选身份校验 ----------
idcode=$(curl -sS -m 20 -o "$EVD/identity.json" -w '%{http_code}' "$BASE/api/candidate-identity" 2>>"$EVD/identity-error.log") || idcode="000"
id_check=$(python3 - "$EVD/identity.json" <<'PY'
import json, os, sys
try:
    data = json.load(open(sys.argv[1], encoding="utf-8"))
except Exception as exc:  # noqa: BLE001
    print(f"身份端点返回内容不是 JSON：{exc}")
    sys.exit(0)
problems = []
if data.get("deliveryId") != os.environ["QA_DELIVERY_ID"]:
    problems.append(f"deliveryId={data.get('deliveryId')!r} 期望 {os.environ['QA_DELIVERY_ID']!r}")
if data.get("candidateSha") != os.environ["QA_CANDIDATE_SHA"]:
    problems.append(f"candidateSha={data.get('candidateSha')!r} 期望 {os.environ['QA_CANDIDATE_SHA']!r}")
print("; ".join(problems) if problems else "OK")
PY
)
if [ "$idcode" != "200" ] || [ "$id_check" != "OK" ]; then
  log "候选身份校验失败：http=$idcode $id_check"
  write_fallback_result env "候选身份校验未通过，未执行功能场景（执行环境问题）" \
    "identity.json：/api/candidate-identity http=$idcode；$id_check"
  exit 0
fi
log "候选身份校验通过"

# ---------- 前置 3：安装测试工具（工作目录内） ----------
cd "$WORK_DIR" || exit 1
if [ ! -f package.json ]; then
  printf '{"name":"iqa-runner","version":"1.0.0","private":true,"type":"module"}\n' > package.json
fi
if ! npm install --no-audit --no-fund --loglevel=error \
      playwright@1.65.1 pg@8.23.1 @azure/identity@4.14.1 > "$EVD/npm-install.log" 2>&1; then
  log "npm 安装失败"
  write_fallback_result gap "QA 脚本前置准备失败（npm 依赖安装），未执行场景" \
    "npm-install.log：QA 脚本无法在工作目录安装 playwright/pg/@azure/identity，属于 QA 计划前置准备问题"
  exit 0
fi
if ! npx --no-install playwright install chromium > "$EVD/playwright-install.log" 2>&1; then
  log "Chromium 安装检查未通过（继续尝试使用已预装的浏览器）"
fi
log "测试工具安装完成"

# ---------- 场景执行（Node + Playwright Chromium + pg） ----------
cat > "$WORK_DIR/iqa-main.mjs" <<'NODE_EOF'
import { chromium } from 'playwright';
import pg from 'pg';
import { DefaultAzureCredential } from '@azure/identity';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

const BASE = process.env.QA_BASE_URL.replace(/\/+$/, '');
const SHA = process.env.QA_CANDIDATE_SHA;
const EVD = process.env.QA_EVIDENCE_DIR;
const RESULT = process.env.QA_RESULT_PATH;
const PHASE = Number(process.env.QA_PHASE || 0);
const PHASES = Number(process.env.QA_PHASES || 0);
const STATE_DIR = process.env.QA_STATE_DIR || '';
const MODE = PHASES >= 2 ? (PHASE === 1 ? 'phase1' : 'phase2') : 'single';
const T0 = Date.now();
const TIME_BUDGET_MS = 12 * 60 * 1000;
const DB_SCOPE = 'https://ossrdbms-aad.database.windows.net/.default';
const SERVICES = ['ticket-db'];
const BASELINE_PREFIX = 'q15-72f2b9041e74-'; // V1 IQA-015 持久升级基线（只读，任何清理都不得匹配）
const STATUS = { OPEN: '待处理', IN_PROGRESS: '处理中', RESOLVED: '已解决', CLOSED: '已关闭' };
const ROLE = { EMPLOYEE: '员工', IT_STAFF: 'IT 同事', ADMIN: '管理员' };
const URG = { LOW: '低', MEDIUM: '中', HIGH: '高' };
const HOURS = { HIGH: 4, MEDIUM: 24, LOW: 72 }; // BQ-001 人工答案 A：自然时间
const MSG_LAST_ADMIN = '系统必须至少保留一个可用的管理员';
const DAY = 86400e3, HOUR = 3600e3, MIN = 60e3;

const SEC = {
  login: (process.env.AIDH_SECRET_INITIAL_ADMIN_LOGIN_NAME ?? '').trim(),
  password: process.env.AIDH_SECRET_INITIAL_ADMIN_PASSWORD ?? '',
  display: (process.env.AIDH_SECRET_INITIAL_ADMIN_DISPLAY_NAME ?? '').trim(),
  token: process.env.AIDH_SECRET_INITIAL_SETUP_TOKEN ?? '',
};
const MASK = '«已遮挡»';
const SECRET_VALUES = [...new Set([SEC.login, SEC.password, SEC.display, SEC.token].filter(v => v && v.length >= 3))]
  .sort((a, b) => b.length - a.length);
function redact(v) {
  let t = String(v);
  for (const sv of SECRET_VALUES) {
    t = t.split(sv).join(MASK);
    const j = JSON.stringify(sv).slice(1, -1);
    if (j !== sv) t = t.split(j).join(MASK);
    const h = sv.replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
    if (h !== sv) t = t.split(h).join(MASK);
  }
  return t;
}
// 合成账号密码：由初始管理员密码派生（HMAC），不写入任何文件；跨阶段可重新推导
const pwFor = login => `Qa2-${crypto.createHmac('sha256', SEC.password).update(`v2qa:${login}`).digest('hex').slice(0, 24)}`;
const sha = o => crypto.createHash('sha256').update(typeof o === 'string' ? o : JSON.stringify(o)).digest('hex');

const runLog = [];
function rlog(msg) { const line = redact(`[${new Date().toISOString()}] ${msg}`); runLog.push(line); console.log(line); }
function writeEvd(name, content) { try { fs.writeFileSync(path.join(EVD, name), redact(content)); } catch (e) { rlog(`写证据失败 ${name}: ${e.message}`); } }

// ---------------- 时间（公司时区 Asia/Shanghai，UTC+8，无夏令时） ----------------
const pad = n => String(n).padStart(2, '0');
const shD = d => new Date(new Date(d).getTime() + 8 * HOUR);
const shDate = d => { const x = shD(d); return `${x.getUTCFullYear()}-${pad(x.getUTCMonth() + 1)}-${pad(x.getUTCDate())}`; };
const shMinute = d => { const x = shD(d); return `${shDate(d)} ${pad(x.getUTCHours())}:${pad(x.getUTCMinutes())}`; };
function weekRange(at = Date.now()) {
  const x = shD(at);
  const back = (x.getUTCDay() + 6) % 7;
  const start = Date.UTC(x.getUTCFullYear(), x.getUTCMonth(), x.getUTCDate() - back) - 8 * HOUR;
  return { ws: new Date(start), we: new Date(start + 7 * DAY) };
}
const expectedOf = (submittedAt, urgency) => new Date(new Date(submittedAt).getTime() + HOURS[urgency] * HOUR);

// ---------------- 数据库（ticket-db test 实例，Entra 令牌，TLS 校验开启） ----------------
const cred = new DefaultAzureCredential();
const pool = new pg.Pool({
  host: process.env.AIDH_DEP_TICKET_DB_HOST,
  database: process.env.AIDH_DEP_TICKET_DB_DATABASE,
  user: process.env.AIDH_DEP_TICKET_DB_USER,
  port: Number(process.env.AIDH_DEP_TICKET_DB_PORT),
  ssl: { rejectUnauthorized: true },
  max: 4,
  password: async () => (await cred.getToken(DB_SCOPE)).token,
});
const q = async (sql, params = []) => (await pool.query(sql, params)).rows;
const num = v => (v === null || v === undefined ? null : Number(v));

async function mkCat(name) {
  const r = await q('INSERT INTO category(name,active,created_at) VALUES($1,TRUE,now()) RETURNING id', [name]);
  return { id: num(r[0].id), name };
}
// 回溯时间的合成工单（design「数据库数据契约」）：只写 V1 列；期望解决时间由 submitted_at 与 urgency 独立计算
async function mkTicket(t) {
  const at = t.submittedAt ?? new Date();
  const r = await q(`INSERT INTO ticket(title,description,category_id,urgency,submitter_id,submitted_at,status,assignee_id,
      first_resolved_at,first_resolved_assignee_id,updated_at) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$6) RETURNING id`,
  [t.title, t.description ?? '合成描述（Independent QA）', t.categoryId, t.urgency, t.submitterId, at, t.status ?? 'OPEN',
    t.assigneeId ?? null, t.firstResolvedAt ?? null, t.firstResolvedAssigneeId ?? null]);
  const id = num(r[0].id);
  for (const e of t.events ?? []) {
    await q(`INSERT INTO ticket_event(ticket_id,actor_id,event_type,from_assignee_id,to_assignee_id,from_status,to_status,created_at)
      VALUES($1,$2,$3,$4,$5,$6,$7,$8)`, [id, e.actorId, e.type, e.fromAssignee ?? null, e.toAssignee ?? null,
      e.fromStatus ?? null, e.toStatus ?? null, e.at]);
  }
  return id;
}
// 只清理工单、沟通、处理记录与类别（按前缀）；账号不以 SQL 修改，由 A1 经页面停用
async function cleanupTicketsByPrefix(prefix) {
  if (!/^v2q[0-9a-f]{6}-$/.test(prefix) || prefix.startsWith(BASELINE_PREFIX)) throw new Error(`拒绝清理前缀 ${prefix}`);
  const p = `${prefix}%`;
  const users = '(SELECT id FROM app_user WHERE login_name LIKE $1)';
  const cats = '(SELECT id FROM category WHERE name LIKE $1)';
  const tickets = `(SELECT id FROM ticket WHERE title LIKE $1 OR submitter_id IN ${users} OR category_id IN ${cats})`;
  await q(`DELETE FROM ticket_event WHERE ticket_id IN ${tickets}`, [p]);
  await q(`DELETE FROM ticket_comment WHERE ticket_id IN ${tickets}`, [p]);
  await q(`DELETE FROM ticket WHERE id IN ${tickets}`, [p]);
  await q(`DELETE FROM category c WHERE c.name LIKE $1 AND NOT EXISTS (SELECT 1 FROM ticket t WHERE t.category_id = c.id)`, [p]);
}

// ---------------- 场景与检查点 ----------------
class Halt extends Error {}
let cur = null;
const scenarios = [];
const defects = [];
const testGaps = [];
const notes = [];
let envProblem = null;
const newCur = (id, title) => ({ id, title, checkpoints: [], failures: [], log: [], net: [], files: [], shotN: 0, server5xx: [], actors: [] });

function netlog(line) { if (cur) cur.net.push(`[${new Date().toISOString()}] ${line}`); }
function cp(step, ok, observation, kind = 'defect') {
  const passed = !!ok;
  const obs = redact(observation);
  cur.checkpoints.push({ step, passed, observation: obs.slice(0, 1500) });
  cur.log.push(`${passed ? 'PASS' : 'FAIL'} | ${step} | ${obs}`);
  if (!passed) cur.failures.push({ step, observation: obs.slice(0, 700), kind });
  if (!passed && kind === 'env') envProblem = envProblem ?? `${cur.id}：${step}：${obs.slice(0, 600)}`;
  return passed;
}
function info(step, observation) { cur.log.push(`INFO | ${step} | ${redact(observation)}`); }
function need(step, ok, observation, kind = 'defect') { if (!cp(step, ok, observation, kind)) throw new Halt(step); }
async function shot(page, label) {
  if (!page || !cur) return;
  cur.shotN += 1;
  const name = `${cur.id}-${pad(cur.shotN)}-${label.replace(/[^\w一-龥-]+/g, '_').slice(0, 40)}.png`;
  try {
    await page.evaluate(({ secrets, mask }) => {
      const w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
      for (let n = w.nextNode(); n; n = w.nextNode()) for (const sv of secrets) if (n.nodeValue.includes(sv)) n.nodeValue = n.nodeValue.split(sv).join(mask);
      for (const el of document.querySelectorAll('input, textarea')) if (secrets.some(sv => (el.value ?? '').includes(sv))) el.setAttribute('data-qa-mask', '1');
    }, { secrets: SECRET_VALUES, mask: MASK }).catch(() => {});
    await page.screenshot({ path: path.join(EVD, name), fullPage: true, mask: [page.locator('[data-qa-mask]')] });
    cur.files.push(name);
  } catch (e) { cur.log.push(`截图失败 ${label}: ${e.message}`); }
}
async function saveHtml(page, label) {
  if (!page) return;
  const name = `${cur.id}-${label}.html`;
  try { writeEvd(name, await page.content()); cur.files.push(name); } catch { /* 页面已关闭 */ }
}
const eqSet = (a, b) => { const A = new Set(a.map(Number)), B = new Set(b.map(Number)); return A.size === B.size && [...A].every(x => B.has(x)); };
const fmtIds = a => `[${[...new Set(a.map(Number))].sort((x, y) => x - y).join(',')}]`;
const isEnvError = msg => /net::ERR_NAME|net::ERR_CONNECTION|net::ERR_CERT|net::ERR_SSL|ECONNREFUSED|ENOTFOUND|EAI_AGAIN|certificate|ETIMEDOUT|ECONNRESET|password authentication failed|AADSTS/i.test(msg);

async function closeActors() { for (const a of cur.actors) { try { await a.ctx.close(); } catch { /* ignore */ } } cur.actors = []; }
async function scenario(id, title, fn) {
  cur = newCur(id, title);
  rlog(`开始 ${id} ${title}`);
  if (Date.now() - T0 > TIME_BUDGET_MS) {
    cp('时间预算', false, 'QA 脚本总时长预算（12 分钟）已用尽，本场景未执行', 'gap');
  } else {
    try { await fn(); } catch (e) {
      if (!(e instanceof Halt)) {
        const msg = `${e?.name ?? 'Error'}: ${e?.message ?? e}`.slice(0, 800);
        let kind = cur.server5xx.length ? 'defect' : 'gap';
        if (isEnvError(msg)) { kind = 'env'; envProblem = envProblem ?? `${id}: ${msg}`; }
        cp('脚本执行', false, `执行中断：${msg}${cur.server5xx.length ? `；服务端 5xx：${cur.server5xx.join(', ')}` : ''}`, kind);
        cur.log.push(String(e?.stack ?? ''));
      }
      for (const a of cur.actors) { await shot(a.page, `failure-${a.label}`); await saveHtml(a.page, `failure-${a.label}`); }
    }
  }
  await closeActors();
  const logName = `${id}.log`;
  writeEvd(logName, [`# ${id} ${title}`, `candidate=${SHA} base=${BASE} prefix=${P} mode=${MODE}`, '', '## 检查点', ...cur.log, '',
    '## 网络请求与响应（方法 URL -> 状态码；不记录请求体与凭据）', ...cur.net].join('\n'));
  const files = [logName, ...cur.files];
  const passed = cur.failures.length === 0 && cur.checkpoints.length > 0;
  for (const f of cur.failures) {
    const line = `${id}｜环节：${f.step}｜${f.observation}｜证据：${logName}`;
    if (f.kind === 'defect') defects.push(line); else if (f.kind === 'gap') testGaps.push(line);
  }
  scenarios.push({ id, passed, externalServices: SERVICES, checkpoints: cur.checkpoints, evidence: files.join('; ') });
  rlog(`结束 ${id}：${passed ? '通过' : '失败'}`);
}

// ---------------- 浏览器与页面操作 ----------------
let browser;
async function actor(label, account) {
  const ctx = await browser.newContext({ baseURL: BASE, locale: 'zh-CN', timezoneId: 'Asia/Shanghai' });
  const page = await ctx.newPage();
  page.setDefaultTimeout(20000);
  page.setDefaultNavigationTimeout(45000);
  page.on('response', r => {
    const t = r.request().resourceType();
    if (t === 'document' || t === 'fetch' || t === 'xhr') {
      netlog(`${label} ${r.request().method()} ${r.url()} -> ${r.status()}`);
      if (r.status() >= 500 && cur) cur.server5xx.push(`${r.request().method()} ${r.url()} ${r.status()}`);
    }
  });
  page.on('requestfailed', rq => netlog(`${label} ${rq.method()} ${rq.url()} FAILED ${rq.failure()?.errorText}`));
  page.on('console', m => netlog(`${label} console.${m.type()}: ${m.text()}`));
  const a = { label, account, ctx, page };
  cur.actors.push(a);
  if (account) {
    const ok = await login(a);
    need(`${label} 登录`, ok, ok ? `已登录 ${account.login}` : `登录失败，当前 URL ${page.url()}`);
  }
  return a;
}
async function go(page, p) { const r = await page.goto(p); return r ? r.status() : 0; }
async function submit(page, locator) {
  const nav = page.waitForEvent('load', { timeout: 45000 });
  await locator.click();
  await nav;
}
async function login(a, acc = a.account) {
  await go(a.page, '/login');
  await a.page.getByTestId('login-username').fill(acc.login);
  await a.page.getByTestId('login-password').fill(acc.password);
  await submit(a.page, a.page.getByTestId('login-submit'));
  return (await a.page.getByTestId('logout').count()) > 0;
}
// 返回 { loggedIn, url, error }：用于比较停用账号与错误凭据的提示
async function tryLogin(a, acc) {
  const ok = await login(a, acc);
  const error = await txt(a.page, 'login-error');
  const res = { loggedIn: ok, url: new URL(a.page.url()).pathname + new URL(a.page.url()).search, error };
  if (ok) await logout(a);
  return res;
}
async function logout(a) { if (await a.page.getByTestId('logout').count()) await submit(a.page, a.page.getByTestId('logout')); }
async function postForm(a, p, form) {
  const token = await a.page.locator('input[name="_csrf"]').first().getAttribute('value').catch(() => null);
  const r = await a.ctx.request.post(p, { form: { ...form, _csrf: token ?? '' }, maxRedirects: 0, headers: { Accept: 'text/html' } });
  const body = await r.text();
  netlog(`${a.label} POST ${p} (直接请求，字段 ${Object.keys(form).join(',') || '无'}) -> ${r.status()} location=${r.headers().location ?? ''}`);
  if (r.status() >= 500) cur.server5xx.push(`POST ${p} ${r.status()}`);
  return { status: r.status(), body, location: r.headers().location ?? '' };
}
async function txt(page, id) { const l = page.getByTestId(id).first(); return (await l.count()) ? ((await l.textContent()) ?? '').trim() : null; }
async function flash(page) { return txt(page, 'flash'); }
// V1 字段（与 V1 IQA-015 快照结构完全相同，键顺序不可改）
async function readDetail(page) {
  return {
    title: await txt(page, 'detail-title'), status: await txt(page, 'ticket-status'), assignee: await txt(page, 'ticket-assignee'),
    category: await txt(page, 'detail-category'), urgency: await txt(page, 'detail-urgency'), submitter: await txt(page, 'detail-submitter'),
    submitted: await txt(page, 'detail-submitted'), description: await txt(page, 'detail-description'),
    comments: await page.locator('[data-testid=comment-item]').evaluateAll(els => els.map(e => ({
      author: e.querySelector('[data-testid=comment-author]')?.textContent.trim(), role: e.querySelector('[data-testid=comment-role]')?.textContent.trim(),
      time: e.querySelector('[data-testid=comment-time]')?.textContent.trim(), text: e.querySelector('[data-testid=comment-text]')?.textContent.trim() }))),
    events: await page.locator('[data-testid=event-item]').evaluateAll(els => els.map(e => ({
      type: e.getAttribute('data-event-type'), actor: e.querySelector('[data-testid=event-actor]')?.textContent.trim(),
      from: e.querySelector('[data-testid=event-from]')?.textContent.trim(), to: e.querySelector('[data-testid=event-to]')?.textContent.trim(),
      time: e.querySelector('[data-testid=event-time]')?.textContent.trim() }))),
  };
}
async function openDetail(a, id) { const st = await go(a.page, `/tickets/${id}`); return st === 200 ? { httpStatus: st, ...(await readDetail(a.page)) } : { httpStatus: st }; }
// V2：期望解决时间与超时标识（含显示样式是否醒目）
async function readV2(page) {
  const exp = page.getByTestId('ticket-expected').first();
  const has = (await exp.count()) > 0;
  const tag = page.getByTestId('ticket-overdue');
  return {
    expectedAt: has ? await exp.getAttribute('data-expected-at') : null,
    expectedText: has ? ((await exp.textContent()) ?? '').trim() : null,
    overdueAttr: await page.getByTestId('ticket-detail').getAttribute('data-overdue').catch(() => null),
    overdueTag: (await tag.count()) > 0,
    overdueText: (await tag.count()) ? ((await tag.first().textContent()) ?? '').trim() : null,
    style: (await tag.count()) ? await styleOf(tag.first()) : null,
  };
}
async function styleOf(loc) {
  return loc.evaluate(el => {
    const s = getComputedStyle(el); const b = getComputedStyle(document.body);
    const r = el.getBoundingClientRect();
    return { bg: s.backgroundColor, color: s.color, weight: s.fontWeight, bodyColor: b.color, visible: r.width > 0 && r.height > 0 && s.visibility !== 'hidden' && s.display !== 'none' };
  });
}
const prominent = st => !!st && st.visible && ((st.bg && !/rgba\(0, 0, 0, 0\)|transparent/.test(st.bg)) || Number(st.weight) >= 600 || st.color !== st.bodyColor);
async function detailFull(a, id) {
  const t1 = Date.now();
  const d = await openDetail(a, id);
  if (d.httpStatus !== 200) return { ...d, t1, t2: Date.now() };
  return { ...d, ...(await readV2(a.page)), t1, t2: Date.now() };
}
async function listRows(page) {
  return page.getByTestId('ticket-row').evaluateAll(els => els.map(e => {
    const g = id => e.querySelector(`[data-testid=${id}]`);
    const tag = g('row-overdue');
    let style = null;
    if (tag) { const s = getComputedStyle(tag); const b = getComputedStyle(document.body); const r = tag.getBoundingClientRect(); style = { bg: s.backgroundColor, color: s.color, weight: s.fontWeight, bodyColor: b.color, visible: r.width > 0 && r.height > 0 }; }
    return { id: Number(e.getAttribute('data-ticket-id')), overdueAttr: e.getAttribute('data-overdue'), overdueTag: !!tag, overdueText: tag?.textContent.trim() ?? null, style,
      expectedAt: g('row-expected')?.getAttribute('data-expected-at') ?? null, expectedText: g('row-expected')?.textContent.trim() ?? null,
      status: g('row-status')?.textContent.trim(), assignee: g('row-assignee')?.textContent.trim(), submitter: g('row-submitter')?.textContent.trim(),
      title: g('row-title')?.textContent.trim() };
  }));
}
// 打开列表地址并收集全部分页的行（返回行与查看时间窗口）
async function collectRows(a, url) {
  const t1 = Date.now();
  const st = await go(a.page, url);
  const rows = [];
  for (let i = 0; i < 60; i += 1) {
    rows.push(...(await listRows(a.page)));
    const next = a.page.getByTestId('page-next');
    if (!(await next.count())) break;
    await go(a.page, await next.getAttribute('href'));
  }
  return { httpStatus: st, rows, t1, t2: Date.now(), url: a.page.url() };
}
async function searchForm(a, f) {
  await go(a.page, '/tickets');
  const p = a.page;
  await p.getByTestId('filter-q').fill(f.q ?? '');
  await p.getByTestId('filter-status').selectOption(f.status ?? '');
  if (f.assignee !== undefined) await p.getByTestId('filter-assignee').selectOption(String(f.assignee));
  const t1 = Date.now();
  await submit(p, p.getByTestId('filter-submit'));
  const rows = [];
  for (let i = 0; i < 60; i += 1) {
    rows.push(...(await listRows(p)));
    const next = p.getByTestId('page-next');
    if (!(await next.count())) break;
    await go(p, await next.getAttribute('href'));
  }
  return { rows, t1, t2: Date.now(), url: p.url() };
}
async function navSet(page) {
  const ids = ['nav-my-tickets', 'nav-new-ticket', 'nav-all-tickets', 'nav-admin-users', 'nav-admin-categories', 'nav-report'];
  const out = [];
  for (const id of ids) if (await page.getByTestId(id).count()) out.push(id);
  return out;
}
const pathOf = page => new URL(page.url()).pathname;

// 期望解决时间核对：属性（ISO UTC）与显示（公司时区，精确到分钟）
function expectedOk(view, expDate) {
  if (!view || !view.expectedAt) return false;
  const at = Date.parse(view.expectedAt);
  return Number.isFinite(at) && Math.abs(at - expDate.getTime()) < 1000 && view.expectedText === shMinute(expDate);
}
// 超时标识期望：未解决且查看时刻晚于期望解决时间；查看时间窗口跨越期望时刻（±5 秒）时两种结果都接受
function overdueExpect(statusCode, expDate, t1, t2) {
  if (!['OPEN', 'IN_PROGRESS'].includes(statusCode)) return false;
  const e = expDate.getTime();
  if (t1 - 5000 > e) return true;
  if (t2 + 5000 < e) return false;
  return 'either';
}
function overdueOk(view, expFlag) {
  const shown = !!view.overdueTag && String(view.overdueAttr) === 'true';
  const hidden = !view.overdueTag && String(view.overdueAttr) === 'false';
  if (expFlag === 'either') return shown || hidden;
  if (expFlag) return shown && view.overdueText === '已超时' && prominent(view.style);
  return hidden;
}
const statusCodeOf = label => Object.keys(STATUS).find(k => STATUS[k] === label) ?? label;

// ---------------- 账号（全部经页面创建、停用、启用） ----------------
const acc = {};
let A1 = null;
async function createUserUI(admin, key, display, role) {
  const login = `${P}${key}`;
  const password = pwFor(login);
  await go(admin.page, '/admin/users');
  await admin.page.getByTestId('user-login').fill(login);
  await admin.page.getByTestId('user-password').fill(password);
  await admin.page.getByTestId('user-display').fill(display);
  await admin.page.getByTestId('user-role').selectOption(role);
  await submit(admin.page, admin.page.getByTestId('user-create'));
  const r = await q('SELECT id, role, active FROM app_user WHERE login_name=$1', [login]);
  if (r.length !== 1 || r[0].role !== role) throw new Error(`经页面创建账号 ${login}（${role}）未成功：库中=${JSON.stringify(r)}，页面提示=${await flash(admin.page)}`);
  return { id: num(r[0].id), login, display, role, password };
}
const userRow = (page, login) => page.locator(`[data-testid=user-row][data-login-name="${login}"]`);
async function userStatus(page, login, reload = true) {
  if (reload) await go(page, '/admin/users');
  const r = userRow(page, login);
  if (!(await r.count())) return null;
  return { text: ((await r.getByTestId('user-row-status').textContent()) ?? '').trim(), active: await r.getByTestId('user-row-status').getAttribute('data-active'),
    display: ((await r.getByTestId('user-row-display').textContent()) ?? '').trim(), role: ((await r.getByTestId('user-row-role').textContent()) ?? '').trim(),
    canDeactivate: (await r.getByTestId('user-deactivate').count()) > 0, canActivate: (await r.getByTestId('user-activate').count()) > 0 };
}
async function setActiveUI(admin, login, active) {
  await go(admin.page, '/admin/users');
  const btn = userRow(admin.page, login).getByTestId(active ? 'user-activate' : 'user-deactivate');
  if (!(await btn.count())) return { clicked: false, flash: null, url: admin.page.url() };
  const respP = admin.page.waitForResponse(r => r.request().method() === 'POST' && /\/admin\/users\/\d+\/(de)?activate$/.test(new URL(r.url()).pathname), { timeout: 45000 });
  await submit(admin.page, btn);
  const resp = await respP.catch(() => null);
  return { clicked: true, flash: await flash(admin.page), url: admin.page.url(), postStatus: resp ? resp.status() : null };
}
const dbActive = async id => { const r = await q('SELECT active FROM app_user WHERE id=$1', [id]); return r[0] ? r[0].active : null; };

// ---------------- 工单页面操作 ----------------
async function submitTicketUI(a, t) {
  await go(a.page, '/tickets/new');
  await a.page.getByTestId('ticket-title').fill(t.title);
  await a.page.getByTestId('ticket-description').fill(t.description ?? `合成描述 ${t.title}`);
  await a.page.getByTestId('ticket-category').selectOption(String(t.categoryId));
  await a.page.getByTestId('ticket-urgency').selectOption(t.urgency);
  await submit(a.page, a.page.getByTestId('ticket-submit'));
  const m = /\/tickets\/(\d+)$/.exec(pathOf(a.page));
  if (!m) throw new Error(`提交工单「${t.title}」后未进入详情页：URL=${a.page.url()} 提示=${await flash(a.page)}`);
  return Number(m[1]);
}
async function commentUI(a, id, body) { await go(a.page, `/tickets/${id}`); await a.page.getByTestId('comment-body').fill(body); await submit(a.page, a.page.getByTestId('comment-submit')); return flash(a.page); }
async function statusUI(a, id, s) { await go(a.page, `/tickets/${id}`); await a.page.getByTestId('status-select').selectOption(s); await submit(a.page, a.page.getByTestId('status-submit')); return flash(a.page); }
async function claimUI(a, id) { await go(a.page, `/tickets/${id}`); await submit(a.page, a.page.getByTestId('claim-button')); return flash(a.page); }
async function assignUI(a, id, targetId) { await go(a.page, `/tickets/${id}`); await a.page.getByTestId('assign-select').selectOption(String(targetId)); await submit(a.page, a.page.getByTestId('assign-submit')); return flash(a.page); }
async function assignOptions(page) {
  return page.getByTestId('assign-select').locator('option').evaluateAll(os => os.filter(o => o.value).map(o => ({ id: Number(o.value), name: o.textContent.trim() })));
}
const ticketRow = async id => (await q('SELECT id, urgency, submitted_at, status, assignee_id, first_resolved_at FROM ticket WHERE id=$1', [id]))[0];

// ---------------- 周报表（页面读取 + 独立 SQL 计算） ----------------
async function readReport(a) {
  const t1 = Date.now();
  const st = await go(a.page, '/admin/reports/weekly');
  const page = a.page;
  if (st !== 200 || !(await page.getByTestId('report-week').count())) return { httpStatus: st, t1, t2: Date.now() };
  const week = await txt(page, 'report-week');
  const cats = await page.getByTestId('report-category-row').evaluateAll(els => els.map(e => ({ id: Number(e.getAttribute('data-category-id')),
    count: Number(e.querySelector('[data-testid=report-count]')?.textContent.trim()) })));
  const staff = await page.getByTestId('report-staff-row').evaluateAll(els => els.map(e => ({ id: Number(e.getAttribute('data-user-id')),
    count: Number(e.querySelector('[data-testid=report-count]')?.textContent.trim()) })));
  const unassigned = Number(await page.locator('[data-testid=report-unassigned] [data-testid=report-count]').textContent());
  const avgText = await txt(page, 'report-avg');
  const avgAttr = await page.getByTestId('report-avg').getAttribute('data-avg-seconds');
  const backlog = Number(await txt(page, 'report-backlog'));
  const orText = await txt(page, 'report-overdue-resolved');
  const m = /(\d{4}-\d{2}-\d{2}).*?(\d{4}-\d{2}-\d{2})/.exec(week ?? '');
  return { httpStatus: st, week, weekStartDate: m?.[1] ?? null, weekEndDate: m?.[2] ?? null, cats, staff, unassigned, avgText,
    avgSec: avgAttr === null || avgAttr === '' ? null : Number(avgAttr), backlog, overdueResolvedText: orText,
    overdueResolved: orText === null || orText === '' ? null : Number(orText), t1, t2: Date.now() };
}
// V1 口径（V1 FR-025～FR-028）与 FR-017（BQ-002 人工答案 A）的独立计算；不复用产品 SQL
const EXPECTED_SQL = "(t.submitted_at + CASE t.urgency WHEN 'HIGH' THEN interval '4 hours' WHEN 'MEDIUM' THEN interval '24 hours' WHEN 'LOW' THEN interval '72 hours' END)";
async function sqlReport(at = Date.now()) {
  const { ws, we } = weekRange(at);
  const cats = await q('SELECT category_id id, count(*) c FROM ticket WHERE submitted_at >= $1 AND submitted_at < $2 GROUP BY 1', [ws, we]);
  const staff = await q(`SELECT first_resolved_assignee_id id, count(*) c FROM ticket WHERE first_resolved_at >= $1 AND first_resolved_at < $2
    AND first_resolved_assignee_id IS NOT NULL GROUP BY 1`, [ws, we]);
  const agg = (await q(`SELECT count(*) n, count(*) FILTER (WHERE first_resolved_assignee_id IS NULL) un,
    avg(EXTRACT(EPOCH FROM first_resolved_at - submitted_at)) a FROM ticket WHERE first_resolved_at >= $1 AND first_resolved_at < $2`, [ws, we]))[0];
  const backlog = num((await q("SELECT count(*) c FROM ticket WHERE status IN ('OPEN','IN_PROGRESS') AND submitted_at < $1", [new Date(at - 72 * HOUR)]))[0].c);
  const overdue = num((await q(`SELECT count(*) c FROM ticket t WHERE t.first_resolved_at >= $1 AND t.first_resolved_at < $2 AND t.first_resolved_at > ${EXPECTED_SQL}`, [ws, we]))[0].c);
  const toMap = rows => Object.fromEntries(rows.map(r => [String(num(r.id)), num(r.c)]).filter(([, c]) => c > 0));
  return { weekStartDate: shDate(ws), weekEndDate: shDate(new Date(we.getTime() - DAY)), cats: toMap(cats), staff: toMap(staff),
    unassigned: num(agg.un), avgSec: agg.a === null ? null : Number(agg.a), backlog, overdueResolved: overdue };
}
function reportDiff(rep, s) {
  const diffs = [];
  const nz = rows => Object.fromEntries(rows.filter(r => r.count > 0).map(r => [String(r.id), r.count]));
  const eqMap = (x, y) => JSON.stringify(Object.keys(x).sort().map(k => [k, x[k]])) === JSON.stringify(Object.keys(y).sort().map(k => [k, y[k]]));
  if (rep.weekStartDate !== s.weekStartDate || rep.weekEndDate !== s.weekEndDate) diffs.push(`统计周期 页面=${rep.weekStartDate}~${rep.weekEndDate} 期望=${s.weekStartDate}~${s.weekEndDate}`);
  if (!eqMap(nz(rep.cats ?? []), s.cats)) diffs.push(`类别数量 页面=${JSON.stringify(nz(rep.cats ?? []))} 期望=${JSON.stringify(s.cats)}`);
  if (!eqMap(nz(rep.staff ?? []), s.staff)) diffs.push(`IT 同事处理量 页面=${JSON.stringify(nz(rep.staff ?? []))} 期望=${JSON.stringify(s.staff)}`);
  if (rep.unassigned !== s.unassigned) diffs.push(`未分配 页面=${rep.unassigned} 期望=${s.unassigned}`);
  if (s.avgSec === null ? !(rep.avgSec === null && rep.avgText === '无数据') : !(rep.avgSec !== null && Math.abs(rep.avgSec - s.avgSec) <= 1.5)) diffs.push(`平均处理时长 页面=${rep.avgText}/${rep.avgSec} 期望=${s.avgSec}`);
  if (rep.backlog !== s.backlog) diffs.push(`超过 3 天未解决 页面=${rep.backlog} 期望=${s.backlog}`);
  if (rep.overdueResolved !== s.overdueResolved) diffs.push(`本周超时解决 页面=${rep.overdueResolvedText} 期望=${s.overdueResolved}`);
  return diffs;
}
// 页面读取前后各做一次独立计算；任一次完全一致即判一致（消除共享库中并发写入与时间推移造成的瞬时差异）
async function reportCheck(a) {
  const before = await sqlReport(Date.now());
  const rep = await readReport(a);
  const after = await sqlReport(Date.now());
  const d1 = reportDiff(rep, before), d2 = reportDiff(rep, after);
  return { rep, sql: d2.length === 0 ? after : before, ok: rep.httpStatus === 200 && (d1.length === 0 || d2.length === 0), diffs: d2.length ? d1.length ? d2 : d1 : d2 };
}

// ================= 运行数据 =================
const STATE_FILE = STATE_DIR ? path.join(STATE_DIR, 'iqa-state.json') : '';
// 跨阶段状态：主通道为 QA_STATE_DIR/iqa-state.json；同一内容（不含机密）另存一份到 ticket-db test 库中 QA 自有的 schema（iqa_qa_phase），
// 用于平台未恢复 QA_STATE_DIR 时的回退。只按 deliveryId + candidateSha 读取 3 小时内最新一份，阶段 2 结束后删除。
const STATE_SCHEMA = 'iqa_qa_phase';
const stateSource = { file: STATE_FILE, listing: [], source: null, db: null };
function listDir(dir, depth = 0) {
  const out = [];
  try {
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
      const f = path.join(dir, e.name);
      if (e.isDirectory()) { out.push(`${f}/`); if (depth < 4) out.push(...listDir(f, depth + 1)); }
      else { let size = -1; try { size = fs.statSync(f).size; } catch { /* ignore */ } out.push(`${f} (${size} 字节)`); }
    }
  } catch (e) { out.push(`${dir}：${e.code ?? e.message}`); }
  return out;
}
function findStateFile(dir, depth = 0) {
  try {
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
      const f = path.join(dir, e.name);
      if (e.isFile() && e.name === 'iqa-state.json') return f;
      if (e.isDirectory() && depth < 4) { const r = findStateFile(f, depth + 1); if (r) return r; }
    }
  } catch { /* ignore */ }
  return null;
}
function parseState(text) { const o = JSON.parse(text); return o && o.v === 1 && typeof o.prefix === 'string' && o.prep ? o : null; }
async function ensureStateTable() {
  await q(`CREATE SCHEMA IF NOT EXISTS ${STATE_SCHEMA}`);
  await q(`CREATE TABLE IF NOT EXISTS ${STATE_SCHEMA}.phase_state (id bigserial PRIMARY KEY, delivery_id text NOT NULL, candidate_sha text NOT NULL, prefix text NOT NULL, digest text NOT NULL, body text NOT NULL, created_at timestamptz NOT NULL DEFAULT now())`);
}
async function saveStateDb(json, prefix) {
  try {
    await ensureStateTable();
    await q(`DELETE FROM ${STATE_SCHEMA}.phase_state WHERE delivery_id=$1 AND (created_at < now() - interval '1 day' OR prefix=$2)`, [process.env.QA_DELIVERY_ID, prefix]);
    await q(`INSERT INTO ${STATE_SCHEMA}.phase_state(delivery_id, candidate_sha, prefix, digest, body) VALUES ($1,$2,$3,$4,$5)`, [process.env.QA_DELIVERY_ID, SHA, prefix, sha(json), json]);
    return { ok: true, digest: sha(json) };
  } catch (e) { return { ok: false, error: String(e.message).slice(0, 300) }; }
}
async function loadStateDb() {
  try {
    const r = await q(`SELECT prefix, digest, body, created_at FROM ${STATE_SCHEMA}.phase_state WHERE delivery_id=$1 AND candidate_sha=$2 AND created_at > now() - interval '3 hours' ORDER BY created_at DESC, id DESC LIMIT 1`, [process.env.QA_DELIVERY_ID, SHA]);
    if (!r.length) return { ok: false, error: '库中没有本候选 3 小时内的阶段 1 状态' };
    if (sha(r[0].body) !== r[0].digest) return { ok: false, error: '库中状态摘要不一致' };
    return { ok: true, state: parseState(r[0].body), prefix: r[0].prefix, digest: r[0].digest, createdAt: new Date(r[0].created_at).toISOString() };
  } catch (e) { return { ok: false, error: String(e.message).slice(0, 300) }; }
}
async function dropStateDb(prefix) {
  try { await q(`DELETE FROM ${STATE_SCHEMA}.phase_state WHERE delivery_id=$1 AND prefix=$2`, [process.env.QA_DELIVERY_ID, prefix]); } catch (e) { rlog(`删除库中阶段状态失败：${e.message}`); }
}
let state = null;
if (MODE === 'phase2') {
  stateSource.listing = STATE_DIR ? listDir(STATE_DIR) : ['QA_STATE_DIR 未设置'];
  const f = STATE_DIR ? findStateFile(STATE_DIR) : null;
  if (f) { try { state = parseState(fs.readFileSync(f, 'utf-8')); if (state) stateSource.source = `QA_STATE_DIR：${f}`; } catch (e) { stateSource.fileError = e.message; } }
  if (!state) {
    try { stateSource.db = await loadStateDb(); } catch (e) { stateSource.db = { ok: false, error: e.message }; }
    if (stateSource.db?.ok && stateSource.db.state) { state = stateSource.db.state; stateSource.source = `ticket-db ${STATE_SCHEMA}.phase_state（QA_STATE_DIR 未恢复，回退）：prefix=${stateSource.db.prefix} 写入于 ${stateSource.db.createdAt}`; }
    if (stateSource.db) delete stateSource.db.state;
  }
  writeEvd('IQA-010-state-source.json', JSON.stringify(stateSource, null, 2));
}
const P = state?.prefix ?? `v2q${crypto.randomBytes(3).toString('hex')}-`; // 本次运行合成数据前缀（不匹配 V1 基线前缀）
let baseCat = null;
let otherTicket = null; // 他人（E1）的工单，供 IQA-009 基线员工越权访问
const ids = {};

// 恢复：若此前运行在 IQA-006 中途中断导致 A1 处于停用状态，用前缀管理员（派生凭据）经页面重新启用 A1
async function recoverA1(a) {
  const row = (await q('SELECT id, active FROM app_user WHERE login_name=$1', [SEC.login]))[0];
  if (!row || row.active) return false;
  const admins = await q("SELECT login_name FROM app_user WHERE role='ADMIN' AND active AND password_hash IS NOT NULL AND login_name ~ '^v2q[0-9a-f]{6}-'");
  for (const r of admins) {
    if (await login(a, { login: r.login_name, password: pwFor(r.login_name) })) {
      const res = await setActiveUI(a, SEC.login, true);
      rlog(`A1 处于停用状态，已由前缀管理员 ${r.login_name} 经页面重新启用：${res.flash}`);
      await logout(a);
      return true;
    }
  }
  return false;
}

async function setup() {
  cur = newCur('setup', '前置：初始管理员登录、合成账号与数据准备');
  try {
    const a = await actor('初始管理员A1');
    A1 = { login: SEC.login, password: SEC.password };
    let ok = await login(a, A1);
    if (!ok && await recoverA1(a)) ok = await login(a, A1);
    if (!ok) {
      const row = (await q('SELECT id, role, active, password_hash IS NOT NULL hp FROM app_user WHERE login_name=$1', [SEC.login]))[0];
      throw Object.assign(new Error(`业务方提供的初始管理员无法登录（库中：${row ? `身份=${row.role} 启用=${row.active} 有凭据=${row.hp}` : '不存在该登录名'}）；需由人更正机密或改绑库`), { gap: true });
    }
    A1.id = num((await q('SELECT id FROM app_user WHERE login_name=$1', [SEC.login]))[0].id);
    acc.A1 = A1;
    // 过期残留：2 小时前的前缀工单/类别按前缀清理；前缀账号（含历次 IQA-006 的管理员）经页面停用
    if (MODE !== 'phase2') {
      const stale = await q(`SELECT DISTINCT substring(login_name from '^(v2q[0-9a-f]{6}-)') p FROM app_user
          WHERE login_name ~ '^v2q[0-9a-f]{6}-' AND created_at < now() - interval '2 hours'
        UNION SELECT DISTINCT substring(name from '^(v2q[0-9a-f]{6}-)') FROM category WHERE name ~ '^v2q[0-9a-f]{6}-' AND created_at < now() - interval '2 hours'`);
      for (const r of stale) if (r.p && r.p !== P) { rlog(`清理过期运行残留 ${r.p}`); await cleanupTicketsByPrefix(r.p); }
      const staleUsers = await q(`SELECT login_name FROM app_user WHERE login_name ~ '^v2q[0-9a-f]{6}-' AND active
        AND (role='ADMIN' OR created_at < now() - interval '2 hours') ORDER BY id`);
      for (const r of staleUsers) { const res = await setActiveUI(a, r.login_name, false); rlog(`停用历次运行残留账号 ${r.login_name}：${res.flash}`); }
    }
    if (MODE === 'phase2') {
      for (const [k, v] of Object.entries(state.accounts)) acc[k] = { ...v, password: pwFor(v.login) };
      Object.assign(ids, state.ids);
    } else {
      const mk = (k, d, r) => createUserUI(a, k, `${P}${d}`, r).then(u => { acc[k.toUpperCase()] = u; });
      await mk('e1', '员工E1', 'EMPLOYEE'); await mk('e2', '员工E2', 'EMPLOYEE'); await mk('e3', '员工E3', 'EMPLOYEE');
      await mk('e4', '员工E4', 'EMPLOYEE'); await mk('e5', '员工E5', 'EMPLOYEE'); await mk('e6', '员工E6', 'EMPLOYEE');
      await mk('d1', '待停用D1', 'EMPLOYEE');
      await mk('t1', 'IT同事T1', 'IT_STAFF'); await mk('t2', 'IT同事T2', 'IT_STAFF');
      await mk('k1', 'IQA004-T1', 'IT_STAFF'); await mk('k2', 'IQA004-T2', 'IT_STAFF'); await mk('k3', 'IQA004-T3', 'IT_STAFF');
      baseCat = await mkCat(`${P}基础类别`);
      ids.baseCat = baseCat.id;
      otherTicket = await mkTicket({ title: `${P}E1 的工单`, categoryId: baseCat.id, urgency: 'LOW', submitterId: acc.E1.id });
      ids.otherTicket = otherTicket;
    }
    rlog(`合成数据前缀 ${P}；账号 ${Object.keys(acc).join(',')}`);
  } finally {
    await closeActors();
    writeEvd(`setup-${MODE}.log`, ['# 前置：初始管理员登录、经「账号管理」页面创建合成账号、准备类别', `prefix=${P} mode=${MODE}`, '', ...cur.log, ...runLog.slice(-40), '',
      '## 网络请求与响应（方法 URL -> 状态码；不记录请求体与凭据）', ...cur.net].join('\n'));
  }
}

// ================= IQA-009 从 V1 升级后数据保持不变并补上期望解决时间 =================
const baselinePw = (K, nonce, login) => `Q15-${crypto.createHash('sha256').update(`${K}:${nonce}:${login}`).digest('hex').slice(0, 24)}`;
async function baselineDb(snap) {
  const t = snap.ticketIds;
  return {
    tickets: (await q(`SELECT id,title,description,category_id,urgency,submitter_id,submitted_at,status,assignee_id,first_resolved_at,first_resolved_assignee_id
      FROM ticket WHERE id = ANY($1::bigint[]) ORDER BY id`, [t])).map(r => JSON.stringify(r)),
    comments: (await q('SELECT id,ticket_id,author_id,author_role,body,created_at FROM ticket_comment WHERE ticket_id = ANY($1::bigint[]) ORDER BY id', [t])).map(r => JSON.stringify(r)),
    events: (await q(`SELECT id,ticket_id,actor_id,event_type,from_assignee_id,to_assignee_id,from_status,to_status,created_at FROM ticket_event
      WHERE ticket_id = ANY($1::bigint[]) ORDER BY id`, [t])).map(r => JSON.stringify(r)),
    users: (await q('SELECT id,login_name,display_name,role FROM app_user WHERE id = ANY($1::bigint[]) ORDER BY id', [snap.users])).map(r => JSON.stringify(r)),
    category: JSON.stringify((await q('SELECT id,name,active FROM category WHERE id=$1', [snap.categoryId]))[0]),
  };
}
async function findBaseline() {
  const row = (await q("SELECT id, description FROM ticket WHERE title LIKE $1 ORDER BY id LIMIT 1", [`${BASELINE_PREFIX}%基线快照`]))[0];
  if (!row) return null;
  return { snapId: num(row.id), snap: JSON.parse(row.description) };
}
async function iqa009() {
  const found = await findBaseline().catch(e => ({ error: e.message }));
  if (!found) {
    const any = num((await q("SELECT (SELECT count(*) FROM app_user WHERE login_name LIKE $1) + (SELECT count(*) FROM ticket WHERE title LIKE $1) c", [`${BASELINE_PREFIX}%`]))[0].c);
    need('找到 V1 升级前基线快照', false, any === 0 ? `test 库中不存在前缀 ${BASELINE_PREFIX} 的基线快照与任何基线行：基线整体不存在，属执行环境问题，不以其他数据替代` : `基线快照工单缺失，但仍有 ${any} 行基线数据`, any === 0 ? 'env' : 'defect');
  }
  need('读取基线快照', !found.error && found.snap?.K, found.error ? `快照工单描述不是有效 JSON：${found.error}` : `快照 #${found.snapId}`);
  const { snap, snapId } = found;
  const K = snap.K;
  cp('找到 V1 升级前基线快照', true, `基线 ${K}，建立于 ${snap.createdAt}，建立时 V1 候选 ${snap.candidateSha}；工单 ${fmtIds(snap.ticketIds)}，账号 ${fmtIds(snap.users)}，类别 #${snap.categoryId}，快照工单 #${snapId}`);
  writeEvd('IQA-009-baseline-snapshot.json', JSON.stringify({ ...snap, nonce: '（见快照工单，不输出）' }, null, 2)); cur.files.push('IQA-009-baseline-snapshot.json');

  // 4）升级执行记录
  const hist = await q('SELECT installed_rank, version, description, success, installed_on, checksum FROM flyway_schema_history ORDER BY installed_rank');
  writeEvd('IQA-009-flyway-history.json', JSON.stringify(hist, null, 2)); cur.files.push('IQA-009-flyway-history.json');
  const v1 = hist.filter(h => h.version === '1'), v2 = hist.filter(h => h.version === '2');
  const failed = hist.filter(h => !h.success);
  const v2At = v2[0] ? new Date(v2[0].installed_on) : null;
  cp('升级执行记录：版本 2 已完成且只成功执行一次、无失败执行、晚于 V1 与基线建立', v1.length === 1 && v1[0].success && v2.length === 1 && v2[0].success && failed.length === 0
    && new Date(v1[0].installed_on) < v2At && new Date(snap.createdAt) < v2At && snap.candidateSha !== SHA,
  `history=${JSON.stringify(hist.map(h => [h.version, h.success, h.installed_on]))}；基线建立=${snap.createdAt}（V1 候选 ${snap.candidateSha}），当前候选 ${SHA}`);

  // 1）原凭据登录、身份与启用状态
  const users = await q('SELECT id, login_name, display_name, role, active FROM app_user WHERE id = ANY($1::bigint[]) ORDER BY id', [snap.users]);
  const accounts = users.map(u => ({ id: num(u.id), login: u.login_name, display: u.display_name, role: u.role, active: u.active, password: baselinePw(K, snap.nonce, u.login_name) }));
  const expUsers = [{ suffix: '-emp', display: `${K} 基线员工`, role: 'EMPLOYEE', nav: ['nav-my-tickets', 'nav-new-ticket'] }, { suffix: '-it', display: `${K} 基线IT`, role: 'IT_STAFF', nav: ['nav-all-tickets'] }];
  cp('基线账号仍存在', accounts.length === 2, `账号=${JSON.stringify(users.map(u => [num(u.id), u.login_name]))}`);
  const by = {};
  for (const x of expUsers) {
    const u = accounts.find(v => v.login === `${K}${x.suffix}`);
    by[x.suffix] = u;
    if (x.suffix === '-emp') {
      // 人工决定 HT-DLV-20261117-88E4DAE97F6A-0011 DEC-QA-V2-001 = B：该账号身份由维护者在 V2 升级前经 V1 账号管理修改，只核对登录名、姓名与启用状态，身份作为观察结果记录
      cp(`基线账号 ${K}${x.suffix} 的登录名、姓名与升级前一致且为启用（${x.display}）`, u && u.display === x.display && u.active === true,
        u ? `当前：姓名=${u.display} 启用=${u.active}；升级前：${x.display}` : '账号不存在');
      if (u) cp(`观察：基线账号 ${K}${x.suffix} 当前身份（不作判定，DEC-QA-V2-001 B）`, true, `当前身份=${ROLE[u.role] ?? u.role}；升级前快照=${ROLE[x.role]}`);
    } else {
      cp(`基线账号 ${K}${x.suffix} 的登录名、姓名、身份与升级前一致且为启用（${x.display}／${ROLE[x.role]}）`, u && u.display === x.display && u.role === x.role && u.active === true,
        u ? `当前：姓名=${u.display} 身份=${ROLE[u.role] ?? u.role} 启用=${u.active}；升级前：${x.display}／${ROLE[x.role]}` : '账号不存在');
    }
  }
  const e = await actor('基线员工'); const t = await actor('基线IT');
  const okE = by['-emp'] ? await login(e, by['-emp']) : false;
  const okT = by['-it'] ? await login(t, by['-it']) : false;
  const navE = okE ? await navSet(e.page) : [], navT = okT ? await navSet(t.page) : [];
  const NAV = { EMPLOYEE: ['nav-my-tickets', 'nav-new-ticket'], IT_STAFF: ['nav-all-tickets'], ADMIN: ['nav-admin-users', 'nav-admin-categories', 'nav-report'] };
  const empRole = by['-emp']?.role;
  cp('基线员工账号用原凭据登录，获得与其当前身份相符的能力', okE && JSON.stringify(navE) === JSON.stringify(NAV[empRole] ?? []), `登录=${okE}；当前身份=${ROLE[empRole] ?? empRole}；导航=${JSON.stringify(navE)}`);
  cp('基线 IT 同事用原凭据登录，获得 IT 同事能力', okT && JSON.stringify(navT) === JSON.stringify(expUsers[1].nav), `登录=${okT}；导航=${JSON.stringify(navT)}`);
  const admin = await actor('V1 时期初始管理员A1', acc.A1);
  const a1Row = (await q('SELECT created_at, role, active FROM app_user WHERE id=$1', [A1.id]))[0];
  cp('V1 时期设置的初始管理员用原凭据登录，身份为管理员', a1Row.role === 'ADMIN' && v2At && new Date(a1Row.created_at) < v2At
    && JSON.stringify(await navSet(admin.page)) === JSON.stringify(['nav-admin-users', 'nav-admin-categories', 'nav-report']), `创建于 ${new Date(a1Row.created_at).toISOString()}（升级于 ${v2At?.toISOString()}）`);
  // 账号列表：V1 全部账号（升级前创建）均为启用
  await go(admin.page, '/admin/users');
  const v1Users = await q('SELECT id, login_name, display_name, role, active FROM app_user WHERE created_at < $1 ORDER BY id', [v2At]);
  const pageRows = await admin.page.getByTestId('user-row').evaluateAll(els => els.map(r => ({ id: Number(r.getAttribute('data-user-id')),
    status: r.querySelector('[data-testid=user-row-status]')?.textContent.trim(), active: r.querySelector('[data-testid=user-row-status]')?.getAttribute('data-active'),
    display: r.querySelector('[data-testid=user-row-display]')?.textContent.trim(), role: r.querySelector('[data-testid=user-row-role]')?.textContent.trim() })));
  const bad = v1Users.filter(u => { const r = pageRows.find(x => x.id === num(u.id)); return !u.active || !r || r.status !== '启用' || r.active !== 'true'; });
  cp('V1 全部账号（升级前创建）在账号列表中显示为「启用」', v1Users.length > 0 && bad.length === 0, `V1 账号 ${v1Users.length} 个；非启用或未显示：${JSON.stringify(bad.map(u => num(u.id)))}`);
  for (const x of expUsers) {
    const u = by[x.suffix]; const r = u && pageRows.find(v => v.id === u.id);
    if (x.suffix === '-emp') cp(`账号列表中基线账号 ${K}${x.suffix} 的姓名与启用状态（身份显示与其当前身份一致，不与快照比较）`, r && r.display === x.display && r.role === ROLE[u.role] && r.status === '启用', `页面=${JSON.stringify(r)}；当前身份=${ROLE[u?.role]}`);
    else cp(`账号列表中基线账号 ${K}${x.suffix} 的姓名与身份`, r && r.display === x.display && r.role === ROLE[x.role] && r.status === '启用', `页面=${JSON.stringify(r)}`);
  }
  await shot(admin.page, 'accounts');

  // 2）逐项核对基线类别、工单、沟通与处理记录（页面 V1 字段 vs 快照；数据库 V1 列 vs 快照摘要）
  const db = await baselineDb(snap);
  writeEvd('IQA-009-baseline-db-now.json', JSON.stringify(db, null, 2)); cur.files.push('IQA-009-baseline-db-now.json');
  const empId = by['-emp']?.id;
  // DEC-QA-V2-001 B：仅把基线员工账号的身份按快照（员工）代入后与快照摘要比较；其余列严格比较
  const dbCmp = { ...db, users: db.users.map(j => { const r = JSON.parse(j); return num(r.id) === empId ? JSON.stringify({ ...r, role: 'EMPLOYEE' }) : j; }) };
  const dbNow = sha(dbCmp);
  cp('数据库中基线 V1 列（账号、类别、工单、沟通记录、处理记录；除基线员工账号身份外）与升级前快照摘要一致', dbNow === snap.dbDigest,
    `比较摘要（基线员工账号 #${empId} 身份按快照代入）=${dbNow}；快照摘要=${snap.dbDigest}；原始摘要=${sha(db)}；分项见 IQA-009-baseline-db-now.json`);
  cp('基线类别存在且名称、有效状态不变', JSON.parse(db.category ?? 'null')?.name === `${K}-类别` && JSON.parse(db.category).active === true, db.category ?? '缺失');
  const cmp = ['| 工单 | 视角 | 与快照一致 | 不一致字段 |', '|---|---|---|---|'];
  for (const id of snap.ticketIds) {
    const exp = snap.ui[String(id)];
    for (const [who, a, ok] of [['基线员工', e, okE], ['基线IT', t, okT]]) {
      if (!ok) { cp(`工单 #${id} 在${who}视角与快照一致`, false, `${who}无法登录，无法核对`); continue; }
      const d = await openDetail(a, id);
      const diffKeys = Object.keys(exp).filter(k => JSON.stringify(d[k]) !== JSON.stringify(exp[k]));
      cmp.push(`| #${id} | ${who} | ${diffKeys.length ? '否' : '是'} | ${diffKeys.join(',')} |`);
      cp(`工单 #${id}（${who}视角）标题、描述、类别、紧急程度、提交人、提交时间、状态、处理人、沟通记录、处理记录与快照一致`, diffKeys.length === 0,
        diffKeys.length ? `不一致字段 ${diffKeys.join(',')}：当前=${JSON.stringify(Object.fromEntries(diffKeys.map(k => [k, d[k]]))).slice(0, 500)}；快照=${JSON.stringify(Object.fromEntries(diffKeys.map(k => [k, exp[k]]))).slice(0, 500)}` : '逐字段一致');
    }
  }
  writeEvd('IQA-009-compare.md', ['# IQA-009 升级前快照与升级后页面逐项对比', '', ...cmp].join('\n')); cur.files.push('IQA-009-compare.md');
  if (okT) await shot(t.page, 'baseline-detail');
  // 基线工单提交人视角的可见性（DEC-QA-V2-001 B：按该账号当前身份核对），以及 QA 员工 E1 不能访问基线工单
  const baseAll = [...snap.ticketIds, snapId];
  if (okE && empRole === 'EMPLOYEE') {
    const mine = await collectRows(e, '/tickets');
    cp('基线提交人（当前身份：员工）「我的工单」恰为其全部工单', eqSet(mine.rows.map(r => r.id), baseAll), `列表=${fmtIds(mine.rows.map(r => r.id))}`);
    const st = await go(e.page, `/tickets/${ids.otherTicket}`);
    cp('基线提交人（员工）访问他人工单被拒绝', st === 403 && (await e.page.getByTestId('detail-title').count()) === 0, `GET /tickets/${ids.otherTicket} -> ${st}`);
    await shot(e.page, 'employee-other-ticket');
  } else if (okE && empRole === 'IT_STAFF') {
    const list = await collectRows(e, `/tickets?q=${encodeURIComponent(K)}`);
    const seen = list.rows.map(r => r.id);
    const miss = baseAll.filter(id => !seen.includes(id));
    cp('基线提交人（当前身份：IT 同事）在全部工单中能看到并打开其提交的全部基线工单', miss.length === 0, `列表=${fmtIds(seen)}；缺失=${fmtIds(miss)}`);
    await shot(e.page, 'baseline-submitter-list');
  } else {
    cp('基线提交人视角的基线工单可见性', false, `基线员工账号登录=${okE}，当前身份=${ROLE[empRole] ?? empRole}，无法按其当前身份核对可见性`);
  }
  {
    const e1 = await actor('QA 员工E1', acc.E1);
    const sts = [];
    for (const id of baseAll) sts.push([id, await go(e1.page, `/tickets/${id}`), await e1.page.getByTestId('detail-title').count()]);
    const mine = await collectRows(e1, '/tickets');
    const leak = mine.rows.map(r => r.id).filter(id => baseAll.includes(id));
    cp('QA 创建的员工 E1 不能访问基线工单（详情 403，「我的工单」中不出现）', sts.every(([, st, n]) => st === 403 && n === 0) && leak.length === 0,
      `${sts.map(([id, st]) => `GET /tickets/${id} -> ${st}`).join('；')}；我的工单中的基线工单=${fmtIds(leak)}`);
    await shot(e1.page, 'e1-baseline-denied');
  }

  // 3）期望解决时间与超时标识（全部基线工单，含快照工单）
  const allIds = [...snap.ticketIds, snapId];
  const rowsDb = await q('SELECT id, urgency, submitted_at, status FROM ticket WHERE id = ANY($1::bigint[]) ORDER BY id', [allIds]);
  const table = ['| 工单 | 紧急程度 | 提交时间（UTC） | 期望解决时间（独立计算） | 状态 | 列表 | 详情 |', '|---|---|---|---|---|---|---|'];
  const viewer = okT ? t : admin;
  if (okT) {
    const list = await collectRows(t, `/tickets?q=${encodeURIComponent(K)}`);
    for (const r of rowsDb) {
      const exp = expectedOf(r.submitted_at, r.urgency);
      const lr = list.rows.find(x => x.id === num(r.id));
      const d = await detailFull(viewer, num(r.id));
      const okL = lr && expectedOk(lr, exp) && overdueOk(lr, overdueExpect(r.status, exp, list.t1, list.t2));
      const okD = expectedOk(d, exp) && overdueOk(d, overdueExpect(r.status, exp, d.t1, d.t2));
      table.push(`| #${num(r.id)} | ${URG[r.urgency]} | ${new Date(r.submitted_at).toISOString()} | ${exp.toISOString()}（${shMinute(exp)}） | ${STATUS[r.status]} | ${lr ? `${lr.expectedText}/超时=${lr.overdueTag}` : '缺失'} | ${d.expectedText}/超时=${d.overdueTag} |`);
      cp(`基线工单 #${num(r.id)} 期望解决时间 = 提交时间 + ${HOURS[r.urgency]} 小时，超时标识与状态和当前时刻相符`, okL && okD,
        `期望=${shMinute(exp)}（${exp.toISOString()}）；列表=${JSON.stringify(lr && { e: lr.expectedAt, t: lr.expectedText, o: lr.overdueTag })}；详情=${JSON.stringify({ e: d.expectedAt, t: d.expectedText, o: d.overdueTag })}；状态=${STATUS[r.status]}`);
    }
    await go(t.page, `/tickets?q=${encodeURIComponent(K)}`); await shot(t.page, 'baseline-expected');
  } else cp('基线工单期望解决时间与超时标识', false, '基线 IT 同事无法登录，无法在列表与详情核对');
  writeEvd('IQA-009-expected.md', ['# IQA-009 期望解决时间核对表（BQ-001 人工答案 A）', '', ...table].join('\n')); cur.files.push('IQA-009-expected.md');

  // 5）周报表
  const rc = await reportCheck(admin);
  await shot(admin.page, 'report');
  writeEvd('IQA-009-report.json', JSON.stringify({ page: rc.rep, independent: rc.sql }, null, 2)); cur.files.push('IQA-009-report.json');
  cp('周报表 V1 四项与按 V1 口径独立计算一致，第五项符合 FR-017', rc.ok, rc.ok ? `页面与独立计算一致：${JSON.stringify(rc.sql)}` : rc.diffs.join('；'));

  // 6）设置初始管理员流程不可用
  const anon = await actor('未登录访问者');
  await go(anon.page, '/login');
  const link = await anon.page.getByTestId('setup-link').count();
  const st = await go(anon.page, '/setup');
  const closed = await anon.page.getByTestId('setup-closed').count();
  const form = await anon.page.getByTestId('setup-submit').count();
  await shot(anon.page, 'setup-closed');
  cp('「设置初始管理员」流程不可用', link === 0 && st === 403 && closed === 1 && form === 0, `登录页入口=${link}；GET /setup -> ${st}；关闭页=${closed}；表单=${form}`);
  ids.baselineSnapId = snapId;
}

// ================= IQA-001 新工单按紧急程度获得期望解决时间 =================
async function noExpectedEntry(page) {
  return page.locator('input, select, textarea').evaluateAll(els => els.map(e => `${e.getAttribute('name') ?? ''}|${e.getAttribute('data-testid') ?? ''}`)
    .filter(s => /expect|due|deadline|sla|resolve_?at|期望/i.test(s)));
}
async function iqa001() {
  const e1 = await actor('员工E1', acc.E1);
  const t1 = await actor('IT同事T1', acc.T1);
  const kw = `${P}kw001`;
  await go(e1.page, '/tickets/new');
  const entryNew = await noExpectedEntry(e1.page);
  const tk = {};
  for (const u of ['HIGH', 'MEDIUM', 'LOW']) tk[u] = await submitTicketUI(e1, { title: `${kw} ${URG[u]}紧急`, categoryId: ids.baseCat, urgency: u });
  const sub = {};
  for (const u of Object.keys(tk)) sub[u] = (await ticketRow(tk[u])).submitted_at;
  cp('员工 E1 以高、中、低各提交一张工单', Object.values(tk).every(Boolean), `工单 高=#${tk.HIGH} 中=#${tk.MEDIUM} 低=#${tk.LOW}；提交时间=${JSON.stringify(Object.fromEntries(Object.entries(sub).map(([k, v]) => [k, new Date(v).toISOString()])))}`);
  const exp = Object.fromEntries(Object.keys(tk).map(u => [u, expectedOf(sub[u], u)]));
  const lines = ['| 工单 | 紧急程度 | 提交时间 | 期望解决时间（= 提交 + 4/24/72 小时） |', '|---|---|---|---|',
    ...Object.keys(tk).map(u => `| #${tk[u]} | ${URG[u]} | ${shMinute(sub[u])} | ${shMinute(exp[u])} |`)];
  const checkView = (label, view, u) => cp(`${label}：#${tk[u]}（${URG[u]}）显示期望解决时间 ${shMinute(exp[u])}`, expectedOk(view, exp[u]),
    `显示=${view?.expectedText ?? '缺失'}；属性=${view?.expectedAt ?? '缺失'}；期望=${exp[u].toISOString()}`);
  // E1：我的工单、详情；T1：全部工单、详情
  for (const [who, a, url] of [['E1 我的工单', e1, `/tickets?q=${encodeURIComponent(kw)}`], ['T1 全部工单', t1, `/tickets?q=${encodeURIComponent(kw)}`]]) {
    const all = await collectRows(a, '/tickets');
    for (const u of Object.keys(tk)) checkView(`${who}列表（未加条件）`, all.rows.find(r => r.id === tk[u]), u);
    await go(a.page, url); await shot(a.page, `${who}-list`);
    for (const u of Object.keys(tk)) {
      const d = await detailFull(a, tk[u]);
      checkView(`${who.slice(0, 2)} 详情`, d, u);
      cp(`${who.slice(0, 2)} 详情 #${tk[u]} 提交时间显示`, d.submitted === shMinute(sub[u]), `显示=${d.submitted}；期望=${shMinute(sub[u])}`);
    }
    await shot(a.page, `${who}-detail`);
    const s1 = await searchForm(a, { q: kw });
    for (const u of Object.keys(tk)) checkView(`${who.slice(0, 2)} 关键词搜索结果`, s1.rows.find(r => r.id === tk[u]), u);
    const s2 = await searchForm(a, { q: kw, status: 'OPEN' });
    for (const u of Object.keys(tk)) checkView(`${who.slice(0, 2)} 按状态「待处理」筛选结果`, s2.rows.find(r => r.id === tk[u]), u);
    await shot(a.page, `${who}-search`);
  }
  // 步骤 3：对高紧急工单依次操作，每步后期望解决时间不变
  const h = tk.HIGH;
  const steps = [['T1 认领', () => claimUI(t1, h)], ['T1 分派给 T2', () => assignUI(t1, h, acc.T2.id)], ['T1 改为处理中', () => statusUI(t1, h, 'IN_PROGRESS')],
    ['T1 改为已解决', () => statusUI(t1, h, 'RESOLVED')], ['T1 改为已关闭', () => statusUI(t1, h, 'CLOSED')], ['T1 回复', () => commentUI(t1, h, `${P}IQA001 回复`)],
    ['E1 补充说明', () => commentUI(e1, h, `${P}IQA001 补充说明`)]];
  for (const [label, fn] of steps) {
    const fl = await fn();
    const who = label.startsWith('E1') ? e1 : t1;
    const v = await readV2(who.page);
    cp(`${label}后期望解决时间不变`, expectedOk(v, exp.HIGH) && pathOf(who.page) === `/tickets/${h}`, `提示=${fl}；显示=${v.expectedText}；属性=${v.expectedAt}`);
  }
  const r = await ticketRow(h);
  cp('步骤 3 的操作均已生效（处理人 T2、状态已关闭）', r.status === 'CLOSED' && num(r.assignee_id) === acc.T2.id, `库中状态=${r.status} 处理人=${r.assignee_id}`);
  for (const [who, a] of [['E1', e1], ['T1', t1]]) {
    const d = await detailFull(a, h);
    cp(`${who} 详情：已关闭状态下仍显示期望解决时间且不变`, d.status === STATUS.CLOSED && expectedOk(d, exp.HIGH), `状态=${d.status}；显示=${d.expectedText}`);
    const l = await collectRows(a, `/tickets?q=${encodeURIComponent(kw)}`);
    const lr = l.rows.find(x => x.id === h);
    cp(`${who} 列表项：已关闭状态下仍显示期望解决时间且不变`, lr && lr.status === STATUS.CLOSED && expectedOk(lr, exp.HIGH), `列表项=${JSON.stringify(lr)}`);
    await shot(a.page, `${who}-after-ops`);
    await go(a.page, `/tickets/${h}`);
    const entryDetail = await noExpectedEntry(a.page);
    cp(`${who} 详情页不存在设置或修改期望解决时间的入口`, entryDetail.length === 0, `相关控件=${JSON.stringify(entryDetail)}`);
  }
  cp('提交工单页不存在设置期望解决时间的入口', entryNew.length === 0, `相关控件=${JSON.stringify(entryNew)}`);
  writeEvd('IQA-001-expected.md', ['# IQA-001 提交时间与期望解决时间', '', ...lines].join('\n')); cur.files.push('IQA-001-expected.md');
}

// ================= IQA-002 超时未解决工单醒目标出 =================
async function iqa002() {
  const now = Date.now();
  const T = acc.T1.id;
  const kw = `${P}kw002`;
  const mk = (n, urgency, ago, status, extra = {}) => mkTicket({ title: `${kw} ${n}`, categoryId: ids.baseCat, urgency, submitterId: acc.E1.id, submittedAt: new Date(now - ago), status, ...extra });
  const tk = {
    A: await mk('A', 'HIGH', 5 * HOUR, 'OPEN'),
    B: await mk('B', 'MEDIUM', 25 * HOUR, 'IN_PROGRESS', { assigneeId: T, events: [{ actorId: T, type: 'CLAIM', toAssignee: T, at: new Date(now - 24 * HOUR) }, { actorId: T, type: 'STATUS', fromStatus: 'OPEN', toStatus: 'IN_PROGRESS', at: new Date(now - 24 * HOUR) }] }),
    C: await mk('C', 'LOW', 10 * HOUR, 'OPEN'),
    D: await mk('D', 'HIGH', 6 * HOUR, 'RESOLVED', { assigneeId: T, firstResolvedAt: new Date(now - 3 * HOUR), firstResolvedAssigneeId: T, events: [{ actorId: T, type: 'STATUS', fromStatus: 'OPEN', toStatus: 'RESOLVED', at: new Date(now - 3 * HOUR) }] }),
    E: await mk('E', 'MEDIUM', 30 * HOUR, 'CLOSED', { assigneeId: T, events: [{ actorId: T, type: 'STATUS', fromStatus: 'OPEN', toStatus: 'CLOSED', at: new Date(now - 20 * HOUR) }] }),
  };
  ids.iqa002 = tk;
  cp('准备回溯合成工单 A～E（员工 E1 名下）', true, `A=#${tk.A}(高,5h前,待处理) B=#${tk.B}(中,25h前,处理中) C=#${tk.C}(低,10h前,待处理) D=#${tk.D}(高,6h前,已解决) E=#${tk.E}(中,30h前,已关闭)`);
  const e1 = await actor('员工E1', acc.E1);
  const t1 = await actor('IT同事T1', acc.T1);
  const check = async (round, expectFlags) => {
    const dbRows = Object.fromEntries(await Promise.all(Object.entries(tk).map(async ([k, id]) => [k, await ticketRow(id)])));
    for (const [who, a] of [['E1', e1], ['T1', t1]]) {
      const l = await collectRows(a, `/tickets?q=${encodeURIComponent(kw)}`);
      await shot(a.page, `${round}-${who}-list`);
      for (const [k, id] of Object.entries(tk)) {
        const r = dbRows[k]; const exp = expectedOf(r.submitted_at, r.urgency);
        const lr = l.rows.find(x => x.id === id);
        const flag = expectFlags[k];
        cp(`${round} ${who} 列表：${k}（#${id}，${STATUS[r.status]}）${flag ? '显示' : '不显示'}超时标识`, lr && overdueOk(lr, flag) && overdueExpect(r.status, exp, l.t1, l.t2) === flag,
          `标识=${lr?.overdueTag} 文本=${lr?.overdueText} 属性=${lr?.overdueAttr} 样式=${JSON.stringify(lr?.style)}；期望解决=${shMinute(exp)}；查看时刻=${shMinute(l.t1)}`);
        const d = await detailFull(a, id);
        cp(`${round} ${who} 详情：${k}（#${id}）${flag ? '显示' : '不显示'}超时标识`, overdueOk(d, flag), `标识=${d.overdueTag} 文本=${d.overdueText} 属性=${d.overdueAttr} 样式=${JSON.stringify(d.style)}`);
        if (k === 'A' || k === 'D') await shot(a.page, `${round}-${who}-detail-${k}`);
      }
    }
  };
  await check('步骤1', { A: true, B: true, C: false, D: false, E: false });
  const f1 = await statusUI(t1, tk.A, 'RESOLVED');
  const f2 = await statusUI(t1, tk.D, 'OPEN');
  const ra = await ticketRow(tk.A), rd = await ticketRow(tk.D);
  cp('步骤2 T1 把 A 改为已解决、把 D 改回待处理', ra.status === 'RESOLVED' && rd.status === 'OPEN', `提示=${f1}／${f2}；库中 A=${ra.status} D=${rd.status}`);
  await check('步骤3', { A: false, B: true, C: false, D: true, E: false });
}

// ================= IQA-003 管理员停用与重新启用账号 =================
async function iqa003() {
  const admin = await actor('管理员A1', acc.A1);
  const E2 = acc.E2;
  const s1 = await userStatus(admin.page, E2.login);
  await shot(admin.page, 'step1-list');
  cp('步骤1 账号列表显示 E2 为「启用」', s1?.text === '启用' && s1.active === 'true', JSON.stringify(s1));
  const e2 = await actor('员工E2（保持登录）', E2);
  const r3 = await setActiveUI(admin, E2.login, false);
  const s3 = await userStatus(admin.page, E2.login);
  await shot(admin.page, 'step3-deactivated');
  cp('步骤3 停用 E2 后账号列表显示「停用」，姓名与身份不变', s3?.text === '停用' && s3.active === 'false' && s3.display === E2.display && s3.role === ROLE.EMPLOYEE && (await dbActive(E2.id)) === false,
    `提示=${r3.flash}；列表=${JSON.stringify(s3)}`);
  const st4 = await go(e2.page, '/tickets');
  await shot(e2.page, 'step4-next-op');
  cp('步骤4 E2 已登录会话的下一次操作被拒绝并被引导至登录入口', pathOf(e2.page) === '/login' && (await e2.page.getByTestId('login-submit').count()) === 1 && (await e2.page.getByTestId('logout').count()) === 0,
    `GET /tickets -> ${st4}，最终 URL=${e2.page.url()}`);
  const right = await tryLogin(e2, E2);
  await shot(e2.page, 'step5-correct-refused');
  const wrong = await tryLogin(e2, { login: E2.login, password: `${E2.password}x` });
  cp('步骤5 正确凭据登录被拒、未进入系统，提示与错误凭据一致', !right.loggedIn && !wrong.loggedIn && right.error && right.error === wrong.error && right.url === wrong.url,
    `正确凭据：${JSON.stringify(right)}；错误凭据：${JSON.stringify(wrong)}`);
  await go(admin.page, '/admin/users');
  await admin.page.getByTestId('user-login').fill(E2.login);
  await admin.page.getByTestId('user-password').fill(pwFor(`${E2.login}-dup`));
  await admin.page.getByTestId('user-display').fill(`${P}重复登录名`);
  await admin.page.getByTestId('user-role').selectOption('EMPLOYEE');
  await submit(admin.page, admin.page.getByTestId('user-create'));
  const dupErr = await txt(admin.page, 'field-error-loginName');
  const cnt = num((await q('SELECT count(*) c FROM app_user WHERE login_name=$1', [E2.login]))[0].c);
  await shot(admin.page, 'step6-duplicate');
  cp('步骤6 用 E2 的登录名创建新账号因登录名重复被拒', !!dupErr && cnt === 1, `提示=${dupErr}；该登录名账号数=${cnt}`);
  const r7 = await setActiveUI(admin, E2.login, true);
  const s7 = await userStatus(admin.page, E2.login);
  cp('步骤7 重新启用后账号列表显示「启用」', s7?.text === '启用' && s7.active === 'true' && (await dbActive(E2.id)) === true, `提示=${r7.flash}；列表=${JSON.stringify(s7)}`);
  const ok = await login(e2, E2);
  const nav = ok ? await navSet(e2.page) : [];
  cp('步骤7 E2 用原凭据登录成功并获得员工能力', ok && JSON.stringify(nav) === JSON.stringify(['nav-my-tickets', 'nav-new-ticket']), `登录=${ok}；导航=${JSON.stringify(nav)}`);
  if (ok) {
    const id = await submitTicketUI(e2, { title: `${P}IQA003 重新启用后提交`, categoryId: ids.baseCat, urgency: 'LOW' });
    const mine = await collectRows(e2, '/tickets');
    await shot(e2.page, 'step7-my-tickets');
    cp('步骤7 E2 正常提交并在「我的工单」中看到该工单', mine.rows.some(r => r.id === id), `新工单 #${id}；我的工单=${fmtIds(mine.rows.map(r => r.id))}`);
  }
}

// ================= IQA-004 停用 IT 同事不再可分派且历史照常显示 =================
async function activeItNames(excludeId) {
  return (await q("SELECT id, display_name FROM app_user WHERE role='IT_STAFF' AND active AND id <> $1 ORDER BY id", [excludeId])).map(r => num(r.id));
}
async function iqa004() {
  const [K1, K2, K3, E1] = [acc.K1, acc.K2, acc.K3, acc.E1];
  const kw = `${P}kw004`;
  const e1 = await actor('员工E1', E1);
  const w1 = await submitTicketUI(e1, { title: `${kw} W1`, categoryId: ids.baseCat, urgency: 'MEDIUM' });
  const w2 = await submitTicketUI(e1, { title: `${kw} W2`, categoryId: ids.baseCat, urgency: 'LOW' });
  const k1 = await actor('IT同事T1(K1)', K1);
  await claimUI(k1, w1);
  const reply = `${P}IQA004 T1 的回复`;
  await commentUI(k1, w1, reply);
  await statusUI(k1, w1, 'IN_PROGRESS');
  await claimUI(k1, w2);
  await statusUI(k1, w2, 'RESOLVED');
  const rw1 = await ticketRow(w1), rw2 = await ticketRow(w2);
  need('前置：T1 认领 W1 并回复、改为处理中；认领 W2 并改为已解决', num(rw1.assignee_id) === K1.id && rw1.status === 'IN_PROGRESS' && num(rw2.assignee_id) === K1.id && rw2.status === 'RESOLVED' && rw2.first_resolved_at,
    `W1=#${w1}(${rw1.status},处理人 ${rw1.assignee_id}) W2=#${w2}(${rw2.status},处理人 ${rw2.assignee_id},首次已解决 ${rw2.first_resolved_at?.toISOString?.()})`);
  const evBefore = num((await q('SELECT count(*) c FROM ticket_event WHERE ticket_id=$1', [w1]))[0].c);
  // 1）T2 打开 W1 分派对象列表（不刷新）
  const k2 = await actor('IT同事T2(K2)', K2);
  await go(k2.page, `/tickets/${w1}`);
  const opt1 = await assignOptions(k2.page);
  cp('步骤1 T2 的分派对象含 T1', opt1.some(o => o.id === K1.id), `分派对象=${JSON.stringify(opt1.map(o => o.id))}`);
  // 2）A1 停用 T1
  const admin = await actor('管理员A1', acc.A1);
  const r2 = await setActiveUI(admin, K1.login, false);
  cp('步骤2 A1 停用 T1', (await dbActive(K1.id)) === false, `提示=${r2.flash}`);
  // 3）T2 在原页面提交分派给 T1
  await k2.page.getByTestId('assign-select').selectOption(String(K1.id));
  await submit(k2.page, k2.page.getByTestId('assign-submit'));
  const fl3 = await flash(k2.page);
  const rw1b = await ticketRow(w1);
  const evAfter = num((await q('SELECT count(*) c FROM ticket_event WHERE ticket_id=$1', [w1]))[0].c);
  await shot(k2.page, 'step3-assign-refused');
  cp('步骤3 分派给已停用的 T1 被拒绝，W1 不变', num(rw1b.assignee_id) === K1.id && rw1b.status === 'IN_PROGRESS' && evAfter === evBefore && !!fl3,
    `提示=${fl3}；W1 处理人=${rw1b.assignee_id} 状态=${rw1b.status}；处理记录数 ${evBefore}->${evAfter}`);
  // 4）刷新后分派对象
  await go(k2.page, `/tickets/${w1}`);
  const opt4 = await assignOptions(k2.page);
  const expOpt = await activeItNames(K2.id);
  await shot(k2.page, 'step4-options');
  cp('步骤4 刷新后分派对象不含 T1，且恰为启用状态的其他 IT 同事', !opt4.some(o => o.id === K1.id) && eqSet(opt4.map(o => o.id), expOpt), `页面=${fmtIds(opt4.map(o => o.id))}；启用的其他 IT 同事=${fmtIds(expOpt)}`);
  // 5）E1、T2 查看历史；T2 按处理人 T1 筛选
  for (const [who, a] of [['E1', e1], ['T2', k2]]) {
    const l = await collectRows(a, `/tickets?q=${encodeURIComponent(kw)}`);
    const l1 = l.rows.find(r => r.id === w1), l2 = l.rows.find(r => r.id === w2);
    cp(`步骤5 ${who} 列表中 W1、W2 仍显示 T1 为处理人`, l1?.assignee === K1.display && l2?.assignee === K1.display && l1?.status === STATUS.IN_PROGRESS, `W1=${JSON.stringify(l1 && [l1.assignee, l1.status])} W2=${JSON.stringify(l2 && [l2.assignee, l2.status])}`);
    const d1 = await openDetail(a, w1);
    const c = d1.comments?.find(x => x.text === reply);
    const evs = d1.events ?? [];
    cp(`步骤5 ${who} W1 详情：处理人 T1、状态处理中、T1 回复（作者姓名与身份）与处理记录照常显示`, d1.assignee === K1.display && d1.status === STATUS.IN_PROGRESS && c?.author === K1.display && c?.role === ROLE.IT_STAFF
      && evs.some(x => x.type === 'CLAIM' && x.actor === K1.display) && evs.some(x => x.type === 'STATUS' && x.actor === K1.display && x.to === STATUS.IN_PROGRESS),
    `处理人=${d1.assignee} 状态=${d1.status} 回复=${JSON.stringify(c)} 处理记录=${JSON.stringify(evs)}`);
    await shot(a.page, `step5-${who}-w1`);
    const d2 = await openDetail(a, w2);
    cp(`步骤5 ${who} W2 详情：处理人 T1、状态已解决`, d2.assignee === K1.display && d2.status === STATUS.RESOLVED, `处理人=${d2.assignee} 状态=${d2.status}`);
  }
  await go(k2.page, '/tickets');
  const filterOpts = await k2.page.getByTestId('filter-assignee').locator('option').evaluateAll(os => os.map(o => o.value));
  const f = await collectRows(k2, `/tickets?assignee=${K1.id}`);
  const expF = (await q('SELECT id FROM ticket WHERE assignee_id=$1', [K1.id])).map(r => num(r.id));
  await shot(k2.page, 'step5-filter');
  cp('步骤5 T2 按处理人 T1 筛选能筛出 W1、W2（结果等于全库按该处理人计算的集合）', filterOpts.includes(String(K1.id)) && f.rows.some(r => r.id === w1) && f.rows.some(r => r.id === w2) && eqSet(f.rows.map(r => r.id), expF),
    `筛选项含 T1=${filterOpts.includes(String(K1.id))}；结果=${fmtIds(f.rows.map(r => r.id))}；期望=${fmtIds(expF)}`);
  // 6）周报表处理量
  const rep = await readReport(admin);
  const { ws, we } = weekRange();
  const expCnt = num((await q('SELECT count(*) c FROM ticket WHERE first_resolved_assignee_id=$1 AND first_resolved_at >= $2 AND first_resolved_at < $3', [K1.id, ws, we]))[0].c);
  const row = rep.staff?.find(s => s.id === K1.id);
  await shot(admin.page, 'step6-report');
  cp('步骤6 周报表中已停用 T1 的处理量照常计入 W2', row && row.count === expCnt && expCnt >= 1, `页面 T1 行=${JSON.stringify(row)}；独立计算=${expCnt}`);
  // 7）T3 认领 W1
  const k3 = await actor('IT同事T3(K3)', K3);
  await claimUI(k3, w1);
  const d7 = await openDetail(k3, w1);
  const last = (d7.events ?? []).at(-1);
  await shot(k3.page, 'step7-claim');
  cp('步骤7 T3 认领 W1：处理人变为 T3，处理记录前值 T1、后值 T3', d7.assignee === K3.display && last?.type === 'CLAIM' && last.from === K1.display && last.to === K3.display && last.actor === K3.display,
    `处理人=${d7.assignee}；最新处理记录=${JSON.stringify(last)}`);
  // 8）重新启用 T1
  const r8 = await setActiveUI(admin, K1.login, true);
  await go(k2.page, `/tickets/${w2}`);
  const opt8 = await assignOptions(k2.page);
  await shot(k2.page, 'step8-options');
  cp('步骤8 重新启用 T1 后分派对象中重新出现 T1', (await dbActive(K1.id)) === true && opt8.some(o => o.id === K1.id), `提示=${r8.flash}；分派对象=${fmtIds(opt8.map(o => o.id))}`);
}

// ================= IQA-005 停用员工的历史工单与沟通记录照常显示 =================
async function iqa005() {
  const E3 = acc.E3, T1 = acc.T1;
  const title = `${P}kw005 W3 打印机无法连接`;
  const e3 = await actor('员工E3', E3);
  const w3 = await submitTicketUI(e3, { title, categoryId: ids.baseCat, urgency: 'MEDIUM' });
  const note = `${P}IQA005 E3 的补充说明`;
  await commentUI(e3, w3, note);
  const t1 = await actor('IT同事T1', T1);
  const reply1 = `${P}IQA005 T1 的回复一`;
  await commentUI(t1, w3, reply1);
  await claimUI(t1, w3);
  const admin = await actor('管理员A1', acc.A1);
  const r1 = await setActiveUI(admin, E3.login, false);
  cp('步骤1 A1 停用 E3', (await dbActive(E3.id)) === false, `提示=${r1.flash}`);
  const all = await collectRows(t1, '/tickets');
  const lr = all.rows.find(r => r.id === w3);
  cp('步骤2 W3 照常出现在 T1 的全部工单列表，提交人显示 E3 姓名', lr && lr.submitter === E3.display, `列表项=${JSON.stringify(lr && [lr.id, lr.submitter])}`);
  const s = await searchForm(t1, { q: 'kw005 W3 打印机' });
  const sr = s.rows.find(r => r.id === w3);
  await shot(t1.page, 'step2-search');
  cp('步骤2 用标题中的部分文字搜索能找到 W3', !!sr && sr.submitter === E3.display, `搜索结果=${fmtIds(s.rows.map(r => r.id))}`);
  const d = await openDetail(t1, w3);
  const cN = d.comments?.find(c => c.text === note), cR = d.comments?.find(c => c.text === reply1);
  await shot(t1.page, 'step2-detail');
  cp('步骤2 W3 详情：提交人 E3、E3 的补充说明（作者姓名与内容）与 T1 回复照常显示', d.submitter === E3.display && cN?.author === E3.display && cN?.role === ROLE.EMPLOYEE && cR?.author === T1.display,
    `提交人=${d.submitter}；补充说明=${JSON.stringify(cN)}；回复=${JSON.stringify(cR)}`);
  const reply2 = `${P}IQA005 T1 停用期间的回复`;
  const evBefore = (d.events ?? []).length;
  await commentUI(t1, w3, reply2);
  const fl = await statusUI(t1, w3, 'IN_PROGRESS');
  const d2 = await openDetail(t1, w3);
  cp('步骤2 T1 在 W3 下回复并修改状态成功，产生处理记录', d2.comments?.some(c => c.text === reply2) && d2.status === STATUS.IN_PROGRESS && (d2.events ?? []).length === evBefore + 1
    && d2.events.at(-1).type === 'STATUS' && d2.events.at(-1).to === STATUS.IN_PROGRESS, `提示=${fl}；状态=${d2.status}；处理记录数 ${evBefore}->${(d2.events ?? []).length}`);
  const r3 = await setActiveUI(admin, E3.login, true);
  cp('步骤3 A1 重新启用 E3', (await dbActive(E3.id)) === true, `提示=${r3.flash}`);
  const e3b = await actor('员工E3（重新登录）');
  const ok = await login(e3b, E3);
  need('步骤3 E3 重新登录', ok, `登录=${ok}`);
  const mine = await collectRows(e3b, '/tickets');
  cp('步骤3 E3「我的工单」中有 W3', mine.rows.some(r => r.id === w3), `我的工单=${fmtIds(mine.rows.map(r => r.id))}`);
  const de = await openDetail(e3b, w3);
  const sameComments = JSON.stringify(de.comments) === JSON.stringify(d2.comments) && JSON.stringify(de.events) === JSON.stringify(d2.events);
  await shot(e3b.page, 'step3-detail');
  cp('步骤3 E3 看到 W3 的全部沟通与处理记录（含停用期间 T1 的回复和状态变化）', sameComments && de.comments.some(c => c.text === reply2) && de.status === STATUS.IN_PROGRESS,
    `E3 视角沟通 ${de.comments?.length} 条、处理记录 ${de.events?.length} 条，与 T1 视角一致=${sameComments}`);
}

// ================= IQA-006 系统始终至少保留一个可用管理员 =================
const usableAdmins = async () => (await q("SELECT id, login_name FROM app_user WHERE role='ADMIN' AND active AND password_hash IS NOT NULL ORDER BY id")).map(r => ({ id: num(r.id), login: r.login_name }));
let a2Acc = null;
async function iqa006() {
  const a1 = await actor('管理员A1', acc.A1);
  // 前置：只经页面停用历次 QA 运行的前缀管理员；存在非 QA 创建的其他可用管理员时不处置他人账号
  for (const u of (await usableAdmins()).filter(x => x.id !== A1.id && /^v2q[0-9a-f]{6}-/.test(x.login))) await setActiveUI(a1, u.login, false);
  const others = (await usableAdmins()).filter(x => x.id !== A1.id);
  const nullHash = num((await q("SELECT count(*) c FROM app_user WHERE role='ADMIN' AND active AND password_hash IS NULL"))[0].c);
  info('前置观察', `另有 ${nullHash} 个无登录凭据（无法登录）的管理员行，按 design TD-025 不属于可用管理员`);
  need('前置：测试环境中仅有一个可用管理员 A1', others.length === 0, `其他可用管理员 ${others.length} 个（非 QA 创建，QA 不处置他人账号）：执行环境不满足前置条件`, 'env');
  // 1）A1 尝试停用自己
  const r1 = await setActiveUI(a1, A1.login, false);
  const s1 = await userStatus(a1.page, A1.login, false);
  await shot(a1.page, 'step1-refused');
  cp('步骤1 A1 停用自己被拒绝，提示必须至少保留一个可用管理员，A1 仍为启用', (r1.flash ?? '').includes(MSG_LAST_ADMIN) && s1?.text === '启用' && (await dbActive(A1.id)) === true,
    `提示=${r1.flash}；列表=${JSON.stringify(s1)}`);
  // 2）A1 创建 A2
  a2Acc = await createUserUI(a1, 'a2', `${P}管理员A2`, 'ADMIN');
  acc.A2 = a2Acc;
  cp('步骤2 A1 创建合成管理员 A2', !!a2Acc.id, `A2=#${a2Acc.id}`);
  // 3）A1 再次停用自己
  const r3 = await setActiveUI(a1, A1.login, false);
  const s3 = await userStatus(a1.page, A1.login, false);
  await shot(a1.page, 'step3-self-deactivated');
  cp('步骤3 A1 停用自己成功，账号列表显示 A1 为停用', (await dbActive(A1.id)) === false && s3?.text === '停用' && s3.active === 'false', `提示=${r3.flash}；POST 状态=${r3.postStatus}；列表=${JSON.stringify(s3)}`);
  try {
    // 4）A1 下一次操作
    await go(a1.page, '/admin/users');
    cp('步骤4 A1 的下一次操作被拒绝并被引导至登录入口', pathOf(a1.page) === '/login' && (await a1.page.getByTestId('logout').count()) === 0, `最终 URL=${a1.page.url()}`);
    await shot(a1.page, 'step4-redirected');
    const rl = await tryLogin(a1, A1);
    cp('步骤4 A1 无法再登录', !rl.loggedIn && !!rl.error, `结果=${JSON.stringify(rl)}`);
    // 5）A2 登录并尝试停用自己
    const a2 = await actor('管理员A2', a2Acc);
    const r5 = await setActiveUI(a2, a2Acc.login, false);
    const s5 = await userStatus(a2.page, a2Acc.login, false);
    await shot(a2.page, 'step5-refused');
    cp('步骤5 A2 停用自己被拒绝并提示原因，A2 仍为启用', (r5.flash ?? '').includes(MSG_LAST_ADMIN) && s5?.text === '启用' && (await dbActive(a2Acc.id)) === true, `提示=${r5.flash}；列表=${JSON.stringify(s5)}`);
    // 6）A2 重新启用 A1
    const r6 = await setActiveUI(a2, A1.login, true);
    cp('步骤6 A2 重新启用 A1 成功', (await dbActive(A1.id)) === true, `提示=${r6.flash}`);
    const ok = await login(a1, A1);
    await shot(a1.page, 'step6-a1-login');
    cp('步骤6 A1 可再次登录', ok, `登录=${ok}`);
  } finally {
    if ((await dbActive(A1.id)) === false) {
      // 安全恢复：无论上面哪一步失败，都由 A2 经页面重新启用 A1，保证测试环境的初始管理员可用
      const rec = await actor('恢复用A2');
      const okA2 = await login(rec, a2Acc);
      const rr = okA2 ? await setActiveUI(rec, A1.login, true) : null;
      info('恢复 A1', `A2 登录=${okA2}；重新启用结果=${rr?.flash}；A1 启用=${await dbActive(A1.id)}`);
    }
  }
}

// ================= IQA-007 停用与启用的访问控制 =================
async function iqa007() {
  const admin = await actor('管理员A1', acc.A1);
  const E4 = acc.E4, E5 = acc.E5;
  if ((await dbActive(E5.id)) !== false) await setActiveUI(admin, E5.login, false);
  need('前置：E4 启用、E5 停用', (await dbActive(E4.id)) === true && (await dbActive(E5.id)) === false, `E4=${await dbActive(E4.id)} E5=${await dbActive(E5.id)}`);
  for (const [who, account] of [['E1', acc.E1], ['T1', acc.T1]]) {
    const a = await actor(who, account);
    const nav = await navSet(a.page);
    const st = await go(a.page, '/admin/users');
    const forbidden = await a.page.getByTestId('forbidden').count();
    const entries = (await a.page.getByTestId('user-deactivate').count()) + (await a.page.getByTestId('user-activate').count());
    await shot(a.page, `${who}-admin-users`);
    cp(`${who} 看不到停用 / 启用入口`, !nav.includes('nav-admin-users') && st === 403 && forbidden === 1 && entries === 0, `导航=${JSON.stringify(nav)}；GET /admin/users -> ${st}；入口数=${entries}`);
    await go(a.page, '/tickets');
    const p1 = await postForm(a, `/admin/users/${E4.id}/deactivate`, {});
    const p2 = await postForm(a, `/admin/users/${E5.id}/activate`, {});
    cp(`${who} 直接发起停用 E4、启用 E5 均被拒绝`, p1.status === 403 && p2.status === 403, `POST deactivate E4 -> ${p1.status}；POST activate E5 -> ${p2.status}`);
    writeEvd(`IQA-007-${who}-denied.html`, p1.body); cur.files.push(`IQA-007-${who}-denied.html`);
  }
  const s4 = await userStatus(admin.page, E4.login);
  const s5 = await userStatus(admin.page, E5.login);
  await shot(admin.page, 'accounts-after');
  cp('A1 查看：E4 仍为启用、E5 仍为停用', s4?.text === '启用' && s5?.text === '停用' && (await dbActive(E4.id)) === true && (await dbActive(E5.id)) === false, `E4=${JSON.stringify(s4)}；E5=${JSON.stringify(s5)}`);
}

// ================= IQA-008 周报表本周超时解决的工单数量 =================
async function iqa008() {
  const T = acc.T1.id;
  const now = Date.now();
  const { ws } = weekRange(now);
  const sinceWs = now - ws.getTime();
  const fr = new Date(now - Math.min(MIN, sinceWs / 2)); // 本周内、早于当前时刻
  const lastWeek = new Date(ws.getTime() - HOUR);
  const kw = `${P}kw008`;
  const resolvedEv = at => [{ actorId: T, type: 'STATUS', fromStatus: 'OPEN', toStatus: 'RESOLVED', at }];
  const mk = (n, urgency, submittedAt, status, firstResolvedAt) => mkTicket({ title: `${kw} ${n}`, categoryId: ids.baseCat, urgency, submitterId: acc.E1.id, submittedAt, status,
    assigneeId: firstResolvedAt ? T : null, firstResolvedAt, firstResolvedAssigneeId: firstResolvedAt ? T : null, events: firstResolvedAt ? resolvedEv(firstResolvedAt) : [] });
  const tk = {
    P1: await mk('P1', 'HIGH', new Date(fr.getTime() - 6 * HOUR), 'RESOLVED', fr),
    P2: await mk('P2', 'MEDIUM', new Date(fr.getTime() - 2 * HOUR), 'RESOLVED', fr),
    P3: await mk('P3', 'LOW', new Date(lastWeek.getTime() - 80 * HOUR), 'RESOLVED', lastWeek),
    P4: await mk('P4', 'HIGH', new Date(fr.getTime() - 6 * HOUR), 'RESOLVED', fr),
    P5: await mk('P5', 'HIGH', new Date(now - 5 * HOUR), 'OPEN', null),
    P6: await mk('P6', 'HIGH', new Date(now - 5 * HOUR), 'OPEN', null),
  };
  ids.iqa008 = tk;
  const t1 = await actor('IT同事T1', acc.T1);
  // 数据准备的状态变化（P3 本周改回处理中后再次解决、P4 改回待处理）与步骤 1（P5 直接关闭、P6 标记已解决）均经页面执行
  await statusUI(t1, tk.P3, 'IN_PROGRESS'); await statusUI(t1, tk.P3, 'RESOLVED');
  await statusUI(t1, tk.P4, 'OPEN');
  await statusUI(t1, tk.P5, 'CLOSED');
  await statusUI(t1, tk.P6, 'RESOLVED');
  const rows = await q('SELECT id, title, urgency, submitted_at, status, first_resolved_at FROM ticket WHERE id = ANY($1::bigint[]) ORDER BY id', [Object.values(tk)]);
  const st = Object.fromEntries(Object.entries(tk).map(([k, id]) => [k, rows.find(r => num(r.id) === id)]));
  need('步骤1 及数据准备的状态修改均已生效', st.P3.status === 'RESOLVED' && st.P4.status === 'OPEN' && st.P5.status === 'CLOSED' && st.P6.status === 'RESOLVED' && st.P6.first_resolved_at
    && new Date(st.P3.first_resolved_at).getTime() === lastWeek.getTime(), JSON.stringify(Object.fromEntries(Object.entries(st).map(([k, r]) => [k, [r.status, r.first_resolved_at]]))));
  const calc = ['| 工单 | 紧急程度 | 提交时间 | 期望解决时间 | 首次已解决时刻 | 当前状态 | 按 FR-017 计入 |', '|---|---|---|---|---|---|---|'];
  const counted = [];
  const { ws: w0, we: w1 } = weekRange();
  for (const [k, r] of Object.entries(st)) {
    const exp = expectedOf(r.submitted_at, r.urgency);
    const f = r.first_resolved_at ? new Date(r.first_resolved_at) : null;
    const inc = !!f && f >= w0 && f < w1 && f > exp;
    if (inc) counted.push(k);
    calc.push(`| ${k} #${num(r.id)} | ${URG[r.urgency]} | ${shMinute(r.submitted_at)} | ${shMinute(exp)} | ${f ? f.toISOString() : '无'} | ${STATUS[r.status]} | ${inc ? '是' : '否'} |`);
  }
  cp('合成数据按 FR-017 手工计算：恰好 P1、P4、P6 计入，P2、P3、P5 不计入', JSON.stringify(counted) === JSON.stringify(['P1', 'P4', 'P6']), `计入=${counted.join(',')}`);
  const admin = await actor('管理员A1', acc.A1);
  const rc = await reportCheck(admin);
  await shot(admin.page, 'report');
  const others = num((await q(`SELECT count(*) c FROM ticket t WHERE t.first_resolved_at >= $1 AND t.first_resolved_at < $2 AND t.first_resolved_at > ${EXPECTED_SQL} AND NOT (t.id = ANY($3::bigint[]))`, [w0, w1, Object.values(tk)]))[0].c);
  calc.push('', `环境中其他本周首次超时解决的工单（同一口径独立 SQL）：${others}；期望第五项 = 3 + ${others} = ${3 + others}；页面 = ${rc.rep.overdueResolvedText}`);
  calc.push('', `V1 四项与第五项独立计算：${JSON.stringify(rc.sql)}`);
  writeEvd('IQA-008-expected.md', ['# IQA-008 合成数据清单与手工计算（BQ-002 人工答案 A；V1 口径）', '', ...calc].join('\n')); cur.files.push('IQA-008-expected.md');
  cp('步骤2 周报表显示「本周超时解决的工单数量」，值 = P1、P4、P6 三张 + 环境中其他本周首次超时解决的工单', rc.rep.overdueResolved !== null && (rc.rep.overdueResolved === 3 + others || rc.rep.overdueResolved === rc.sql.overdueResolved) && rc.sql.overdueResolved - others === 3,
    `页面=${rc.rep.overdueResolvedText}；其他=${others}；独立计算全库=${rc.sql.overdueResolved}`);
  cp('步骤2 V1 原有四项与按 V1 口径独立计算的结果一致', rc.ok, rc.ok ? JSON.stringify(rc.sql) : rc.diffs.join('；'));
  for (const [who, account] of [['员工E1', acc.E1], ['IT同事T1', acc.T1]]) {
    const a = who === 'IT同事T1' ? t1 : await actor(who, account);
    const s = await go(a.page, '/admin/reports/weekly');
    const leaked = await a.page.getByTestId('report-overdue-resolved').count();
    await shot(a.page, `${who}-report-denied`);
    cp(`步骤3 ${who} 访问周报表被拒绝`, s === 403 && leaked === 0 && (await a.page.getByTestId('forbidden').count()) === 1, `GET /admin/reports/weekly -> ${s}`);
  }
}

// ================= IQA-010 重复执行升级不改变数据 =================
const TABLES = {
  app_user: 'SELECT id, login_name, display_name, role, active, password_hash, created_by, created_at FROM app_user ORDER BY id',
  category: 'SELECT id, name, active, created_at FROM category ORDER BY id',
  ticket: 'SELECT id, title, description, category_id, urgency, submitter_id, submitted_at, status, assignee_id, first_resolved_at, first_resolved_assignee_id, updated_at FROM ticket ORDER BY id',
  ticket_comment: 'SELECT id, ticket_id, author_id, author_role, body, created_at FROM ticket_comment ORDER BY id',
  ticket_event: 'SELECT id, ticket_id, actor_id, event_type, from_assignee_id, to_assignee_id, from_status, to_status, created_at FROM ticket_event ORDER BY id',
};
async function dbSnapshot() {
  const out = {};
  for (const [t, sql] of Object.entries(TABLES)) out[t] = Object.fromEntries((await q(sql)).map(r => [String(num(r.id)), sha(r)]));
  out.history = (await q('SELECT installed_rank, version, description, script, checksum, installed_on, success FROM flyway_schema_history ORDER BY installed_rank')).map(r => JSON.stringify(r));
  out.dups = {
    ticket: num((await q('SELECT count(*) c FROM (SELECT 1 FROM ticket GROUP BY title, submitter_id, submitted_at HAVING count(*) > 1) x'))[0].c),
    ticket_comment: num((await q('SELECT count(*) c FROM (SELECT 1 FROM ticket_comment GROUP BY ticket_id, author_id, body, created_at HAVING count(*) > 1) x'))[0].c),
    ticket_event: num((await q('SELECT count(*) c FROM (SELECT 1 FROM ticket_event GROUP BY ticket_id, actor_id, event_type, from_status, to_status, from_assignee_id, to_assignee_id, created_at HAVING count(*) > 1) x'))[0].c),
    app_user: num((await q('SELECT count(*) c FROM (SELECT 1 FROM app_user GROUP BY login_name HAVING count(*) > 1) x'))[0].c),
    category: num((await q('SELECT count(*) c FROM (SELECT 1 FROM category WHERE active GROUP BY name HAVING count(*) > 1) x'))[0].c),
  };
  return out;
}
// 关注范围：本次运行的前缀数据与 V1 基线（逐行逐列比对）；范围外的行只要求仍存在（共享库中他人可能修改）
async function scopeIds() {
  const p = `${P}%`, b = `${BASELINE_PREFIX}%`;
  const tickets = (await q(`SELECT id FROM ticket WHERE title LIKE $1 OR title LIKE $2 OR submitter_id IN (SELECT id FROM app_user WHERE login_name LIKE $1 OR login_name LIKE $2)`, [p, b])).map(r => String(num(r.id)));
  return {
    app_user: (await q('SELECT id FROM app_user WHERE login_name LIKE $1 OR login_name LIKE $2 OR login_name = $3', [p, b, SEC.login])).map(r => String(num(r.id))),
    category: (await q('SELECT id FROM category WHERE name LIKE $1 OR name LIKE $2', [p, b])).map(r => String(num(r.id))),
    ticket: tickets,
    ticket_comment: (await q('SELECT id FROM ticket_comment WHERE ticket_id = ANY($1::bigint[])', [tickets])).map(r => String(num(r.id))),
    ticket_event: (await q('SELECT id FROM ticket_event WHERE ticket_id = ANY($1::bigint[])', [tickets])).map(r => String(num(r.id))),
  };
}
async function uiSnapshot(t1, admin, ticketIds) {
  const tickets = {};
  for (const id of ticketIds) {
    const d = await detailFull(t1, id);
    tickets[id] = { v1: redact(JSON.stringify(readSubset(d))), expectedAt: d.expectedAt, expectedText: d.expectedText, overdueTag: d.overdueTag, status: d.status };
  }
  await go(admin.page, '/admin/users');
  const users = await admin.page.getByTestId('user-row').evaluateAll(els => els.map(r => ({ id: Number(r.getAttribute('data-user-id')),
    v: ['user-row-login', 'user-row-display', 'user-row-role', 'user-row-status'].map(k => r.querySelector(`[data-testid=${k}]`)?.textContent.trim()).join('|') })));
  const rep = await readReport(admin);
  return { tickets, users: Object.fromEntries(users.map(u => [String(u.id), sha(u.v)])), report: { cats: rep.cats, staff: rep.staff, unassigned: rep.unassigned, avgSec: rep.avgSec, backlog: rep.backlog, overdueResolved: rep.overdueResolved, week: rep.weekStartDate } };
}
const readSubset = d => ({ title: d.title, status: d.status, assignee: d.assignee, category: d.category, urgency: d.urgency, submitter: d.submitter, submitted: d.submitted, description: d.description, comments: d.comments, events: d.events });

async function iqa010Prepare() {
  cur = newCur('IQA-010-prepare', 'IQA-010 前置：在 V2 中新增数据并记录快照');
  let prep = { ok: false };
  try {
    const e6 = await actor('员工E6', acc.E6);
    const t1 = await actor('IT同事T1', acc.T1);
    const admin = await actor('管理员A1', acc.A1);
    const n1 = await submitTicketUI(e6, { title: `${P}kw010 V2 新工单`, categoryId: ids.baseCat, urgency: 'HIGH' });
    await commentUI(t1, n1, `${P}IQA010 回复`);
    const r = await setActiveUI(admin, acc.D1.login, false);
    const resolvedId = ids.iqa002?.C ?? n1;
    await statusUI(t1, resolvedId, 'RESOLVED');
    const okData = (await dbActive(acc.D1.id)) === false && (await ticketRow(resolvedId)).status === 'RESOLVED' && num((await q('SELECT count(*) c FROM ticket_comment WHERE ticket_id=$1', [n1]))[0].c) === 1;
    if (!okData) throw new Error(`V2 新增数据未全部生效：停用 D1 提示=${r.flash}`);
    const found = await findBaseline();
    const scope = await scopeIds();
    const ticketIds = scope.ticket.map(Number).sort((a, b) => a - b);
    const ui = await uiSnapshot(t1, admin, ticketIds);
    const db = await dbSnapshot();
    prep = { ok: true, at: new Date().toISOString(), n1, resolvedId, ticketIds, scope, ui, db, baselineDigest: found ? sha(await baselineDb(found.snap)) : null, sha: SHA };
    rlog(`IQA-010 快照完成：范围内工单 ${ticketIds.length} 张`);
  } catch (e) {
    prep = { ok: false, error: redact(`${e?.message ?? e}`).slice(0, 600), kind: cur.server5xx.length ? 'defect' : isEnvError(String(e?.message)) ? 'env' : 'gap' };
    rlog(`IQA-010 前置失败：${prep.error}`);
    for (const a of cur.actors) await shot(a.page, 'prepare-failure');
  }
  await closeActors();
  writeEvd('IQA-010-prepare.log', ['# IQA-010 前置：新增 V2 数据并记录快照（快照只含摘要，不含机密）', `prefix=${P} mode=${MODE}`, '', JSON.stringify({ ...prep, db: prep.db ? { history: prep.db.history, dups: prep.db.dups, rows: Object.fromEntries(Object.entries(prep.db).filter(([k]) => TABLES[k]).map(([k, v]) => [k, Object.keys(v).length])) } : null, ui: undefined, scope: undefined }, null, 2),
    '', '## 网络请求与响应', ...cur.net].join('\n'));
  return prep;
}

function parseRedeploy() {
  const raw = process.env.QA_REDEPLOY_EVIDENCE;
  if (!raw) return { present: false };
  try {
    const arr = JSON.parse(raw);
    const times = [];
    const walk = v => { if (typeof v === 'string' && /^\d{4}-\d{2}-\d{2}T/.test(v) && Number.isFinite(Date.parse(v))) times.push(Date.parse(v)); else if (v && typeof v === 'object') Object.values(v).forEach(walk); };
    walk(arr);
    return { present: Array.isArray(arr) && arr.length > 0, raw: arr, latest: times.length ? Math.max(...times) : null };
  } catch (e) { return { present: false, invalid: e.message }; }
}

async function iqa010(prep, redeployed) {
  if (!prep?.ok) need('IQA-010 前置（新增 V2 数据并记录快照）', false, `前置未完成：${prep?.error ?? '阶段 1 未留下快照状态'}；证据：IQA-010-prepare.log`, prep?.kind ?? 'gap');
  cp('IQA-010 前置：V2 新增数据（新工单与回复、停用 D1、合成工单改为已解决）并记录快照', true, `快照时间 ${prep.at}；新工单 #${prep.n1}；改为已解决 #${prep.resolvedId}；范围内工单 ${prep.ticketIds.length} 张；history ${prep.db.history.length} 行`);
  const rd = parseRedeploy();
  writeEvd('IQA-010-redeploy-evidence.json', JSON.stringify(rd, null, 2)); cur.files.push('IQA-010-redeploy-evidence.json');
  const didRedeploy = redeployed && rd.present;
  if (didRedeploy) {
    cp('平台在 IQA-010 之前对同一候选受控重新部署（即再次执行升级）', prep.sha === SHA && (rd.latest === null || rd.latest >= Date.parse(prep.at) - 60e3),
      `重新部署证据=${JSON.stringify(rd.raw).slice(0, 600)}；快照时间=${prep.at}；快照候选=${prep.sha}，当前候选=${SHA}`);
  } else {
    info('未执行再次升级', redeployed ? `已声明分阶段执行，但未获得平台重新部署证据（QA_REDEPLOY_EVIDENCE ${rd.invalid ? `无效：${rd.invalid}` : '缺失'}）` : '本次未分阶段执行');
    notes.push('IQA-010：本次未执行再次升级（未获得平台受控重新部署证据），只做快照数据与升级执行记录的只读核对；再次升级与中断后重跑以开发者升级演练记录为证据：.ai-delivery/features/002-v1/reports/implementation.md（UpgradeRehearsalIT 再次 migrate 执行 0 个、UpgradeInterruptionIT 中断回滚后重跑、e2e upgrade-repeat）');
  }
  const now = await dbSnapshot();
  // 升级执行记录
  cp(didRedeploy ? '重新部署前后升级执行记录完全相同（未新增或失败的升级执行）' : '升级执行记录：版本 2 只成功执行一次且无失败执行，与快照相同',
    JSON.stringify(now.history) === JSON.stringify(prep.db.history) && now.history.filter(h => JSON.parse(h).version === '2').length === 1 && now.history.every(h => JSON.parse(h).success),
    `快照=${prep.db.history.length} 行；当前=${now.history.length} 行；${now.history.map(h => { const x = JSON.parse(h); return `v${x.version}:${x.success}`; }).join(',')}`);
  // 数据：快照中的行全部仍在；范围内逐行逐列不变；无重复
  const missing = [], changed = [], outChanged = [];
  for (const t of Object.keys(TABLES)) {
    const scope = new Set(prep.scope[t]);
    for (const [id, h] of Object.entries(prep.db[t])) {
      if (!(id in now[t])) missing.push(`${t}#${id}`);
      else if (now[t][id] !== h) (scope.has(id) ? changed : outChanged).push(`${t}#${id}`);
    }
  }
  const counts = Object.fromEntries(Object.keys(TABLES).map(t => [t, `${Object.keys(prep.db[t]).length}->${Object.keys(now[t]).length}`]));
  cp('快照中的全部账号、类别、工单、沟通记录、处理记录仍存在', missing.length === 0, `缺失=${JSON.stringify(missing.slice(0, 50))}；行数=${JSON.stringify(counts)}`);
  cp('V2 新增数据与 V1 基线（本次范围内的全部行）逐行逐列不变', changed.length === 0, `改变=${JSON.stringify(changed.slice(0, 50))}`);
  if (outChanged.length) info('范围外行变化（共享库中他人操作，不作判定）', JSON.stringify(outChanged.slice(0, 50)));
  const newDups = Object.entries(now.dups).filter(([k, v]) => v > prep.db.dups[k]);
  cp('无重复的账号、类别、工单或记录', newDups.length === 0, `重复组数 快照=${JSON.stringify(prep.db.dups)} 当前=${JSON.stringify(now.dups)}`);
  const found = await findBaseline();
  cp('IQA-009 的 V1 基线数据完整保留', found && sha(await baselineDb(found.snap)) === prep.baselineDigest, `快照摘要=${prep.baselineDigest}`);
  // 页面：各身份登录后对比
  const t1 = await actor('IT同事T1', acc.T1);
  const admin = await actor('管理员A1', acc.A1);
  const diffT = [], badExp = [], badOver = [];
  for (const id of prep.ticketIds) {
    const s = prep.ui.tickets[id];
    const d = await detailFull(t1, id);
    if (redact(JSON.stringify(readSubset(d))) !== s.v1) diffT.push(id);
    if (d.expectedAt !== s.expectedAt || d.expectedText !== s.expectedText) badExp.push(id);
    const r = await ticketRow(id);
    if (r && !overdueOk(d, overdueExpect(r.status, expectedOf(r.submitted_at, r.urgency), d.t1, d.t2))) badOver.push(id);
  }
  await go(t1.page, `/tickets?q=${encodeURIComponent(P)}`); await shot(t1.page, 'tickets-after');
  cp('工单详情（含沟通与处理记录）与快照一致', diffT.length === 0, `不一致工单=${fmtIds(diffT)}`);
  cp('已有工单的期望解决时间不变', badExp.length === 0, `改变=${fmtIds(badExp)}`);
  cp('超时标识按当前状态与时刻正确显示', badOver.length === 0, `不符=${fmtIds(badOver)}`);
  const uiNow = await uiSnapshot(t1, admin, []);
  const uDiff = Object.entries(prep.ui.users).filter(([id, h]) => uiNow.users[id] !== h).map(([id]) => id);
  await go(admin.page, '/admin/users'); await shot(admin.page, 'accounts-after');
  cp('账号列表（登录名、姓名、身份、启用 / 停用状态）与快照一致', uDiff.length === 0, `不一致账号 id=${JSON.stringify(uDiff)}`);
  const sD1 = await userStatus(admin.page, acc.D1.login);
  const dl = await actor('停用账号D1');
  const rl = await tryLogin(dl, acc.D1);
  cp('停用账号 D1 仍为停用且仍不能登录', sD1?.text === '停用' && (await dbActive(acc.D1.id)) === false && !rl.loggedIn && !!rl.error, `列表=${JSON.stringify(sD1)}；登录=${JSON.stringify(rl)}`);
  const e6 = await actor('员工E6', acc.E6);
  const d6 = await openDetail(e6, prep.n1);
  cp('V2 新增工单与回复完整保留（提交人视角）', d6.httpStatus === 200 && d6.comments?.some(c => c.text === `${P}IQA010 回复`), `#${prep.n1} -> ${d6.httpStatus}，沟通 ${d6.comments?.length} 条`);
  const rc = await reportCheck(admin);
  await shot(admin.page, 'report-after');
  const r0 = prep.ui.report;
  const stable = JSON.stringify([r0.cats, r0.staff, r0.unassigned, r0.overdueResolved]) === JSON.stringify([rc.rep.cats, rc.rep.staff, rc.rep.unassigned, rc.rep.overdueResolved]) && (r0.avgSec === rc.rep.avgSec || Math.abs((r0.avgSec ?? 0) - (rc.rep.avgSec ?? 0)) <= 1.5);
  cp('周报表结果一致（与当前数据的独立计算一致；非时间相关项与快照相同，除非周期变化或他人修改了数据）', rc.ok && (stable || r0.week !== rc.rep.weekStartDate || outChanged.length > 0 || Object.keys(now.ticket).length !== Object.keys(prep.db.ticket).length),
    `快照=${JSON.stringify(r0)}；当前=${JSON.stringify({ cats: rc.rep.cats, staff: rc.rep.staff, unassigned: rc.rep.unassigned, avgSec: rc.rep.avgSec, backlog: rc.rep.backlog, overdueResolved: rc.rep.overdueResolved })}；${rc.ok ? '与独立计算一致' : rc.diffs.join('；')}`);
}

// ================= 清理（本次运行前缀） =================
async function cleanup() {
  cur = newCur('cleanup', '清理');
  try {
    const a = await actor('管理员A1');
    if (await login(a, acc.A1)) {
      for (const r of await q("SELECT login_name FROM app_user WHERE login_name LIKE $1 AND active ORDER BY id", [`${P}%`])) await setActiveUI(a, r.login_name, false);
    }
    await cleanupTicketsByPrefix(P);
    rlog(`已清理本次运行合成工单与类别，并经页面停用本次运行合成账号（${P}）`);
  } catch (e) { rlog(`清理失败：${e.message}`); }
  await closeActors();
}

// ---------------- 主流程 ----------------
const LIST1 = [
  ['IQA-009', '从 V1 升级后数据保持不变并补上期望解决时间', iqa009],
  ['IQA-001', '新工单按紧急程度获得期望解决时间', iqa001], ['IQA-002', '超时未解决工单醒目标出', iqa002],
  ['IQA-003', '管理员停用与重新启用账号', iqa003], ['IQA-004', '停用 IT 同事不再可分派且历史照常显示', iqa004],
  ['IQA-005', '停用员工的历史工单与沟通记录照常显示', iqa005], ['IQA-006', '系统始终至少保留一个可用管理员', iqa006],
  ['IQA-007', '停用与启用的访问控制', iqa007], ['IQA-008', '周报表本周超时解决的工单数量', iqa008],
];
const T010 = ['IQA-010', '重复执行升级不改变数据'];
async function main() {
  const expected = MODE === 'phase1' ? LIST1.map(x => x[0]) : MODE === 'phase2' ? ['IQA-010'] : [...LIST1.map(x => x[0]), 'IQA-010'];
  try { await q('SELECT 1'); } catch (e) { envProblem = `无法连接 ticket-db test 实例：${e.message}`; }
  if (!envProblem) {
    browser = await chromium.launch();
    try {
      if (MODE === 'phase2' && !state) {
        await scenario(...T010, async () => need('读取阶段 1 快照状态', false, `QA_STATE_DIR 中没有阶段 1 写入的 iqa-state.json（${STATE_FILE}；目录内容 ${JSON.stringify(stateSource.listing.slice(0, 20))}），库内备份也不可用（${stateSource.db?.error ?? '未读取'}）｜证据：IQA-010-state-source.json`, 'gap'));
      } else {
        let setupErr = null;
        try { await setup(); } catch (e) { setupErr = e; }
        if (setupErr) {
          const msg = String(setupErr.message).slice(0, 500);
          const kind = isEnvError(msg) ? 'env' : 'gap';
          if (kind === 'env') envProblem = `前置准备：${msg}`;
          for (const id of expected) await scenario(id, '前置准备失败', async () => need('前置准备（初始管理员登录、合成账号）', false, `${msg}；证据：setup-${MODE}.log`, kind));
        } else if (MODE === 'phase2') {
          rlog(`阶段 1 快照状态来源：${stateSource.source}`);
          await scenario(...T010, () => { info('阶段 1 快照状态来源', stateSource.source); return iqa010(state.prep, true); });
          await cleanup();
          await dropStateDb(P);
        } else {
          for (const [id, title, fn] of LIST1) await scenario(id, title, fn);
          const prep = await iqa010Prepare();
          if (MODE === 'phase1') {
            const accounts = Object.fromEntries(Object.entries(acc).filter(([k]) => k !== 'A1').map(([k, v]) => [k, { id: v.id, login: v.login, display: v.display, role: v.role }]));
            const json = JSON.stringify({ v: 1, prefix: P, accounts, ids, prep });
            const safe = !SECRET_VALUES.some(sv => json.toLowerCase().includes(sv.toLowerCase())) ? json : JSON.stringify({ v: 1, prefix: P, accounts, ids, prep: { ok: false, error: '快照含机密值，已拒绝写入状态', kind: 'gap' } });
            const manifest = { stateDir: STATE_DIR, file: STATE_FILE, bytes: Buffer.byteLength(safe), sha256: sha(safe), prefix: P, prepOk: !!prep?.ok };
            try {
              fs.mkdirSync(STATE_DIR, { recursive: true });
              const fd = fs.openSync(STATE_FILE, 'w'); fs.writeSync(fd, safe); fs.fsyncSync(fd); fs.closeSync(fd);
              manifest.fileWritten = sha(fs.readFileSync(STATE_FILE, 'utf-8')) === manifest.sha256;
            } catch (e) { manifest.fileWritten = false; manifest.fileError = e.message; }
            manifest.db = await saveStateDb(safe, P);
            manifest.listing = listDir(STATE_DIR);
            writeEvd('IQA-010-state-manifest.json', JSON.stringify(manifest, null, 2));
            rlog(`阶段 1 状态：文件 ${STATE_FILE} 写入=${manifest.fileWritten}（${manifest.bytes} 字节）；库内备份=${manifest.db.ok}${manifest.db.error ? ` ${manifest.db.error}` : ''}`);
            if (!manifest.fileWritten && !manifest.db.ok) testGaps.push(`阶段 1 未能保存 IQA-010 快照状态（QA_STATE_DIR 与库内备份均失败）｜证据：IQA-010-state-manifest.json`);
          } else {
            await scenario(...T010, () => iqa010(prep, false));
            await cleanup();
          }
        }
      }
    } catch (e) {
      rlog(`执行失败：${e.stack ?? e}`);
      if (isEnvError(String(e.message))) envProblem = envProblem ?? `执行：${e.message}`;
      else testGaps.push(`QA 脚本执行失败：${String(redact(e.message)).slice(0, 400)}｜证据：node-run.log`);
    } finally {
      try { await browser.close(); } catch { /* ignore */ }
    }
  }
  try { await pool.end(); } catch { /* ignore */ }
  writeEvd('node-run.log', runLog.join('\n'));
  let files = [];
  try { files = fs.readdirSync(EVD).sort(); } catch { /* ignore */ }
  const evidence = ['identity.json', 'health-attempts.log', 'node-run.log', ...files.filter(f => /^setup-.*\.log$/.test(f) || /^IQA-\d{3}(-prepare)?\.log$/.test(f) || /^IQA-010-state-(manifest|source)\.json$/.test(f)), ...notes];
  let finalDefects = defects, finalGaps = testGaps;
  if (envProblem) { evidence.unshift(redact(`执行环境问题：${envProblem}`)); finalDefects = []; finalGaps = []; }
  const missingScen = expected.filter(id => !scenarios.some(s => s.id === id));
  if (!envProblem && missingScen.length) finalGaps = [...finalGaps, `未产生场景结果：${missingScen.join('、')}｜证据：node-run.log`];
  const allPassed = !envProblem && missingScen.length === 0 && scenarios.every(s => s.passed) && finalDefects.length === 0 && finalGaps.length === 0;
  const failed = scenarios.filter(s => !s.passed).map(s => s.id);
  const phaseText = MODE === 'single' ? '未分阶段' : `阶段 ${PHASE}/${PHASES}`;
  const result = {
    passed: allPassed,
    testedCommitSha: SHA,
    environmentUrl: process.env.QA_BASE_URL,
    summary: redact(envProblem ? `（${phaseText}）执行环境问题导致未完成验证：${envProblem}`.slice(0, 500)
      : `（${phaseText}）执行 ${scenarios.length}/${expected.length} 个场景，通过 ${scenarios.filter(s => s.passed).length} 个${failed.length ? `；未通过：${failed.join('、')}` : ''}；产品缺陷 ${finalDefects.length} 项，测试缺口 ${finalGaps.length} 项${notes.length ? '；IQA-010 未执行再次升级（只读核对）' : ''}`),
    defects: finalDefects,
    testGaps: finalGaps,
    evidence,
    bugfixResults: [],
    scenarios,
  };
  let json = redact(JSON.stringify(result, null, 2));
  if (Buffer.byteLength(json) > 250000) {
    for (const s of result.scenarios) s.checkpoints = s.checkpoints.map(c => ({ ...c, observation: c.observation.slice(0, 200) }));
    json = redact(JSON.stringify(result));
  }
  fs.writeFileSync(RESULT, json);
  rlog(`结果已写出：passed=${allPassed}`);
}
await main();
NODE_EOF

log "开始执行场景"
timeout 840 node "$WORK_DIR/iqa-main.mjs" > "$EVD/node-stdout.log" 2>&1
rc=$?
log "场景执行结束 rc=$rc"
if [ ! -s "$QA_RESULT_PATH" ] || ! python3 -c 'import json,sys; json.load(open(sys.argv[1], encoding="utf-8"))' "$QA_RESULT_PATH" 2>/dev/null; then
  write_fallback_result gap "QA 脚本未能写出有效结果（退出码 $rc），场景结论不可用" \
    "node-stdout.log：QA 执行器退出码 $rc 且未写出有效结果，属于 QA 计划自身问题"
fi
exit 0
