#!/usr/bin/env bash
# Independent QA 执行脚本（DLV-20261116-72F2B9041E74 · 内部 IT 工单系统 · Feature 001-v1）
# 契约：工作目录为空的临时目录；stdin 为 /dev/null；只写工作目录、QA_RESULT_PATH、QA_EVIDENCE_DIR。
# 平台变量：QA_DELIVERY_ID、QA_CANDIDATE_SHA、QA_BASE_URL、QA_HEALTH_PATH、QA_RESULT_PATH、QA_EVIDENCE_DIR，
#           以及 ticket-db test 实例的 AIDH_DEP_TICKET_DB_*（以 QA 工作负载身份 + Entra 令牌访问，TLS 校验开启）。
# 场景：IQA-001～IQA-014（spec.md「验收场景」），计划见同目录上一级 tasks-qa.md。
# IQA-015 按人工澄清答案 HT-DLV-20261116-72F2B9041E74-0010（QD-001）延后到后续迭代验证，本脚本不执行、不判定；
# 脚本只维护供下一迭代核对的持久基线（不计入场景、缺陷或测试缺口）。
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

missing=""
for v in QA_DELIVERY_ID QA_CANDIDATE_SHA QA_BASE_URL QA_HEALTH_PATH QA_RESULT_PATH QA_EVIDENCE_DIR \
         AIDH_DEP_TICKET_DB_HOST AIDH_DEP_TICKET_DB_DATABASE AIDH_DEP_TICKET_DB_USER; do
  if [ -z "${!v:-}" ]; then missing="$missing $v"; fi
done
if [ -z "${QA_RESULT_PATH:-}" ]; then
  echo "缺少 QA_RESULT_PATH，无法写出结果" >&2
  exit 1
fi
mkdir -p "${QA_EVIDENCE_DIR:-$WORK_DIR/evidence}" 2>/dev/null || true
if [ -n "$missing" ]; then
  write_fallback_result env "执行环境缺少必需环境变量，未执行场景" "缺少环境变量:${missing}（执行环境问题，不计入产品缺陷）"
  exit 0
fi

BASE="${QA_BASE_URL%/}"
EVD="$QA_EVIDENCE_DIR"
RUNLOG="$EVD/run.log"
log() { echo "[$(date -u +%Y-%m-%dT%H:%M:%SZ)] $*" | tee -a "$RUNLOG" >&2; }

log "QA 开始：delivery=$QA_DELIVERY_ID sha=$QA_CANDIDATE_SHA base=$BASE health=$QA_HEALTH_PATH workdir=$WORK_DIR"

# ---------- 前置 1：健康检查（最多重试 5 分钟） ----------
deadline=$(( $(date +%s) + 300 ))
health_ok=0
attempt=0
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
      playwright@1.65.1 pg@8.23.1 @azure/identity@4.14.1 bcryptjs@3.0.3 > "$EVD/npm-install.log" 2>&1; then
  log "npm 安装失败"
  write_fallback_result gap "QA 脚本前置准备失败（npm 依赖安装），未执行场景" \
    "npm-install.log：QA 脚本无法在工作目录安装 playwright/pg/@azure/identity/bcryptjs，属于 QA 计划前置准备问题"
  exit 0
fi
log "测试工具安装完成"

# ---------- 场景执行（Node + Playwright Chromium + pg） ----------
cat > "$WORK_DIR/iqa-main.mjs" <<'NODE_EOF'
import { chromium } from 'playwright';
import pg from 'pg';
import { DefaultAzureCredential } from '@azure/identity';
import bcrypt from 'bcryptjs';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

const BASE = process.env.QA_BASE_URL.replace(/\/+$/, '');
const SHA = process.env.QA_CANDIDATE_SHA;
const DELIVERY = process.env.QA_DELIVERY_ID;
const EVD = process.env.QA_EVIDENCE_DIR;
const RESULT = process.env.QA_RESULT_PATH;
const T0 = Date.now();
const TIME_BUDGET_MS = 12 * 60 * 1000;
const DB_SCOPE = 'https://ossrdbms-aad.database.windows.net/.default';
const SERVICES = ['ticket-db'];

const STATUS = { OPEN: '待处理', IN_PROGRESS: '处理中', RESOLVED: '已解决', CLOSED: '已关闭' };
const ROLE = { EMPLOYEE: '员工', IT_STAFF: 'IT 同事', ADMIN: '管理员' };
const URG = { LOW: '低', MEDIUM: '中', HIGH: '高' };

const TOK = crypto.randomBytes(3).toString('hex');
const P = `iq${TOK}-`; // 本次运行的合成数据前缀
const runLog = [];
function rlog(msg) { const line = `[${new Date().toISOString()}] ${msg}`; runLog.push(line); console.log(line); }
function writeEvd(name, content) { try { fs.writeFileSync(path.join(EVD, name), content); } catch (e) { rlog(`写证据失败 ${name}: ${e.message}`); } }

// ---------------- 时间（公司时区 Asia/Shanghai，UTC+8，无夏令时；design TD-009） ----------------
const pad = n => String(n).padStart(2, '0');
function shParts(d) { const x = new Date(new Date(d).getTime() + 8 * 3600e3); return x; }
const shDate = d => { const x = shParts(d); return `${x.getUTCFullYear()}-${pad(x.getUTCMonth() + 1)}-${pad(x.getUTCDate())}`; };
const shMinute = d => { const x = shParts(d); return `${shDate(d)} ${pad(x.getUTCHours())}:${pad(x.getUTCMinutes())}`; };
const DAY = 86400e3, HOUR = 3600e3;

// ---------------- 数据库（ticket-db test 实例，Entra 令牌，TLS 校验） ----------------
const cred = new DefaultAzureCredential();
const pool = new pg.Pool({
  host: process.env.AIDH_DEP_TICKET_DB_HOST,
  database: process.env.AIDH_DEP_TICKET_DB_DATABASE,
  user: process.env.AIDH_DEP_TICKET_DB_USER,
  port: 5432,
  ssl: { rejectUnauthorized: true },
  max: 4,
  password: async () => (await cred.getToken(DB_SCOPE)).token,
});
const q = async (sql, params = []) => (await pool.query(sql, params)).rows;
const num = v => (v === null || v === undefined ? null : Number(v));

async function mkUser(login, display, role, password = `Pw-${crypto.randomBytes(12).toString('hex')}`) {
  const hash = bcrypt.hashSync(password, 10);
  const r = await q('INSERT INTO app_user(login_name,password_hash,display_name,role,created_at) VALUES($1,$2,$3,$4,now()) RETURNING id',
    [login, hash, display, role]);
  return { id: num(r[0].id), login, display, role, password };
}
async function mkCat(name) {
  const r = await q('INSERT INTO category(name,active,created_at) VALUES($1,TRUE,now()) RETURNING id', [name]);
  return { id: num(r[0].id), name };
}
async function mkTicket(t) {
  const at = t.submittedAt ?? new Date();
  const r = await q(`INSERT INTO ticket(title,description,category_id,urgency,submitter_id,submitted_at,status,assignee_id,
      first_resolved_at,first_resolved_assignee_id,updated_at) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$6) RETURNING id`,
  [t.title, t.description ?? '合成描述', t.categoryId, t.urgency ?? 'MEDIUM', t.submitterId, at, t.status ?? 'OPEN',
    t.assigneeId ?? null, t.firstResolvedAt ?? null, t.firstResolvedAssigneeId ?? null]);
  const id = num(r[0].id);
  for (const e of t.events ?? []) {
    await q(`INSERT INTO ticket_event(ticket_id,actor_id,event_type,from_assignee_id,to_assignee_id,from_status,to_status,created_at)
      VALUES($1,$2,$3,$4,$5,$6,$7,$8)`, [id, e.actorId, e.type, e.fromAssignee ?? null, e.toAssignee ?? null,
      e.fromStatus ?? null, e.toStatus ?? null, e.at]);
  }
  return id;
}
async function cleanupPrefix(prefix) {
  const p = `${prefix}%`;
  const users = '(SELECT id FROM app_user WHERE login_name LIKE $1)';
  const cats = '(SELECT id FROM category WHERE name LIKE $1)';
  const tickets = `(SELECT id FROM ticket WHERE submitter_id IN ${users} OR category_id IN ${cats}
    OR assignee_id IN ${users} OR first_resolved_assignee_id IN ${users})`;
  await q(`DELETE FROM ticket_event WHERE ticket_id IN ${tickets} OR actor_id IN ${users}
    OR from_assignee_id IN ${users} OR to_assignee_id IN ${users}`, [p]);
  await q(`DELETE FROM ticket_comment WHERE ticket_id IN ${tickets} OR author_id IN ${users}`, [p]);
  await q(`DELETE FROM ticket WHERE id IN ${tickets}`, [p]);
  await q('DELETE FROM category WHERE name LIKE $1', [p]);
  await q(`UPDATE app_user SET created_by = NULL WHERE created_by IN ${users}`, [p]);
  await q('DELETE FROM app_user WHERE login_name LIKE $1', [p]);
}
async function cleanupStale() {
  const rows = await q(`SELECT DISTINCT substring(login_name from '^(iq[0-9a-f]{6}-)') AS p FROM app_user
      WHERE login_name ~ '^iq[0-9a-f]{6}-' AND created_at < now() - interval '2 hours'
    UNION SELECT DISTINCT substring(name from '^(iq[0-9a-f]{6}-)') FROM category
      WHERE name ~ '^iq[0-9a-f]{6}-' AND created_at < now() - interval '2 hours'`);
  for (const r of rows) { if (r.p) { rlog(`清理过期运行残留 ${r.p}`); await cleanupPrefix(r.p); } }
}

// ---------------- 场景与检查点 ----------------
class Halt extends Error {}
let cur = null;
const scenarios = [];
const defects = [];
const testGaps = [];
let envProblem = null;

function netlog(line) { if (cur) cur.net.push(`[${new Date().toISOString()}] ${line}`); }
function cp(step, ok, observation, kind = 'defect') {
  const passed = !!ok;
  cur.checkpoints.push({ step, passed, observation: String(observation).slice(0, 1500) });
  cur.log.push(`${passed ? 'PASS' : 'FAIL'} | ${step} | ${observation}`);
  if (!passed) cur.failures.push({ step, observation: String(observation).slice(0, 600), kind });
  return passed;
}
function need(step, ok, observation, kind = 'defect') { if (!cp(step, ok, observation, kind)) throw new Halt(step); }
async function shot(page, label) {
  if (!page) return;
  cur.shotN += 1;
  const name = `${cur.id}-${pad(cur.shotN)}-${label.replace(/[^\w一-龥-]+/g, '_').slice(0, 40)}.png`;
  try { await page.screenshot({ path: path.join(EVD, name), fullPage: true }); cur.files.push(name); } catch (e) { cur.log.push(`截图失败 ${label}: ${e.message}`); }
}
async function saveHtml(page, label) {
  if (!page) return;
  const name = `${cur.id}-${label}.html`;
  try { writeEvd(name, await page.content()); cur.files.push(name); } catch { /* 页面已关闭 */ }
}
const eqSet = (a, b) => { const A = new Set(a.map(Number)), B = new Set(b.map(Number)); return A.size === B.size && [...A].every(x => B.has(x)); };
const fmtIds = a => `[${[...new Set(a.map(Number))].sort((x, y) => x - y).join(',')}]`;
const isEnvError = msg => /net::ERR_NAME|net::ERR_CONNECTION|net::ERR_CERT|net::ERR_SSL|ECONNREFUSED|ENOTFOUND|EAI_AGAIN|certificate|ETIMEDOUT|ECONNRESET|password authentication failed|AADSTS/i.test(msg);

async function scenario(id, title, fn) {
  cur = { id, title, checkpoints: [], failures: [], log: [], net: [], files: [], shotN: 0, server5xx: [], actors: [] };
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
  for (const a of cur.actors) { try { await a.ctx.close(); } catch { /* ignore */ } }
  const logName = `${id}.log`;
  writeEvd(logName, [`# ${id} ${title}`, `candidate=${SHA} base=${BASE} prefix=${P}`, '', '## 检查点', ...cur.log, '',
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

// IQA-015 延后（人工澄清答案 HT-DLV-20261116-72F2B9041E74-0010 / QD-001）：只维护持久基线，供下一迭代候选部署到同一 test 库后核对。
// 不进入 scenarios，不产生 defects / testGaps；观察结果只写入证据。
const deferredNotes = [];
const EXPECTED_SCENARIOS = 14;
const DEFERRED_NOTE = 'IQA-015（AC-020）未在本次 qa-run 执行：人工澄清答案 HT-DLV-20261116-72F2B9041E74-0010（QD-001）决定延后到后续迭代，以下一迭代候选部署到同一 test 库的真实升级为重新部署事实依据；第一版以 FR-029/FR-030 的设计证据为依据';
async function maintainDeferredBaseline() {
  cur = { id: 'IQA-015-deferred', title: '后续迭代基线维护', checkpoints: [], failures: [], log: [], net: [], files: [], shotN: 0, server5xx: [], actors: [] };
  if (Date.now() - T0 > TIME_BUDGET_MS) {
    deferredNotes.push('IQA-015 基线维护：时间预算已用尽，本次未执行（不影响本次结论）');
    return;
  }
  try { await iqa015(); } catch (e) { cur.log.push(`基线维护中断：${e?.message ?? e}`); cur.failures.push({ step: '基线维护', observation: String(e?.message ?? e).slice(0, 400), kind: 'info' }); }
  for (const a of cur.actors) { try { await a.ctx.close(); } catch { /* ignore */ } }
  writeEvd('IQA-015-deferred-baseline.log', ['# IQA-015 后续迭代基线维护（不计入本次 QA 结论）', `candidate=${SHA} base=${BASE}`, '', '## 观察', ...cur.log, '',
    '## 网络请求与响应（方法 URL -> 状态码；不记录请求体与凭据）', ...cur.net].join('\n'));
  deferredNotes.push(`IQA-015 基线维护：${cur.failures.length ? '有未通过的观察（仅供下一迭代参考）' : '完成'}，见 IQA-015-deferred-baseline.log`);
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
    need(`${label} 以合成账号登录`, ok, ok ? `已登录 ${account.login}` : `登录失败，当前 URL ${page.url()}`);
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
async function logout(a) { await submit(a.page, a.page.getByTestId('logout')); }
async function postForm(a, p, form) {
  const token = await a.page.locator('input[name="_csrf"]').first().getAttribute('value').catch(() => null);
  const r = await a.ctx.request.post(p, { form: { ...form, _csrf: token ?? '' }, maxRedirects: 0, headers: { Accept: 'text/html' } });
  const body = await r.text();
  netlog(`${a.label} POST ${p} (直接请求，字段 ${Object.keys(form).join(',')}) -> ${r.status()} location=${r.headers().location ?? ''}`);
  if (r.status() >= 500) cur.server5xx.push(`POST ${p} ${r.status()}`);
  return { status: r.status(), body, location: r.headers().location ?? '' };
}
async function txt(page, id) { const l = page.getByTestId(id).first(); return (await l.count()) ? ((await l.textContent()) ?? '').trim() : null; }
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
async function collectIds(page) {
  const ids = [];
  for (let i = 0; i < 100; i += 1) {
    ids.push(...(await page.getByTestId('ticket-row').evaluateAll(els => els.map(e => Number(e.getAttribute('data-ticket-id'))))));
    const next = page.getByTestId('page-next');
    if (!(await next.count())) break;
    await go(page, await next.getAttribute('href'));
  }
  return ids;
}
async function searchForm(a, f) {
  await go(a.page, '/tickets');
  const pg_ = a.page;
  await pg_.getByTestId('filter-q').fill(f.q ?? '');
  await pg_.getByTestId('filter-status').selectOption(f.status ?? '');
  await pg_.getByTestId('filter-category').selectOption(f.categoryId ? String(f.categoryId) : '');
  await pg_.getByTestId('filter-assignee').selectOption(f.assignee ? String(f.assignee) : '');
  await pg_.getByTestId('filter-from').fill(f.from ?? '');
  await pg_.getByTestId('filter-to').fill(f.to ?? '');
  await submit(pg_, pg_.getByTestId('filter-submit'));
  const url = pg_.url();
  const empty = (await pg_.getByTestId('ticket-empty').count()) > 0;
  const emptyText = empty ? await txt(pg_, 'ticket-empty') : null;
  const ids = await collectIds(pg_);
  return { ids, url, empty, emptyText };
}
const likeEsc = s => s.replace(/[\\%_]/g, m => `\\${m}`);
async function sqlFilter(f, submitterId = null) {
  const c = []; const p = [];
  const add = (sql, v) => { p.push(v); c.push(sql.replaceAll('?', `$${p.length}`)); };
  if (submitterId) add('submitter_id = ?', submitterId);
  if (f.q) add('(title ILIKE ? OR description ILIKE ?)', `%${likeEsc(f.q)}%`);
  if (f.status) add('status = ?', f.status);
  if (f.categoryId) add('category_id = ?', f.categoryId);
  if (f.assignee === 'unassigned') c.push('assignee_id IS NULL'); else if (f.assignee) add('assignee_id = ?', f.assignee);
  if (f.from) add("submitted_at >= (?::date)::timestamp AT TIME ZONE 'Asia/Shanghai'", f.from);
  if (f.to) add("submitted_at < ((?::date + 1))::timestamp AT TIME ZONE 'Asia/Shanghai'", f.to);
  const rows = await q(`SELECT id FROM ticket ${c.length ? `WHERE ${c.join(' AND ')}` : ''}`, p);
  return rows.map(r => num(r.id));
}
async function navSet(page) {
  const ids = ['nav-my-tickets', 'nav-new-ticket', 'nav-all-tickets', 'nav-admin-users', 'nav-admin-categories', 'nav-report'];
  const out = [];
  for (const id of ids) if (await page.getByTestId(id).count()) out.push(id);
  return out;
}
async function readReport(a) {
  const st = await go(a.page, '/admin/reports/weekly');
  const page = a.page;
  const week = await txt(page, 'report-week');
  const cats = await page.getByTestId('report-category-row').evaluateAll(els => els.map(e => ({ id: Number(e.getAttribute('data-category-id')),
    name: e.getAttribute('data-category-name'), label: e.querySelector('[data-testid=report-category-name]')?.textContent.trim(),
    count: Number(e.querySelector('[data-testid=report-count]')?.textContent.trim()) })));
  const staff = await page.getByTestId('report-staff-row').evaluateAll(els => els.map(e => ({ id: Number(e.getAttribute('data-user-id')),
    name: e.getAttribute('data-display-name'), count: Number(e.querySelector('[data-testid=report-count]')?.textContent.trim()) })));
  const unassigned = Number(await page.locator('[data-testid=report-unassigned] [data-testid=report-count]').textContent());
  const avgText = await txt(page, 'report-avg');
  const avgAttr = await page.getByTestId('report-avg').getAttribute('data-avg-seconds');
  const backlog = Number(await txt(page, 'report-backlog'));
  const m = /(\d{4}-\d{2}-\d{2}).*?(\d{4}-\d{2}-\d{2})/.exec(week ?? '');
  const weekStart = m ? new Date(`${m[1]}T00:00:00+08:00`) : null;
  return { httpStatus: st, week, weekStartDate: m?.[1], weekEndDate: m?.[2], weekStart, weekEnd: weekStart ? new Date(weekStart.getTime() + 7 * DAY) : null,
    cats, staff, unassigned, avgText, avgSec: avgAttr === null || avgAttr === '' ? null : Number(avgAttr), backlog };
}
async function sqlBacklog() { return num((await q("SELECT count(*) c FROM ticket WHERE status IN ('OPEN','IN_PROGRESS') AND submitted_at < now() - interval '72 hours'"))[0].c); }
async function sqlWeekResolved(ws, we, excludeIds = []) {
  const r = await q(`SELECT count(*) n, count(*) FILTER (WHERE first_resolved_assignee_id IS NULL) un,
      COALESCE(sum(EXTRACT(EPOCH FROM first_resolved_at - submitted_at)),0) s
    FROM ticket WHERE first_resolved_at >= $1 AND first_resolved_at < $2 AND NOT (id = ANY($3::bigint[]))`, [ws, we, excludeIds]);
  return { n: num(r[0].n), un: num(r[0].un), sum: Number(r[0].s) };
}

// ---------------- 运行时数据 ----------------
const acc = {};
let baseCat;
let preReport = null;

async function setup() {
  await cleanupStale();
  acc.A0 = await mkUser(`${P}admin`, `${P}种子管理员`, 'ADMIN');
  acc.E1 = await mkUser(`${P}e1`, `${P}员工甲`, 'EMPLOYEE');
  acc.E2 = await mkUser(`${P}e2`, `${P}员工乙`, 'EMPLOYEE');
  acc.T1 = await mkUser(`${P}t1`, `${P}IT甲`, 'IT_STAFF');
  acc.T2 = await mkUser(`${P}t2`, `${P}IT乙`, 'IT_STAFF');
  acc.U1 = await mkUser(`${P}u1`, `${P}待设IT`, 'EMPLOYEE');
  acc.S1 = await mkUser(`${P}s1`, `${P}报表IT一`, 'IT_STAFF');
  acc.S2 = await mkUser(`${P}s2`, `${P}报表IT二`, 'IT_STAFF');
  baseCat = await mkCat(`${P}基础类别`);
  rlog(`合成数据前缀 ${P}，账号与基础类别已准备`);
}

// ================= IQA-001 =================
async function iqa001() {
  const anon = await actor('匿名');
  await go(anon.page, '/login');
  const reg = await anon.page.locator('a, button').filter({ hasText: /注册|register|sign\s*up/i }).count();
  cp('登录页无自助注册入口', reg === 0 && (await anon.page.getByTestId('login-submit').count()) === 1, `注册类链接/按钮数=${reg}`);
  await shot(anon.page, 'login-page');
  await go(anon.page, '/tickets');
  cp('未登录访问工单列表被引导到登录', new URL(anon.page.url()).pathname === '/login', `访问 /tickets 后 URL=${anon.page.url()}`);
  await go(anon.page, '/admin/users');
  cp('未登录访问管理页被引导到登录', new URL(anon.page.url()).pathname === '/login', `访问 /admin/users 后 URL=${anon.page.url()}`);

  const admin = await actor('管理员A0', acc.A0);
  const created = {};
  for (const [k, role] of [['ne', 'EMPLOYEE'], ['nt', 'IT_STAFF'], ['na', 'ADMIN']]) {
    const a = { login: `${P}${k}`, password: `Pw-${crypto.randomBytes(10).toString('hex')}`, display: `${P}新建${ROLE[role]}`, role };
    await go(admin.page, '/admin/users');
    await admin.page.getByTestId('user-login').fill(a.login);
    await admin.page.getByTestId('user-password').fill(a.password);
    await admin.page.getByTestId('user-display').fill(a.display);
    await admin.page.getByTestId('user-role').selectOption(role);
    await submit(admin.page, admin.page.getByTestId('user-create'));
    const row = admin.page.locator(`[data-testid=user-row][data-login-name="${a.login}"]`);
    const roleText = (await row.count()) ? (await row.getByTestId('user-row-role').textContent()).trim() : null;
    const db = await q('SELECT role, display_name FROM app_user WHERE login_name=$1', [a.login]);
    cp(`管理员创建${ROLE[role]}账号`, roleText === ROLE[role] && db.length === 1 && db[0].role === role,
      `账号列表身份=${roleText}；库中=${JSON.stringify(db)}`);
    created[role] = a;
  }
  await shot(admin.page, 'account-list');
  // 重复登录名
  await go(admin.page, '/admin/users');
  await admin.page.getByTestId('user-login').fill(created.EMPLOYEE.login);
  await admin.page.getByTestId('user-password').fill('Pw-duplicate-attempt-1');
  await admin.page.getByTestId('user-display').fill(`${P}重复`);
  await admin.page.getByTestId('user-role').selectOption('EMPLOYEE');
  await submit(admin.page, admin.page.getByTestId('user-create'));
  const dupErr = await txt(admin.page, 'field-error-loginName');
  const dupCount = num((await q('SELECT count(*) c FROM app_user WHERE login_name=$1', [created.EMPLOYEE.login]))[0].c);
  const pwEcho = await admin.page.getByTestId('user-password').inputValue();
  cp('重复登录名被拒绝并提示', !!dupErr && dupCount === 1, `提示=${dupErr}；库中同名账号数=${dupCount}`);
  cp('失败回填时不回显密码', pwEcho === '', `密码框值长度=${pwEcho.length}`);
  await shot(admin.page, 'duplicate-login');
  // 必填项为空
  const emptyLogin = `${P}nx`;
  await go(admin.page, '/admin/users');
  await admin.page.getByTestId('user-login').fill(emptyLogin);
  await admin.page.getByTestId('user-password').fill('Pw-empty-display-1');
  await admin.page.getByTestId('user-display').fill('   ');
  await admin.page.getByTestId('user-role').selectOption('EMPLOYEE');
  await submit(admin.page, admin.page.getByTestId('user-create'));
  const emptyErr = await txt(admin.page, 'field-error-displayName');
  const emptyCnt = num((await q('SELECT count(*) c FROM app_user WHERE login_name=$1', [emptyLogin]))[0].c);
  cp('必填项为空时拒绝创建', !!emptyErr && emptyCnt === 0, `提示=${emptyErr}；库中账号数=${emptyCnt}`);

  const expectNav = { EMPLOYEE: ['nav-my-tickets', 'nav-new-ticket'], IT_STAFF: ['nav-all-tickets'], ADMIN: ['nav-admin-users', 'nav-admin-categories', 'nav-report'] };
  for (const role of ['EMPLOYEE', 'IT_STAFF', 'ADMIN']) {
    const u = await actor(`新${ROLE[role]}`);
    const ok = await login(u, created[role]);
    const nav = ok ? await navSet(u.page) : [];
    cp(`${ROLE[role]}新账号登录并只看到相符功能`, ok && JSON.stringify(nav) === JSON.stringify(expectNav[role]), `登录=${ok}；导航=${nav.join(',')}；期望=${expectNav[role].join(',')}`);
    await shot(u.page, `nav-${role}`);
    if (ok) {
      await logout(u);
      const after = new URL(u.page.url());
      await go(u.page, '/tickets');
      cp(`${ROLE[role]}退出登录`, after.pathname === '/login' && new URL(u.page.url()).pathname === '/login', `退出后 URL=${after}；再次访问 /tickets → ${u.page.url()}`);
    }
  }
  const bad = await actor('错误凭据');
  const okBad = await login(bad, { login: created.EMPLOYEE.login, password: 'Wrong-password-0' });
  const err1 = await txt(bad.page, 'login-error');
  cp('错误凭据登录失败并停留在登录入口', !okBad && !!err1 && new URL(bad.page.url()).pathname === '/login', `登录成功=${okBad}；提示=${err1}；URL=${bad.page.url()}`);
  await shot(bad.page, 'wrong-password');
  const okNone = await login(bad, { login: `${P}nobody`, password: 'Wrong-password-0' });
  const err2 = await txt(bad.page, 'login-error');
  cp('不存在账号与密码错误提示无差异', !okNone && err1 === err2, `密码错误提示=${err1}；账号不存在提示=${err2}`);
}

// ================= IQA-002 =================
async function iqa002() {
  const e = await actor('员工E1', acc.E1);
  await go(e.page, '/tickets/new');
  const urg = await e.page.locator('[data-testid=ticket-urgency] option').evaluateAll(os => os.filter(o => o.value).map(o => o.textContent.trim()));
  cp('紧急程度选项恰为低、中、高', JSON.stringify(urg) === JSON.stringify(['低', '中', '高']), `选项=${urg.join('、')}`);
  await shot(e.page, 'new-form');
  const fill = async v => {
    await go(e.page, '/tickets/new');
    await e.page.getByTestId('ticket-title').fill(v.title);
    await e.page.getByTestId('ticket-description').fill(v.description);
    await e.page.getByTestId('ticket-category').selectOption(v.categoryId);
    await e.page.getByTestId('ticket-urgency').selectOption(v.urgency);
    await submit(e.page, e.page.getByTestId('ticket-submit'));
  };
  const full = { title: `${P}必填校验标题`, description: `${P}必填校验描述`, categoryId: String(baseCat.id), urgency: 'MEDIUM' };
  for (const [field, blank] of [['title', ''], ['description', '   '], ['categoryId', ''], ['urgency', '']]) {
    const v = { ...full, [field]: blank };
    await fill(v);
    const err = await txt(e.page, `field-error-${field}`);
    const kept = {
      title: await e.page.getByTestId('ticket-title').inputValue().catch(() => null),
      description: await e.page.getByTestId('ticket-description').inputValue().catch(() => null),
      categoryId: await e.page.getByTestId('ticket-category').inputValue().catch(() => null),
      urgency: await e.page.getByTestId('ticket-urgency').inputValue().catch(() => null),
    };
    const keptOk = Object.keys(kept).filter(k => k !== field).every(k => kept[k] === v[k]);
    const notCreated = !/\/tickets\/\d+$/.test(new URL(e.page.url()).pathname);
    cp(`缺少「${field}」被拒、指出缺失项并保留已填内容`, !!err && keptOk && notCreated, `提示=${err}；回填=${JSON.stringify(kept)}；URL=${e.page.url()}`);
    await shot(e.page, `missing-${field}`);
  }
  const cnt = num((await q('SELECT count(*) c FROM ticket WHERE title=$1', [full.title]))[0].c);
  cp('缺项提交未产生工单', cnt === 0, `库中同标题工单数=${cnt}`);
  const ids = [];
  for (const [code, label] of [['LOW', '低'], ['MEDIUM', '中'], ['HIGH', '高']]) {
    const title = `${P}提交${label}级工单`;
    await fill({ title, description: `${P}合成问题描述 ${label}`, categoryId: String(baseCat.id), urgency: code });
    const m = /\/tickets\/(\d+)$/.exec(new URL(e.page.url()).pathname);
    if (!cp(`完整提交紧急程度「${label}」的工单`, !!m, `提交后 URL=${e.page.url()}`)) continue;
    const id = Number(m[1]); ids.push(id);
    const d = await readDetail(e.page);
    const db = (await q('SELECT submitter_id, submitted_at, status, assignee_id, urgency FROM ticket WHERE id=$1', [id]))[0];
    const fresh = db && Math.abs(Date.now() - new Date(db.submitted_at).getTime()) < 10 * 60e3;
    cp(`工单 #${id} 详情：编号、紧急程度、待处理、未分配、提交人与提交时间`,
      d.title === title && d.urgency === label && d.status === '待处理' && d.assignee === '未分配' && d.submitter === acc.E1.display
      && db && d.submitted === shMinute(db.submitted_at) && num(db.submitter_id) === acc.E1.id && fresh,
      `详情=${JSON.stringify({ t: d.title, u: d.urgency, s: d.status, a: d.assignee, by: d.submitter, at: d.submitted })}；库=${JSON.stringify(db)}`);
    const editable = await e.page.locator('input[name=title], textarea[name=description], select[name=categoryId], select[name=urgency]').count();
    const staffUi = await e.page.getByTestId('staff-actions').count();
    cp(`工单 #${id} 详情无修改基本信息入口`, editable === 0 && staffUi === 0, `可编辑基本信息控件数=${editable}；处理操作区=${staffUi}`);
    await shot(e.page, `detail-${code}`);
  }
  cp('三张工单编号唯一', ids.length === 3 && new Set(ids).size === 3, `编号=${fmtIds(ids)}`);
  await go(e.page, '/tickets');
  const mine = await collectIds(e.page);
  cp('工单出现在 E1「我的工单」', ids.every(i => mine.includes(i)), `我的工单=${fmtIds(mine)}`);
  await shot(e.page, 'my-tickets');
  const t = await actor('IT同事T1', acc.T1);
  const r = await searchForm(t, { q: `${P}提交` });
  const rows = await t.page.getByTestId('ticket-row').evaluateAll(els => els.map(x => ({ id: Number(x.getAttribute('data-ticket-id')),
    status: x.querySelector('[data-testid=row-status]')?.textContent.trim(), assignee: x.querySelector('[data-testid=row-assignee]')?.textContent.trim() })));
  const okRows = ids.every(i => rows.some(x => x.id === i && x.status === '待处理' && x.assignee === '未分配'));
  cp('工单出现在 T1 全部工单列表（待处理、未分配）', ids.every(i => r.ids.includes(i)) && okRows, `T1 列表=${JSON.stringify(rows)}`);
  await shot(t.page, 'it-list');
}

// ================= IQA-003 =================
async function iqa003() {
  const kw = `${P}共享词`;
  const now = new Date();
  const e1t = [
    await mkTicket({ title: `${kw} 甲一`, categoryId: baseCat.id, submitterId: acc.E1.id, status: 'OPEN' }),
    await mkTicket({ title: `${kw} 甲二`, categoryId: baseCat.id, submitterId: acc.E1.id, status: 'IN_PROGRESS', assigneeId: acc.T1.id,
      events: [{ actorId: acc.T1.id, type: 'CLAIM', toAssignee: acc.T1.id, at: now }, { actorId: acc.T1.id, type: 'STATUS', fromStatus: 'OPEN', toStatus: 'IN_PROGRESS', at: now }] }),
  ];
  const e2t = [
    await mkTicket({ title: `${kw} 乙一`, description: `${P}乙的私密描述`, categoryId: baseCat.id, submitterId: acc.E2.id, status: 'OPEN' }),
    await mkTicket({ title: `${kw} 乙二`, categoryId: baseCat.id, submitterId: acc.E2.id, status: 'CLOSED', assigneeId: acc.T2.id,
      events: [{ actorId: acc.T2.id, type: 'CLAIM', toAssignee: acc.T2.id, at: now }, { actorId: acc.T2.id, type: 'STATUS', fromStatus: 'OPEN', toStatus: 'CLOSED', at: now }] }),
  ];
  const e = await actor('员工E1', acc.E1);
  await go(e.page, '/tickets');
  const listTitle = await txt(e.page, 'list-title');
  const ui = await collectIds(e.page);
  const dbMine = (await q('SELECT id, status, assignee_id FROM ticket WHERE submitter_id=$1', [acc.E1.id]));
  cp('E1「我的工单」恰好包含其全部工单', eqSet(ui, dbMine.map(r => num(r.id))) && !e2t.some(i => ui.includes(i)), `标题=${listTitle}；列表=${fmtIds(ui)}；库中 E1 工单=${fmtIds(dbMine.map(r => r.id))}`);
  await go(e.page, '/tickets?q=' + encodeURIComponent(kw));
  const rows = await e.page.getByTestId('ticket-row').evaluateAll(els => els.map(x => ({ id: Number(x.getAttribute('data-ticket-id')),
    status: x.querySelector('[data-testid=row-status]')?.textContent.trim(), assignee: x.querySelector('[data-testid=row-assignee]')?.textContent.trim() })));
  const r1 = rows.find(x => x.id === e1t[0]), r2 = rows.find(x => x.id === e1t[1]);
  cp('列表显示正确状态与处理人', r1?.status === '待处理' && r1?.assignee === '未分配' && r2?.status === '处理中' && r2?.assignee === acc.T1.display, JSON.stringify(rows));
  await shot(e.page, 'my-list');
  for (const id of e1t) {
    const d = await openDetail(e, id);
    const hasSections = (await e.page.getByTestId('event-list').count()) === 1 && (await e.page.getByTestId('comment-list').count()) === 1;
    cp(`E1 打开本人工单 #${id} 详情`, d.httpStatus === 200 && hasSections && d.status && d.assignee, `HTTP=${d.httpStatus} 状态=${d.status} 处理人=${d.assignee} 处理记录=${d.events?.length}`);
  }
  await shot(e.page, 'my-detail');
  const st = await go(e.page, `/tickets/${e2t[0]}`);
  const html = await e.page.content();
  const leak = [`${kw} 乙一`, `${P}乙的私密描述`, acc.E2.display].filter(s => html.includes(s));
  cp('直接访问 E2 工单被拒且无内容泄露', st === 403 && (await e.page.getByTestId('forbidden').count()) === 1 && leak.length === 0, `HTTP=${st}；泄露片段=${JSON.stringify(leak)}`);
  await shot(e.page, 'forbidden-other');
  await go(e.page, '/tickets');
  const before = num((await q('SELECT count(*) c FROM ticket_comment WHERE ticket_id=$1', [e2t[0]]))[0].c);
  const pc = await postForm(e, `/tickets/${e2t[0]}/comments`, { body: `${P}越权补充` });
  const after = num((await q('SELECT count(*) c FROM ticket_comment WHERE ticket_id=$1', [e2t[0]]))[0].c);
  cp('直接向 E2 工单添加补充说明被拒', pc.status === 403 && before === after, `HTTP=${pc.status}；沟通记录数 ${before}→${after}`);
  const filters = [
    ['关键词', { q: kw }], ['状态=待处理', { q: kw, status: 'OPEN' }], ['状态=已关闭', { q: kw, status: 'CLOSED' }],
    ['类别', { q: kw, categoryId: baseCat.id }], ['处理人=未分配', { q: kw, assignee: 'unassigned' }],
    ['处理人=T2', { q: kw, assignee: acc.T2.id }], ['提交日期=今天', { q: kw, from: shDate(now), to: shDate(now) }],
  ];
  for (const [label, f] of filters) {
    const r = await searchForm(e, f);
    const exp = await sqlFilter(f, acc.E1.id);
    cp(`E1 ${label} 检索结果不含 E2 工单且等于本人可见集合`, eqSet(r.ids, exp) && !e2t.some(i => r.ids.includes(i)), `结果=${fmtIds(r.ids)}；期望=${fmtIds(exp)}；E2 工单=${fmtIds(e2t)}`);
    if (label === '关键词') await shot(e.page, 'search-kw');
  }
}

// ================= IQA-004 =================
async function iqa004() {
  const id = await mkTicket({ title: `${P}沟通测试`, categoryId: baseCat.id, submitterId: acc.E1.id });
  const e = await actor('员工E1', acc.E1);
  const t = await actor('IT同事T1', acc.T1);
  const addComment = async (a, body) => { await go(a.page, `/tickets/${id}`); await a.page.getByTestId('comment-body').fill(body); await submit(a.page, a.page.getByTestId('comment-submit')); };
  const cCount = async () => num((await q('SELECT count(*) c FROM ticket_comment WHERE ticket_id=$1', [id]))[0].c);
  const c1 = `${P}员工补充说明：重启后仍无法联网`;
  await addComment(e, c1);
  cp('E1 添加补充说明成功', (await cCount()) === 1, `沟通记录数=${await cCount()}；flash=${await txt(e.page, 'flash')}`);
  await addComment(e, '   ');
  const err1 = await txt(e.page, 'field-error-body');
  cp('空补充说明被拒', !!err1 && (await cCount()) === 1, `提示=${err1}；沟通记录数=${await cCount()}`);
  const r1 = `${P}IT 回复：请检查网线`;
  await addComment(t, r1);
  cp('T1 回复成功', (await cCount()) === 2, `沟通记录数=${await cCount()}`);
  await addComment(t, '');
  const err2 = await txt(t.page, 'field-error-body');
  cp('空回复被拒', !!err2 && (await cCount()) === 2, `提示=${err2}`);
  const de = await openDetail(e, id);
  await shot(e.page, 'employee-view');
  const dt = await openDetail(t, id);
  await shot(t.page, 'it-view');
  const okOrder = de.comments?.length === 2 && de.comments[0].text === c1 && de.comments[0].author === acc.E1.display && de.comments[0].role === '员工'
    && de.comments[1].text === r1 && de.comments[1].author === acc.T1.display && de.comments[1].role === 'IT 同事'
    && de.comments.every(c => /\d{4}-\d{2}-\d{2} \d{2}:\d{2}/.test(c.time ?? '')) && de.comments[0].time <= de.comments[1].time;
  cp('沟通记录按时间顺序显示作者、身份、时间、内容', okOrder, JSON.stringify(de.comments));
  cp('E1 与 T1 看到一致的沟通记录', JSON.stringify(de.comments) === JSON.stringify(dt.comments), `T1=${JSON.stringify(dt.comments)}`);
  const db = (await q('SELECT status, assignee_id FROM ticket WHERE id=$1', [id]))[0];
  cp('补充说明与回复不改变状态和处理人', de.status === '待处理' && de.assignee === '未分配' && db.status === 'OPEN' && db.assignee_id === null, `详情 ${de.status}/${de.assignee}；库 ${JSON.stringify(db)}`);
  await go(t.page, `/tickets/${id}`);
  await t.page.getByTestId('status-select').selectOption('CLOSED');
  await submit(t.page, t.page.getByTestId('status-submit'));
  const c2 = `${P}关闭后再补充`;
  await addComment(e, c2);
  const d2 = await readDetail(e.page);
  cp('已关闭工单仍可补充说明且状态不变', d2.status === '已关闭' && d2.comments.at(-1)?.text === c2 && (await cCount()) === 3, `状态=${d2.status}；最后一条=${d2.comments.at(-1)?.text}`);
  await shot(e.page, 'closed-comment');
}

// ================= IQA-005 =================
async function iqa005() {
  const X = await mkTicket({ title: `${P}认领测试 E1`, categoryId: baseCat.id, submitterId: acc.E1.id });
  const Y = await mkTicket({ title: `${P}分派测试 E2`, categoryId: baseCat.id, submitterId: acc.E2.id });
  const t1 = await actor('IT同事T1', acc.T1);
  await go(t1.page, '/tickets');
  const listTitle = await txt(t1.page, 'list-title');
  const all = await collectIds(t1.page);
  const dbAll = (await q('SELECT id FROM ticket')).map(r => num(r.id));
  const e1All = (await q('SELECT id FROM ticket WHERE submitter_id=$1', [acc.E1.id])).map(r => num(r.id));
  const e2All = (await q('SELECT id FROM ticket WHERE submitter_id=$1', [acc.E2.id])).map(r => num(r.id));
  cp('T1 看到全部工单（含 E1、E2 全部工单）', eqSet(all, dbAll) && [...e1All, ...e2All].every(i => all.includes(i)), `标题=${listTitle}；列表 ${all.length} 条；库中 ${dbAll.length} 条`);
  await shot(t1.page, 'all-list');
  await go(t1.page, `/tickets/${X}`);
  await submit(t1.page, t1.page.getByTestId('claim-button'));
  let d = await readDetail(t1.page);
  const ev1 = d.events.at(-1);
  cp('T1 认领后处理人为 T1 并产生认领记录', d.assignee === acc.T1.display && ev1?.type === 'CLAIM' && ev1.actor === acc.T1.display && ev1.from === '未分配' && ev1.to === acc.T1.display,
    `处理人=${d.assignee}；记录=${JSON.stringify(d.events)}`);
  await go(t1.page, `/tickets/${Y}`);
  const opts = await t1.page.locator('[data-testid=assign-select] option').evaluateAll(os => os.filter(o => o.value).map(o => Number(o.value)));
  const dbStaff = (await q("SELECT id FROM app_user WHERE role='IT_STAFF' AND id<>$1", [acc.T1.id])).map(r => num(r.id));
  cp('分派对象只包含当前 IT 同事', eqSet(opts, dbStaff) && ![acc.E1.id, acc.E2.id, acc.A0.id, acc.U1.id].some(i => opts.includes(i)), `选项=${fmtIds(opts)}；库中当前其他 IT 同事=${fmtIds(dbStaff)}`);
  await shot(t1.page, 'assign-options');
  await t1.page.getByTestId('assign-select').selectOption(String(acc.T2.id));
  await submit(t1.page, t1.page.getByTestId('assign-submit'));
  d = await readDetail(t1.page);
  const ev2 = d.events.at(-1);
  cp('T1 分派给 T2 后处理人为 T2 并产生分派记录', d.assignee === acc.T2.display && ev2?.type === 'ASSIGN' && ev2.actor === acc.T1.display && ev2.from === '未分配' && ev2.to === acc.T2.display,
    `处理人=${d.assignee}；记录=${JSON.stringify(d.events)}`);
  const t2 = await actor('IT同事T2', acc.T2);
  await go(t2.page, `/tickets/${X}`);
  await submit(t2.page, t2.page.getByTestId('claim-button'));
  d = await readDetail(t2.page);
  const ev3 = d.events.at(-1);
  cp('T2 认领已由 T1 处理的工单，记录操作人、时间与前后值', d.assignee === acc.T2.display && ev3?.type === 'CLAIM' && ev3.actor === acc.T2.display
    && ev3.from === acc.T1.display && ev3.to === acc.T2.display && /\d{4}-\d{2}-\d{2} \d{2}:\d{2}/.test(ev3.time ?? ''), `记录=${JSON.stringify(d.events)}`);
  await shot(t2.page, 'reclaim');
  const e = await actor('员工E1', acc.E1);
  const de = await openDetail(e, X);
  cp('E1 看到最新处理人和处理记录', de.assignee === acc.T2.display && de.events?.length === 2 && JSON.stringify(de.events) === JSON.stringify(d.events), `处理人=${de.assignee}；记录=${JSON.stringify(de.events)}`);
  await shot(e.page, 'employee-view');
}

// ================= IQA-006 =================
async function iqa006() {
  const title = `${P}状态修改测试`;
  const Z = await mkTicket({ title, categoryId: baseCat.id, submitterId: acc.E1.id });
  const t = await actor('IT同事T1', acc.T1);
  const e = await actor('员工E1', acc.E1);
  await go(t.page, `/tickets/${Z}`);
  const opts = await t.page.locator('[data-testid=status-select] option').evaluateAll(os => os.map(o => o.textContent.trim()));
  cp('状态只能选择四种', JSON.stringify([...opts].sort()) === JSON.stringify(Object.values(STATUS).sort()), `选项=${opts.join('、')}`);
  let prev = 'OPEN';
  let n = 0;
  for (const s of ['IN_PROGRESS', 'RESOLVED', 'IN_PROGRESS', 'RESOLVED', 'CLOSED', 'OPEN']) {
    n += 1;
    await go(t.page, `/tickets/${Z}`);
    await t.page.getByTestId('status-select').selectOption(s);
    await submit(t.page, t.page.getByTestId('status-submit'));
    const d = await readDetail(t.page);
    const last = d.events.at(-1);
    await go(t.page, `/tickets?q=${encodeURIComponent(title)}`);
    const rowStatus = (await t.page.locator(`[data-testid=ticket-row][data-ticket-id="${Z}"] [data-testid=row-status]`).textContent())?.trim();
    const de = await openDetail(e, Z);
    cp(`第 ${n} 步 ${STATUS[prev]}→${STATUS[s]}：详情、列表、提交人视角立即显示且产生处理记录`,
      d.status === STATUS[s] && rowStatus === STATUS[s] && de.status === STATUS[s] && d.events.length === n && last?.type === 'STATUS'
      && last.from === STATUS[prev] && last.to === STATUS[s] && last.actor === acc.T1.display,
      `详情=${d.status} 列表=${rowStatus} 提交人=${de.status} 记录数=${d.events.length} 最新=${JSON.stringify(last)}`);
    await shot(e.page, `step${n}-${s}`);
    prev = s;
  }
}

// ================= IQA-007 =================
async function iqa007() {
  const V = await mkTicket({ title: `${P}员工越权测试`, categoryId: baseCat.id, submitterId: acc.E1.id });
  const snap = async () => JSON.stringify((await q(`SELECT t.status, t.assignee_id, t.first_resolved_at, (SELECT count(*) FROM ticket_event WHERE ticket_id=t.id) ev
    FROM ticket t WHERE id=$1`, [V]))[0]);
  const before = await snap();
  const e = await actor('员工E1', acc.E1);
  await go(e.page, `/tickets/${V}`);
  const entries = {};
  for (const id of ['staff-actions', 'claim-button', 'assign-select', 'status-select']) entries[id] = await e.page.getByTestId(id).count();
  cp('员工详情无认领、分派、改状态入口', Object.values(entries).every(c => c === 0), JSON.stringify(entries));
  await shot(e.page, 'detail-no-actions');
  for (const [p, form] of [[`/tickets/${V}/claim`, {}], [`/tickets/${V}/assign`, { assigneeId: String(acc.T1.id) }], [`/tickets/${V}/status`, { status: 'RESOLVED' }]]) {
    await go(e.page, `/tickets/${V}`);
    const r = await postForm(e, p, form);
    cp(`直接发起 ${p} 被拒`, r.status === 403, `HTTP=${r.status} location=${r.location}`);
  }
  await go(e.page, `/tickets/${V}`);
  const navDone = e.page.waitForEvent('load', { timeout: 45000 });
  await e.page.evaluate(id => {
    const f = document.createElement('form'); f.method = 'post'; f.action = `/tickets/${id}/status`;
    const csrf = document.querySelector('input[name="_csrf"]');
    for (const [k, v] of [['status', 'IN_PROGRESS'], ['_csrf', csrf ? csrf.value : '']]) { const i = document.createElement('input'); i.type = 'hidden'; i.name = k; i.value = v; f.appendChild(i); }
    document.body.appendChild(f); f.submit();
  }, V);
  await navDone;
  cp('页面直接提交改状态显示无权限提示', (await e.page.getByTestId('forbidden').count()) === 1, `URL=${e.page.url()}`);
  await shot(e.page, 'forbidden');
  const after = await snap();
  const d = await openDetail(e, V);
  cp('工单状态、处理人与处理记录不变', before === after && d.status === '待处理' && d.assignee === '未分配' && d.events.length === 0, `操作前=${before}；操作后=${after}；详情 ${d.status}/${d.assignee}`);
}

// ================= IQA-008 =================
async function iqa008() {
  const a = await actor('管理员A0', acc.A0);
  const e = await actor('员工E1', acc.E1);
  const t = await actor('IT同事T1', acc.T1);
  const jia = `${P}测试类别甲`, yi = `${P}测试类别乙`;
  const createCat = async name => { await go(a.page, '/admin/categories'); await a.page.getByTestId('category-new-name').fill(name); await submit(a.page, a.page.getByTestId('category-create')); };
  await createCat(jia);
  const row = a.page.locator(`[data-testid=category-row][data-category-name="${jia}"]`);
  need('新增类别「测试类别甲」', (await row.count()) === 1, `类别行数=${await row.count()}`);
  const catId = Number(await row.getAttribute('data-category-id'));
  await shot(a.page, 'category-added');
  await go(e.page, '/tickets/new');
  const opts1 = await e.page.locator('[data-testid=ticket-category] option').evaluateAll(os => os.map(o => o.textContent.trim()));
  cp('员工提交页可选择新类别', opts1.includes(jia), `选项含新类别=${opts1.includes(jia)}`);
  await e.page.getByTestId('ticket-title').fill(`${P}类别甲工单`);
  await e.page.getByTestId('ticket-description').fill('使用新类别提交');
  await e.page.getByTestId('ticket-category').selectOption(String(catId));
  await e.page.getByTestId('ticket-urgency').selectOption('LOW');
  await submit(e.page, e.page.getByTestId('ticket-submit'));
  const m = /\/tickets\/(\d+)$/.exec(new URL(e.page.url()).pathname);
  need('员工用新类别提交工单', !!m, `URL=${e.page.url()}`);
  const G = Number(m[1]);
  await createCat('   ');
  const errEmpty = await txt(a.page, 'field-error-name');
  await createCat(jia);
  const errDup = await txt(a.page, 'field-error-name');
  const dupCnt = num((await q('SELECT count(*) c FROM category WHERE name=$1 AND active', [jia]))[0].c);
  cp('空名称被拒', !!errEmpty, `提示=${errEmpty}`);
  cp('与现有有效类别同名被拒', !!errDup && dupCnt === 1, `提示=${errDup}；有效同名数=${dupCnt}`);
  await shot(a.page, 'duplicate-rejected');
  await go(a.page, '/admin/categories');
  const r2 = a.page.locator(`[data-testid=category-row][data-category-id="${catId}"]`);
  await r2.getByTestId('category-rename-input').fill(yi);
  await submit(a.page, r2.getByTestId('category-rename'));
  const d1 = await openDetail(e, G);
  cp('改名后工单显示新名称', d1.category?.includes(yi) && !d1.category.includes(jia), `详情类别=${d1.category}`);
  await shot(e.page, 'renamed');
  await go(a.page, '/admin/categories');
  await submit(a.page, a.page.locator(`[data-testid=category-row][data-category-id="${catId}"]`).getByTestId('category-remove'));
  const dbc = (await q('SELECT name, active FROM category WHERE id=$1', [catId]))[0];
  cp('类别被移除（软删除）', dbc.active === false && dbc.name === yi, JSON.stringify(dbc));
  await shot(a.page, 'removed');
  await go(e.page, '/tickets/new');
  const opts2 = await e.page.locator('[data-testid=ticket-category] option').evaluateAll(os => os.map(o => o.value));
  cp('移除后提交页不可选该类别', !opts2.includes(String(catId)), `选项值=${opts2.join(',')}`);
  await shot(e.page, 'new-after-remove');
  const r = await searchForm(t, { categoryId: catId });
  cp('IT 同事按已移除类别筛选仍能找到历史工单', r.ids.includes(G) && eqSet(r.ids, await sqlFilter({ categoryId: catId })), `结果=${fmtIds(r.ids)}`);
  await shot(t.page, 'filter-removed');
  const d2 = await openDetail(t, G);
  cp('历史工单保留原类别', d2.category?.includes(yi), `详情类别=${d2.category}`);
}

// ================= IQA-009 =================
async function iqa009() {
  const H = await mkTicket({ title: `${P}身份变更测试`, categoryId: baseCat.id, submitterId: acc.E1.id });
  const a = await actor('管理员A0', acc.A0);
  const u = await actor('账号U1', acc.U1);
  cp('U1 初始为员工能力', JSON.stringify(await navSet(u.page)) === JSON.stringify(['nav-my-tickets', 'nav-new-ticket']), `导航=${(await navSet(u.page)).join(',')}`);
  const setRole = async role => {
    await go(a.page, '/admin/users');
    const row = a.page.locator(`[data-testid=user-row][data-login-name="${acc.U1.login}"]`);
    await row.getByTestId('role-select').selectOption(role);
    await submit(a.page, row.getByTestId('role-submit'));
    return (await row.getByTestId('user-row-role').textContent()).trim();
  };
  const r1 = await setRole('IT_STAFF');
  cp('管理员将 U1 设为 IT 同事', r1 === 'IT 同事', `账号列表身份=${r1}`);
  await shot(a.page, 'set-it');
  await go(u.page, '/tickets');
  const nav1 = await navSet(u.page);
  const all = await collectIds(u.page);
  cp('U1 下一次操作即具备 IT 同事能力并看到全部工单', JSON.stringify(nav1) === JSON.stringify(['nav-all-tickets']) && all.includes(H), `导航=${nav1.join(',')}；列表含 #${H}=${all.includes(H)}`);
  await go(u.page, `/tickets/${H}`);
  await submit(u.page, u.page.getByTestId('claim-button'));
  const d = await readDetail(u.page);
  cp('U1 认领工单', d.assignee === acc.U1.display, `处理人=${d.assignee}`);
  await shot(u.page, 'claimed');
  const r2 = await setRole('EMPLOYEE');
  cp('管理员取消 U1 的 IT 同事身份', r2 === '员工', `账号列表身份=${r2}`);
  await go(u.page, '/tickets');
  const nav2 = await navSet(u.page);
  const st = await go(u.page, `/tickets/${H}`);
  await go(u.page, '/tickets');
  const pc = await postForm(u, `/tickets/${H}/claim`, {});
  cp('取消后 U1 只具备员工能力', JSON.stringify(nav2) === JSON.stringify(['nav-my-tickets', 'nav-new-ticket']) && st === 403 && pc.status === 403,
    `导航=${nav2.join(',')}；访问他人工单 HTTP=${st}；认领 HTTP=${pc.status}`);
  await shot(u.page, 'back-to-employee');
  const t = await actor('IT同事T1', acc.T1);
  const dt = await openDetail(t, H);
  const opts = await t.page.locator('[data-testid=assign-select] option').evaluateAll(os => os.map(o => o.value));
  const db = (await q('SELECT assignee_id FROM ticket WHERE id=$1', [H]))[0];
  cp('U1 不在分派对象中', !opts.includes(String(acc.U1.id)), `选项=${opts.join(',')}`);
  cp('U1 认领过的工单仍显示 U1 为处理人', dt.assignee === acc.U1.display && num(db.assignee_id) === acc.U1.id, `处理人=${dt.assignee}；库 assignee_id=${db.assignee_id}`);
  await shot(t.page, 'history-assignee');
}

// ================= IQA-010 =================
async function iqa010() {
  for (const [label, account] of [['员工E1', acc.E1], ['IT同事T1', acc.T1]]) {
    const x = await actor(label, account);
    const nav = await navSet(x.page);
    cp(`${label} 导航无管理与报表入口`, !nav.some(n => ['nav-admin-users', 'nav-admin-categories', 'nav-report'].includes(n)), `导航=${nav.join(',')}`);
    await shot(x.page, `nav-${account.role}`);
    for (const p of ['/admin/users', '/admin/categories', '/admin/reports/weekly']) {
      const st = await go(x.page, p);
      const html = await x.page.content();
      const leak = ['user-row', 'category-row', 'report-week', 'report-backlog', acc.A0.login].filter(s => html.includes(s));
      cp(`${label} 直接访问 ${p} 被拒且无数据`, st === 403 && (await x.page.getByTestId('forbidden').count()) === 1 && leak.length === 0, `HTTP=${st}；泄露=${JSON.stringify(leak)}`);
      await shot(x.page, `forbidden-${p.split('/').pop()}`);
    }
    await go(x.page, '/tickets');
    const r1 = await postForm(x, `/admin/users/${acc.E2.id}/role`, { role: 'IT_STAFF' });
    const role = (await q('SELECT role FROM app_user WHERE id=$1', [acc.E2.id]))[0].role;
    cp(`${label} 直接设置 IT 同事被拒`, r1.status === 403 && role === 'EMPLOYEE', `HTTP=${r1.status}；E2 身份=${role}`);
    const cname = `${P}越权类别${account.role}`;
    const r2 = await postForm(x, '/admin/categories', { name: cname });
    const cc = num((await q('SELECT count(*) c FROM category WHERE name=$1', [cname]))[0].c);
    cp(`${label} 直接新增类别被拒`, r2.status === 403 && cc === 0, `HTTP=${r2.status}；库中=${cc}`);
    const lname = `${P}越权账号${account.role === 'EMPLOYEE' ? 'e' : 't'}`;
    const r3 = await postForm(x, '/admin/users', { loginName: lname, password: 'Pw-forbidden-1', displayName: 'x', role: 'ADMIN' });
    const uc = num((await q('SELECT count(*) c FROM app_user WHERE login_name=$1', [lname]))[0].c);
    cp(`${label} 直接创建账号被拒`, r3.status === 403 && uc === 0, `HTTP=${r3.status}；库中=${uc}`);
  }
}

// ================= IQA-011 =================
async function iqa011() {
  const A = await mkTicket({ title: `${P} 打印机卡纸`, description: '出纸口有纸张堵塞', categoryId: baseCat.id, submitterId: acc.E1.id });
  const B = await mkTicket({ title: `${P} 远程办公问题`, description: '在家时 VPN 连接断开，无法访问内网', categoryId: baseCat.id, submitterId: acc.E1.id });
  const C = await mkTicket({ title: `${P} 显示器闪烁`, description: '屏幕偶尔黑屏', categoryId: baseCat.id, submitterId: acc.E2.id });
  const t = await actor('IT同事T1', acc.T1);
  for (const [kw, inc, exc] of [['卡纸', [A], [B, C]], ['VPN 连接', [B], [A, C]], ['打印', [A], [B, C]], ['不存在的文字XYZ', [], [A, B, C]]]) {
    const r = await searchForm(t, { q: kw });
    const exp = await sqlFilter({ q: kw });
    const okEmpty = exp.length === 0 ? (r.empty && r.emptyText === '无匹配工单') : !r.empty;
    cp(`搜索「${kw}」`, eqSet(r.ids, exp) && inc.every(i => r.ids.includes(i)) && !exc.some(i => r.ids.includes(i)) && okEmpty,
      `结果=${fmtIds(r.ids)}；按标题/描述包含计算的期望=${fmtIds(exp)}；应含=${fmtIds(inc)}；不应含=${fmtIds(exc)}；空提示=${r.emptyText}`);
    await shot(t.page, `search-${kw}`);
  }
}

// ================= IQA-012 =================
async function iqa012() {
  const K1 = await mkCat(`${P}筛选类别一`), K2 = await mkCat(`${P}筛选类别二`);
  const now = Date.now();
  const kw = `${P}组合词`;
  const d20 = new Date(now - 20 * DAY), d40 = new Date(now - 40 * DAY), d0 = new Date(now - 60e3);
  const ev = (actor, to, at) => [{ actorId: actor, type: 'CLAIM', toAssignee: to, at }];
  const F = {
    F1: await mkTicket({ title: `${kw} 一`, categoryId: K1.id, submitterId: acc.E1.id, submittedAt: d0, status: 'OPEN' }),
    F2: await mkTicket({ title: `${kw} 二`, categoryId: K1.id, submitterId: acc.E2.id, submittedAt: d20, status: 'IN_PROGRESS', assigneeId: acc.T1.id, events: ev(acc.T1.id, acc.T1.id, d20) }),
    F3: await mkTicket({ title: `${P}其它 三`, categoryId: K2.id, submitterId: acc.E1.id, submittedAt: d40, status: 'RESOLVED', assigneeId: acc.T2.id,
      firstResolvedAt: new Date(d40.getTime() + HOUR), firstResolvedAssigneeId: acc.T2.id,
      events: [...ev(acc.T2.id, acc.T2.id, d40), { actorId: acc.T2.id, type: 'STATUS', fromStatus: 'OPEN', toStatus: 'RESOLVED', at: new Date(d40.getTime() + HOUR) }] }),
    F4: await mkTicket({ title: `${kw} 四`, categoryId: K2.id, submitterId: acc.E2.id, submittedAt: d20, status: 'OPEN', assigneeId: acc.T1.id, events: ev(acc.T1.id, acc.T1.id, d20) }),
    F5: await mkTicket({ title: `${kw} 五`, categoryId: K1.id, submitterId: acc.E1.id, submittedAt: d40, status: 'CLOSED' }),
    F6: await mkTicket({ title: `${P}其它 六`, categoryId: K1.id, submitterId: acc.E2.id, submittedAt: d0, status: 'OPEN', assigneeId: acc.T2.id, events: ev(acc.T2.id, acc.T2.id, d0) }),
  };
  const mine = Object.values(F);
  const t = await actor('IT同事T1', acc.T1);
  const from = shDate(now - 25 * DAY), to = shDate(now - 15 * DAY);
  const cases = [
    ['状态=待处理', { status: 'OPEN' }, ['F1', 'F4', 'F6']],
    ['类别=筛选类别一', { categoryId: K1.id }, ['F1', 'F2', 'F5', 'F6']],
    ['处理人=T1', { assignee: acc.T1.id }, ['F2', 'F4']],
    ['处理人=未分配', { assignee: 'unassigned' }, ['F1', 'F5']],
    [`提交时间 ${from}～${to}`, { from, to }, ['F2', 'F4']],
    ['关键词+状态+类别', { q: kw, status: 'OPEN', categoryId: K1.id }, ['F1']],
  ];
  const table = ['| 条件 | 期望（本次合成工单） | 实际（本次合成工单） | 期望（全库按条件计算） | 实际（页面） |', '|---|---|---|---|---|'];
  for (const [label, f, expKeys] of cases) {
    const r = await searchForm(t, f);
    const exp = await sqlFilter(f);
    const expMine = expKeys.map(k => F[k]);
    const gotMine = r.ids.filter(i => mine.includes(i));
    table.push(`| ${label} | ${fmtIds(expMine)} | ${fmtIds(gotMine)} | ${fmtIds(exp)} | ${fmtIds(r.ids)} |`);
    cp(`筛选 ${label}`, eqSet(gotMine, expMine) && eqSet(r.ids, exp), `本次合成工单：期望 ${fmtIds(expMine)} 实际 ${fmtIds(gotMine)}；全库期望 ${exp.length} 条，页面 ${r.ids.length} 条`);
    await shot(t.page, `filter-${label}`);
  }
  writeEvd('IQA-012-expected-vs-actual.md', `# IQA-012 预期集合对照表\n\n合成工单：${JSON.stringify(F)}\n\n${table.join('\n')}\n`);
  cur.files.push('IQA-012-expected-vs-actual.md');
  await searchForm(t, { q: kw, status: 'OPEN', categoryId: K1.id });
  await submit(t.page, t.page.getByTestId('filter-clear'));
  const u = new URL(t.page.url());
  const fields = [await t.page.getByTestId('filter-q').inputValue(), await t.page.getByTestId('filter-status').inputValue(), await t.page.getByTestId('filter-category').inputValue()];
  const ids = await collectIds(t.page);
  const all = await sqlFilter({});
  cp('清除条件后恢复完整列表', u.search === '' && fields.every(v => v === '') && eqSet(ids, all), `URL=${u}；条件=${JSON.stringify(fields)}；列表 ${ids.length} 条，全库 ${all.length} 条`);
  await shot(t.page, 'cleared');
}

// ================= IQA-013（无数据预检在全部场景前执行） =================
const r13 = {};
async function iqa013Prepare() {
  r13.Ka = await mkCat(`${P}报表类别A`);
  r13.Kb = await mkCat(`${P}报表类别B`);
  cur = { id: 'IQA-013-pre', checkpoints: [], failures: [], log: [], net: [], files: [], shotN: 0, server5xx: [], actors: [] };
  try {
    const a = await actor('管理员A0', acc.A0);
    const rep = await readReport(a);
    await shot(a.page, 'report-before-seed');
    const others = rep.weekStart ? await sqlWeekResolved(rep.weekStart, rep.weekEnd) : null;
    preReport = { rep, others, checkpoints: cur.checkpoints, failures: cur.failures, files: cur.files, log: cur.log, net: cur.net };
    for (const c of cur.actors) await c.ctx.close();
  } catch (e) {
    preReport = { error: e.message, checkpoints: [], failures: [], files: cur.files, log: cur.log, net: cur.net };
  }
}
async function iqa013() {
  cur.files.push(...(preReport?.files ?? [])); cur.net.push(...(preReport?.net ?? []));
  if (preReport?.error) cp('无数据预检', false, `预检执行中断：${preReport.error}`, 'gap');
  else {
    const { rep, others } = preReport;
    const rowA = rep.cats.find(c => c.id === r13.Ka.id), s1 = rep.staff.find(s => s.id === acc.S1.id);
    cp('本周无可计入工单的项显示 0', rowA?.count === 0 && s1?.count === 0, `新类别行=${JSON.stringify(rowA)}；新 IT 同事行=${JSON.stringify(s1)}`);
    if (others && others.n === 0) {
      cp('本周无首次已解决工单时平均处理时长显示「无数据」、未分配为 0', rep.avgText === '无数据' && rep.unassigned === 0, `平均=${rep.avgText}；未分配=${rep.unassigned}`);
    } else {
      cp('本周无首次已解决工单时平均处理时长显示「无数据」', false, `共享 test 库本周已有 ${others?.n} 张其他首次已解决工单，本次运行无法构造"本周无数据"的平均处理时长情形`, 'gap');
    }
  }
  const a = await actor('管理员A0', acc.A0);
  let rep = await readReport(a);
  need('周报表可打开并标明周起止日期', rep.httpStatus === 200 && rep.weekStart, `HTTP=${rep.httpStatus}；周=${rep.week}`);
  const ws = rep.weekStart.getTime(), now = Date.now();
  const startDow = new Date(ws + 8 * HOUR).getUTCDay();
  const endOk = shDate(ws + 6 * DAY) === rep.weekEndDate;
  cp('周起止日期为当前自然周（周一至周日）', startDow === 1 && endOk && now >= ws && now < ws + 7 * DAY, `周=${rep.week}；今天(公司时区)=${shDate(now)}`);
  const span = now - ws - 120e3;
  const tw = f => new Date(ws + Math.max(1000, span * f));
  const lw = days => new Date(ws - days * DAY);
  const { Ka, Kb } = r13; const { S1, S2 } = acc;
  const st = (actorId, from, to, at) => ({ actorId, type: 'STATUS', fromStatus: from, toStatus: to, at });
  const T = {};
  T.t1 = await mkTicket({ title: `${P}报表 本周提交未解决`, categoryId: Ka.id, submitterId: acc.E1.id, submittedAt: tw(0.10) });
  T.t2 = await mkTicket({ title: `${P}报表 本周提交本周解决`, categoryId: Ka.id, submitterId: acc.E1.id, submittedAt: tw(0.10), status: 'RESOLVED', assigneeId: S1.id,
    firstResolvedAt: tw(0.30), firstResolvedAssigneeId: S1.id, events: [{ actorId: S1.id, type: 'CLAIM', toAssignee: S1.id, at: tw(0.12) }, st(S1.id, 'OPEN', 'RESOLVED', tw(0.30))] });
  T.t3 = await mkTicket({ title: `${P}报表 无处理人解决`, categoryId: Kb.id, submitterId: acc.E2.id, submittedAt: tw(0.20), status: 'RESOLVED',
    firstResolvedAt: tw(0.40), firstResolvedAssigneeId: null, events: [st(S2.id, 'OPEN', 'RESOLVED', tw(0.40))] });
  T.t4 = await mkTicket({ title: `${P}报表 上周提交本周解决`, categoryId: Ka.id, submitterId: acc.E2.id, submittedAt: lw(3), status: 'RESOLVED', assigneeId: S2.id,
    firstResolvedAt: tw(0.50), firstResolvedAssigneeId: S2.id, events: [{ actorId: S2.id, type: 'CLAIM', toAssignee: S2.id, at: lw(2) }, st(S2.id, 'OPEN', 'RESOLVED', tw(0.50))] });
  T.t5 = await mkTicket({ title: `${P}报表 上周首次解决本周再次解决`, categoryId: Kb.id, submitterId: acc.E1.id, submittedAt: lw(5), status: 'RESOLVED', assigneeId: S1.id,
    firstResolvedAt: lw(4), firstResolvedAssigneeId: S1.id, events: [{ actorId: S1.id, type: 'CLAIM', toAssignee: S1.id, at: lw(5) }, st(S1.id, 'OPEN', 'RESOLVED', lw(4)),
      st(S1.id, 'RESOLVED', 'IN_PROGRESS', tw(0.15)), st(S1.id, 'IN_PROGRESS', 'RESOLVED', tw(0.25))] });
  T.t6 = await mkTicket({ title: `${P}报表 页面解决后改回再解决`, categoryId: Ka.id, submitterId: acc.E1.id, submittedAt: tw(0.05) });
  const s2 = await actor('IT同事S2', S2);
  const setStatus = async s => { await go(s2.page, `/tickets/${T.t6}`); await s2.page.getByTestId('status-select').selectOption(s); await submit(s2.page, s2.page.getByTestId('status-submit')); };
  await go(s2.page, `/tickets/${T.t6}`);
  await submit(s2.page, s2.page.getByTestId('claim-button'));
  await setStatus('RESOLVED');
  const fr1 = (await q('SELECT first_resolved_at, first_resolved_assignee_id FROM ticket WHERE id=$1', [T.t6]))[0];
  await setStatus('IN_PROGRESS');
  await setStatus('RESOLVED');
  const fr2 = (await q('SELECT first_resolved_at, first_resolved_assignee_id, submitted_at, status FROM ticket WHERE id=$1', [T.t6]))[0];
  cp('页面上解决→改回→再次解决不改变首次已解决时刻与归属', fr1.first_resolved_at && new Date(fr1.first_resolved_at).getTime() === new Date(fr2.first_resolved_at).getTime()
    && num(fr2.first_resolved_assignee_id) === S2.id && fr2.status === 'RESOLVED', `首次=${fr1.first_resolved_at?.toISOString?.()}；再次解决后=${fr2.first_resolved_at?.toISOString?.()}；归属=${fr2.first_resolved_assignee_id}`);
  await go(a.page, '/admin/categories');
  await submit(a.page, a.page.locator(`[data-testid=category-row][data-category-id="${Kb.id}"]`).getByTestId('category-remove'));
  const mine = Object.values(T);
  const durs = [[T.t2, tw(0.10), tw(0.30)], [T.t3, tw(0.20), tw(0.40)], [T.t4, lw(3), tw(0.50)], [T.t6, new Date(fr2.submitted_at), new Date(fr2.first_resolved_at)]]
    .map(([, s, r]) => (r.getTime() - s.getTime()) / 1000);
  rep = await readReport(a);
  const oth = await sqlWeekResolved(rep.weekStart, rep.weekEnd, mine);
  await shot(a.page, 'report');
  const expect = { Ka: 3, Kb: 1, S1: 1, S2: 2, unassigned: oth.un + 1 };
  const expAvg = (oth.sum + durs.reduce((x, y) => x + y, 0)) / (oth.n + durs.length);
  const ga = rep.cats.find(c => c.id === Ka.id), gb = rep.cats.find(c => c.id === Kb.id);
  const g1 = rep.staff.find(s => s.id === S1.id), g2 = rep.staff.find(s => s.id === S2.id);
  const calc = [
    '# IQA-013 合成数据清单与期望值计算表', '', `报表周：${rep.week}（weekStart=${rep.weekStart.toISOString()}）`, '',
    '| 工单 | 类别 | 提交时刻 | 首次已解决时刻 | 首次已解决时处理人 | 计入类别数量 | 计入处理量 | 计入平均时长 |', '|---|---|---|---|---|---|---|---|',
    `| #${T.t1} | A | ${tw(0.10).toISOString()} | — | — | A | 否 | 否 |`,
    `| #${T.t2} | A | ${tw(0.10).toISOString()} | ${tw(0.30).toISOString()} | S1 | A | S1 | 是 |`,
    `| #${T.t3} | B（本周已移除） | ${tw(0.20).toISOString()} | ${tw(0.40).toISOString()} | 无 | B | 未分配 | 是 |`,
    `| #${T.t4} | A | ${lw(3).toISOString()}（上周） | ${tw(0.50).toISOString()} | S2 | 否 | S2 | 是 |`,
    `| #${T.t5} | B | ${lw(5).toISOString()}（上周） | ${lw(4).toISOString()}（上周；本周再次解决） | S1 | 否 | 否 | 否 |`,
    `| #${T.t6} | A | ${new Date(fr2.submitted_at).toISOString()} | ${new Date(fr2.first_resolved_at).toISOString()}（页面操作） | S2 | A | S2 | 是 |`, '',
    `期望：类别A=${expect.Ka}，类别B=${expect.Kb}，S1=${expect.S1}，S2=${expect.S2}，未分配=其他工单 ${oth.un} + 1 = ${expect.unassigned}`,
    `平均处理时长期望 = (其他工单合计 ${oth.sum.toFixed(0)}s / ${oth.n} 张 + 本次 ${durs.map(x => x.toFixed(0)).join('+')}s) / ${oth.n + durs.length} = ${expAvg.toFixed(1)}s`, '',
    `实际：${JSON.stringify({ A: ga, B: gb, S1: g1, S2: g2, unassigned: rep.unassigned, avgText: rep.avgText, avgSec: rep.avgSec })}`,
  ];
  writeEvd('IQA-013-expected.md', calc.join('\n') + '\n');
  cur.files.push('IQA-013-expected.md');
  cp('每类别数量只计本周提交（含本周被移除的类别）', ga?.count === expect.Ka && gb?.count === expect.Kb && /已移除/.test(gb?.label ?? ''), `A=${JSON.stringify(ga)}；B=${JSON.stringify(gb)}`);
  cp('每位 IT 同事处理量只计本周首次解决并归属当时处理人', g1?.count === expect.S1 && g2?.count === expect.S2, `S1=${g1?.count}（期望 1）；S2=${g2?.count}（期望 2）`);
  cp('首次解决时无处理人的工单计入「未分配」', rep.unassigned === expect.unassigned, `未分配=${rep.unassigned}；期望=${expect.unassigned}`);
  const hm = rep.avgSec === null ? null : Math.round(rep.avgSec / 60);
  const textOk = hm !== null && rep.avgText.replace(/\s/g, '') === `${Math.floor(hm / 60)}小时${hm % 60}分钟`;
  cp('平均处理时长与按口径手工计算一致', rep.avgSec !== null && Math.abs(rep.avgSec - expAvg) <= 1.5 && textOk, `页面=${rep.avgText}（${rep.avgSec}s）；期望=${expAvg.toFixed(1)}s`);
}

// ================= IQA-014 =================
async function iqa014() {
  const now = Date.now();
  const ago = h => new Date(now - h * HOUR);
  const mk = (title, h, status, extra = {}) => mkTicket({ title: `${P}积压 ${title}`, categoryId: baseCat.id, submitterId: acc.E1.id, submittedAt: ago(h), status, ...extra });
  const B = {
    open100: await mk('超期待处理', 100, 'OPEN'),
    prog80: await mk('超期处理中', 80, 'IN_PROGRESS', { assigneeId: acc.T1.id, events: [{ actorId: acc.T1.id, type: 'CLAIM', toAssignee: acc.T1.id, at: ago(79) }] }),
    res90: await mk('超期已解决', 90, 'RESOLVED', { firstResolvedAt: ago(85), events: [{ actorId: acc.T1.id, type: 'STATUS', fromStatus: 'OPEN', toStatus: 'RESOLVED', at: ago(85) }] }),
    clo90: await mk('超期已关闭', 90, 'CLOSED', { events: [{ actorId: acc.T1.id, type: 'STATUS', fromStatus: 'OPEN', toStatus: 'CLOSED', at: ago(85) }] }),
    open71: await mk('未满72小时待处理', 71, 'OPEN'),
    prog70: await mk('未满72小时处理中', 70, 'IN_PROGRESS'),
    open240: await mk('本周之前提交未解决', 240, 'OPEN'),
  };
  const mine = Object.values(B);
  const mineOverdue = num((await q("SELECT count(*) c FROM ticket WHERE id = ANY($1::bigint[]) AND status IN ('OPEN','IN_PROGRESS') AND submitted_at < now() - interval '72 hours'", [mine]))[0].c);
  const a = await actor('管理员A0', acc.A0);
  const s1 = await sqlBacklog();
  let rep = await readReport(a);
  const s2 = await sqlBacklog();
  await shot(a.page, 'report-before');
  const othersBefore = num((await q("SELECT count(*) c FROM ticket WHERE NOT (id = ANY($1::bigint[])) AND status IN ('OPEN','IN_PROGRESS') AND submitted_at < now() - interval '72 hours'", [mine]))[0].c);
  writeEvd('IQA-014-expected.md', `# IQA-014 合成数据与期望值\n\n合成工单（id）：${JSON.stringify(B)}\n\n本次合成工单中应计入积压：超期待处理、超期处理中、本周之前提交未解决 = 3 张（实测按条件 ${mineOverdue}）\n其他工单积压数（全库按条件计算）：${othersBefore}\n期望积压数 = ${othersBefore} + 3 = ${othersBefore + 3}；读报表前后全库计算：${s1} / ${s2}；页面：${rep.backlog}\n`);
  cur.files.push('IQA-014-expected.md');
  cp('合成数据中恰有 3 张计入积压（不含已解决/已关闭、未满 72 小时）', mineOverdue === 3, `按条件计数=${mineOverdue}`);
  cp('积压数等于超过 72 小时且待处理/处理中的全部工单数（含本周之前提交）', [s1, s2].includes(rep.backlog) && rep.backlog === othersBefore + 3, `页面=${rep.backlog}；期望=${othersBefore}+3；全库计算=${s1}/${s2}`);
  const before = rep.backlog;
  const t = await actor('IT同事T1', acc.T1);
  await go(t.page, `/tickets/${B.open100}`);
  await t.page.getByTestId('status-select').selectOption('RESOLVED');
  await submit(t.page, t.page.getByTestId('status-submit'));
  const s3 = await sqlBacklog();
  rep = await readReport(a);
  const s4 = await sqlBacklog();
  await shot(a.page, 'report-after');
  cp('超期待处理工单改为已解决后积压数立即减一', rep.backlog === before - 1 && [s3, s4].includes(rep.backlog), `之前=${before}；之后=${rep.backlog}；全库计算=${s3}/${s4}`);
}

// ================= IQA-015 =================
async function iqa015() {
  const kBase = `q15-${DELIVERY.slice(-12).toLowerCase()}`;
  const snapRow = (await q("SELECT id, description, submitter_id FROM ticket WHERE title LIKE $1 ORDER BY id DESC LIMIT 1", [`${kBase}-%基线快照`]))[0];
  const pw = (K, nonce, login) => `Q15-${crypto.createHash('sha256').update(`${K}:${nonce}:${login}`).digest('hex').slice(0, 24)}`;
  const dbState = async (ticketIds, userIds, catId) => ({
    tickets: (await q(`SELECT id,title,description,category_id,urgency,submitter_id,submitted_at,status,assignee_id,first_resolved_at,first_resolved_assignee_id
      FROM ticket WHERE id = ANY($1::bigint[]) ORDER BY id`, [ticketIds])).map(r => JSON.stringify(r)),
    comments: (await q('SELECT id,ticket_id,author_id,author_role,body,created_at FROM ticket_comment WHERE ticket_id = ANY($1::bigint[]) ORDER BY id', [ticketIds])).map(r => JSON.stringify(r)),
    events: (await q(`SELECT id,ticket_id,actor_id,event_type,from_assignee_id,to_assignee_id,from_status,to_status,created_at FROM ticket_event
      WHERE ticket_id = ANY($1::bigint[]) ORDER BY id`, [ticketIds])).map(r => JSON.stringify(r)),
    users: (await q('SELECT id,login_name,display_name,role FROM app_user WHERE id = ANY($1::bigint[]) ORDER BY id', [userIds])).map(r => JSON.stringify(r)),
    category: JSON.stringify((await q('SELECT id,name,active FROM category WHERE id=$1', [catId]))[0]),
  });
  const digest = o => crypto.createHash('sha256').update(JSON.stringify(o)).digest('hex');
  const admin = await actor('管理员A0', acc.A0);

  if (!snapRow) {
    cp('查找重新部署前建立的基线', true, '未找到本交付的 IQA-015 基线，本次运行建立基线');
    const K = `${kBase}-${crypto.randomBytes(2).toString('hex')}`;
    const nonce = crypto.randomBytes(8).toString('hex');
    const emp = await mkUser(`${K}-emp`, `${K} 基线员工`, 'EMPLOYEE', pw(K, nonce, `${K}-emp`));
    const it = await mkUser(`${K}-it`, `${K} 基线IT`, 'IT_STAFF', pw(K, nonce, `${K}-it`));
    const cat = await mkCat(`${K}-类别`);
    const e = await actor('基线员工', emp);
    const t = await actor('基线IT', it);
    const ids = [];
    for (const [i, u] of [[1, 'LOW'], [2, 'MEDIUM'], [3, 'HIGH']]) {
      await go(e.page, '/tickets/new');
      await e.page.getByTestId('ticket-title').fill(`${K} 基线工单${i}`);
      await e.page.getByTestId('ticket-description').fill(`IQA-015 基线工单 ${i}：重新部署前后必须保持不变`);
      await e.page.getByTestId('ticket-category').selectOption(String(cat.id));
      await e.page.getByTestId('ticket-urgency').selectOption(u);
      await submit(e.page, e.page.getByTestId('ticket-submit'));
      ids.push(Number(/\/tickets\/(\d+)$/.exec(new URL(e.page.url()).pathname)[1]));
    }
    const comment = async (a, id, body) => { await go(a.page, `/tickets/${id}`); await a.page.getByTestId('comment-body').fill(body); await submit(a.page, a.page.getByTestId('comment-submit')); };
    const status = async (id, s) => { await go(t.page, `/tickets/${id}`); await t.page.getByTestId('status-select').selectOption(s); await submit(t.page, t.page.getByTestId('status-submit')); };
    await comment(e, ids[0], '基线补充说明一');
    await go(t.page, `/tickets/${ids[1]}`); await submit(t.page, t.page.getByTestId('claim-button'));
    await status(ids[1], 'IN_PROGRESS');
    await comment(t, ids[1], '基线回复二');
    await go(t.page, `/tickets/${ids[2]}`); await submit(t.page, t.page.getByTestId('claim-button'));
    await comment(e, ids[2], '基线补充说明三');
    await comment(t, ids[2], '基线回复三');
    await status(ids[2], 'CLOSED');
    const ui = {};
    for (const id of ids) ui[id] = await openDetail(e, id);
    const db = await dbState(ids, [emp.id, it.id], cat.id);
    const snapshot = { v: 1, K, nonce, createdAt: new Date().toISOString(), candidateSha: SHA, ticketIds: ids, users: [emp.id, it.id], categoryId: cat.id, ui, dbDigest: digest(db) };
    await go(e.page, '/tickets/new');
    await e.page.getByTestId('ticket-title').fill(`${K} 基线快照`);
    await e.page.getByTestId('ticket-description').fill(JSON.stringify(snapshot));
    await e.page.getByTestId('ticket-category').selectOption(String(cat.id));
    await e.page.getByTestId('ticket-urgency').selectOption('LOW');
    await submit(e.page, e.page.getByTestId('ticket-submit'));
    const ok = /\/tickets\/\d+$/.test(new URL(e.page.url()).pathname);
    writeEvd('IQA-015-baseline-snapshot.json', JSON.stringify({ ...snapshot, nonce: '（见快照工单）', db }, null, 2));
    cur.files.push('IQA-015-baseline-snapshot.json');
    await shot(e.page, 'baseline-created');
    cp('建立供下一迭代核对的基线（账号、类别、工单、沟通记录、处理记录）', ok, `基线 ${K}，工单 ${fmtIds(ids)}，候选 ${SHA}`, 'info');
    return;
  }

  let snap;
  try { snap = JSON.parse(snapRow.description); } catch (e) { need('读取基线快照', false, `快照工单 #${snapRow.id} 描述不是有效 JSON：${e.message}`); }
  cp('查找重新部署前建立的基线', true, `基线 ${snap.K}，建立于 ${snap.createdAt}，建立时候选 ${snap.candidateSha}，快照工单 #${snapRow.id}`);
  const users = await q('SELECT id, login_name, display_name, role FROM app_user WHERE id = ANY($1::bigint[]) ORDER BY id', [snap.users]);
  const accounts = users.map(u => ({ id: num(u.id), login: u.login_name, display: u.display_name, role: u.role, password: pw(snap.K, snap.nonce, u.login_name) }));
  cp('基线账号仍存在', accounts.length === 2, `账号=${JSON.stringify(users)}`);
  const emp = accounts.find(x => x.role === 'EMPLOYEE'), it = accounts.find(x => x.role === 'IT_STAFF');
  const e = await actor('基线员工');
  const t = await actor('基线IT');
  const okE = emp ? await login(e, emp) : false;
  const okT = it ? await login(t, it) : false;
  cp('基线账号可用原凭据登录且身份不变', okE && okT && JSON.stringify(await navSet(e.page)) === JSON.stringify(['nav-my-tickets', 'nav-new-ticket'])
    && JSON.stringify(await navSet(t.page)) === JSON.stringify(['nav-all-tickets']), `员工登录=${okE}；IT 登录=${okT}`);
  if (okE) {
    for (const id of snap.ticketIds) {
      const d = await openDetail(e, id);
      const exp = snap.ui[id];
      cp(`工单 #${id} 详情（状态、处理人、提交时间、沟通记录、处理记录）与快照一致`, JSON.stringify(d) === JSON.stringify(exp), `当前=${JSON.stringify(d).slice(0, 500)}；快照=${JSON.stringify(exp).slice(0, 500)}`);
      if (okT) { const dt = await openDetail(t, id); cp(`IT 同事视角工单 #${id} 一致`, JSON.stringify({ ...dt }) === JSON.stringify(exp), `IT 视角状态=${dt.status} 处理人=${dt.assignee}`); }
    }
    await shot(e.page, 'baseline-detail');
    await go(e.page, '/tickets');
    const mine = await collectIds(e.page);
    cp('基线员工「我的工单」完整', eqSet(mine, [...snap.ticketIds, num(snapRow.id)]), `列表=${fmtIds(mine)}`);
  }
  const db = await dbState(snap.ticketIds, snap.users, snap.categoryId);
  cp('数据库中基线数据与快照摘要一致', digest(db) === snap.dbDigest, `当前摘要=${digest(db)}；快照摘要=${snap.dbDigest}`);
  writeEvd('IQA-015-after.json', JSON.stringify(db, null, 2)); cur.files.push('IQA-015-after.json');
  await go(admin.page, '/admin/users');
  const rows = [];
  for (const u of accounts) {
    const r = admin.page.locator(`[data-testid=user-row][data-login-name="${u.login}"]`);
    rows.push((await r.count()) ? `${(await r.getByTestId('user-row-display').textContent()).trim()}/${(await r.getByTestId('user-row-role').textContent()).trim()}` : null);
  }
  cp('账号管理中基线账号姓名与身份不变', rows.every((x, i) => x === `${accounts[i].display}/${ROLE[accounts[i].role]}`), JSON.stringify(rows));
  await go(admin.page, '/admin/categories');
  const crow = admin.page.locator(`[data-testid=category-row][data-category-id="${snap.categoryId}"]`);
  cp('基线类别仍存在且名称不变', (await crow.count()) === 1 && (await crow.getAttribute('data-category-name')) === `${snap.K}-类别`, `类别行数=${await crow.count()}`);
  const rep = await readReport(admin);
  await shot(admin.page, 'report');
  const oth = await sqlWeekResolved(rep.weekStart, rep.weekEnd);
  const bl = await sqlBacklog();
  const expAvg = oth.n ? oth.sum / oth.n : null;
  cp('报表按持久化数据计算的处理量、平均处理时长、积压一致', (expAvg === null ? rep.avgText === '无数据' : Math.abs(rep.avgSec - expAvg) <= 1.5) && rep.unassigned === oth.un && rep.backlog === bl,
    `平均=${rep.avgText}/${rep.avgSec}（按数据计算 ${expAvg}）；未分配=${rep.unassigned}（${oth.un}）；积压=${rep.backlog}（${bl}）`);
  cp('记录基线建立时与当前的候选 SHA（IQA-015 在后续迭代判定）', true, `基线建立于候选 ${snap.candidateSha}，当前候选 ${SHA}`, 'info');
}

// ---------------- 主流程 ----------------
async function main() {
  try {
    await q('SELECT 1');
  } catch (e) {
    envProblem = `无法连接 ticket-db test 实例：${e.message}`;
  }
  if (!envProblem) {
    browser = await chromium.launch();
    try {
      await setup();
      await iqa013Prepare();
      const list = [
        ['IQA-001', '管理员创建账号与登录', iqa001], ['IQA-002', '员工提交工单与必填校验', iqa002], ['IQA-003', '员工查看进度与工单隔离', iqa003],
        ['IQA-004', '补充说明与回复的双向沟通', iqa004], ['IQA-005', 'IT 同事查看全部工单、认领与分派', iqa005], ['IQA-006', '状态修改', iqa006],
        ['IQA-007', '员工无法执行处理操作', iqa007], ['IQA-008', '管理员维护类别', iqa008], ['IQA-009', '管理员设置与取消 IT 同事', iqa009],
        ['IQA-010', '管理功能与报表的访问控制', iqa010], ['IQA-011', '关键词搜索', iqa011], ['IQA-012', '条件筛选与组合', iqa012],
        ['IQA-013', '周报表数量与处理时长', iqa013], ['IQA-014', '超过 3 天未解决积压数', iqa014],
      ];
      for (const [id, title, fn] of list) await scenario(id, title, fn);
      await maintainDeferredBaseline();
    } catch (e) {
      rlog(`准备阶段失败：${e.stack ?? e}`);
      if (isEnvError(String(e.message))) envProblem = envProblem ?? `准备阶段：${e.message}`;
      else testGaps.push(`QA 脚本前置准备失败（合成数据/浏览器）：${String(e.message).slice(0, 400)}｜证据：run.log`);
    } finally {
      try { await browser.close(); } catch { /* ignore */ }
      try { await cleanupPrefix(P); rlog(`已清理本次运行合成数据 ${P}`); } catch (e) { rlog(`清理失败：${e.message}`); }
    }
  }
  try { await pool.end(); } catch { /* ignore */ }
  writeEvd('node-run.log', runLog.join('\n'));
  let files = [];
  try { files = fs.readdirSync(EVD).sort(); } catch { /* ignore */ }
  const evidence = ['identity.json', 'health-attempts.log', 'node-run.log', ...files.filter(f => /^IQA-\d{3}\.log$/.test(f)),
    DEFERRED_NOTE, ...deferredNotes, ...(files.includes('IQA-015-deferred-baseline.log') ? ['IQA-015-deferred-baseline.log'] : [])];
  let finalDefects = defects, finalGaps = testGaps;
  const deferredSummary = '；IQA-015 按人工澄清答案 HT-DLV-20261116-72F2B9041E74-0010 延后到后续迭代验证，本次未执行';
  if (envProblem) { evidence.unshift(`执行环境问题：${envProblem}`); finalDefects = []; finalGaps = []; }
  const allPassed = !envProblem && scenarios.length === EXPECTED_SCENARIOS && scenarios.every(s => s.passed) && finalDefects.length === 0 && finalGaps.length === 0;
  const failed = scenarios.filter(s => !s.passed).map(s => s.id);
  const result = {
    passed: allPassed,
    testedCommitSha: SHA,
    environmentUrl: process.env.QA_BASE_URL,
    summary: envProblem ? `执行环境问题导致未完成验证：${envProblem}`.slice(0, 500)
      : `执行 ${scenarios.length}/${EXPECTED_SCENARIOS} 个场景，通过 ${scenarios.filter(s => s.passed).length} 个${failed.length ? `；未通过：${failed.join('、')}` : ''}；产品缺陷 ${finalDefects.length} 项，测试缺口 ${finalGaps.length} 项${deferredSummary}`,
    defects: finalDefects,
    testGaps: finalGaps,
    evidence,
    bugfixResults: [],
    scenarios,
  };
  let json = JSON.stringify(result, null, 2);
  if (Buffer.byteLength(json) > 250000) {
    for (const s of result.scenarios) s.checkpoints = s.checkpoints.map(c => ({ ...c, observation: c.observation.slice(0, 200) }));
    json = JSON.stringify(result);
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
