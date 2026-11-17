import { mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { query } from './db';

/**
 * 升级演练快照与比对工具（TD-030、IQA-009、IQA-010）：只操作 e2e_upgrade_ 前缀的一次性 schema；
 * 快照把每列转为 text 后按主键排序（保留完整精度）；比对差异只输出表名、id 与列名，不输出任何值。
 * 快照与期望值保存在运行时临时目录（E2E_UPGRADE_STATE_DIR），不写入仓库。
 */
const UPGRADE_SCHEMA = /^e2e_upgrade_[a-z0-9_]{1,51}$/;

export const V1_COLUMNS: Record<string, string[]> = {
  app_user: ['id', 'login_name', 'password_hash', 'display_name', 'role', 'created_at', 'created_by'],
  category: ['id', 'name', 'active', 'created_at', 'removed_at'],
  ticket: ['id', 'title', 'description', 'category_id', 'urgency', 'submitter_id', 'submitted_at', 'status',
    'assignee_id', 'first_resolved_at', 'first_resolved_assignee_id', 'updated_at'],
  ticket_comment: ['id', 'ticket_id', 'author_id', 'author_role', 'body', 'created_at'],
  ticket_event: ['id', 'ticket_id', 'actor_id', 'event_type', 'from_assignee_id', 'to_assignee_id', 'from_status',
    'to_status', 'created_at'],
};

/** V1 列 + V2 新增的 app_user.active（全部数据比对用）。 */
export const ALL_COLUMNS_V2: Record<string, string[]> = { ...V1_COLUMNS,
  app_user: [...V1_COLUMNS.app_user, 'active'] };

export type Row = Record<string, string | null>;
export type Snapshot = Record<string, Row[]>;

export function upgradeSchema(): string {
  const name = process.env.E2E_UPGRADE_SCHEMA;
  if (!name || !UPGRADE_SCHEMA.test(name)) {
    throw new Error('升级演练 schema 名称不合法（只接受 e2e_upgrade_ 前缀的小写字母数字下划线名）');
  }
  return name;
}

/** 升级演练账号：登录名带运行前缀，密码由进程环境中的随机值派生（不输出）。 */
export const UPGRADE_ACCOUNTS = ['admin', 'emp1', 'emp2', 'it1', 'it2', 'former'] as const;
export type AccountKey = typeof UPGRADE_ACCOUNTS[number];

export function upgradeAccount(key: AccountKey): { login: string; password: string } {
  const secret = process.env.E2E_UPGRADE_PASSWORD;
  if (!secret) throw new Error('缺少运行时随机值 E2E_UPGRADE_PASSWORD');
  return { login: `${process.env.E2E_RUN_PREFIX}up-${key}`, password: `${secret}-${key}` };
}

export async function snapshot(schema: string, columns: Record<string, string[]> = V1_COLUMNS): Promise<Snapshot> {
  const result: Snapshot = {};
  for (const [table, cols] of Object.entries(columns)) {
    result[table] = await query<Row>(
      `SELECT ${cols.map(c => `${c}::text AS ${c}`).join(', ')} FROM ${schema}.${table} ORDER BY id`);
  }
  return result;
}

/** 逐行逐列比对；返回差异描述（表名#id.列名），不含值。 */
export function diff(before: Snapshot, after: Snapshot): string[] {
  const out: string[] = [];
  for (const table of Object.keys(before)) {
    const a = new Map(before[table].map(r => [r.id, r]));
    const b = new Map((after[table] ?? []).map(r => [r.id, r]));
    for (const [id, left] of a) {
      const right = b.get(id);
      if (!right) {
        out.push(`${table}#${id} 缺失`);
        continue;
      }
      for (const column of Object.keys(left)) {
        if (left[column] !== right[column]) out.push(`${table}#${id}.${column} 不同`);
      }
    }
    for (const id of b.keys()) {
      if (!a.has(id)) out.push(`${table}#${id} 新增`);
    }
  }
  return out;
}

export async function history(schema: string): Promise<Row[]> {
  return query<Row>(`SELECT installed_rank::text AS installed_rank, version, description, type, script,
    checksum::text AS checksum, installed_on::text AS installed_on, success::text AS success
    FROM ${schema}.flyway_schema_history ORDER BY installed_rank`);
}

export async function columnExists(schema: string, table: string, column: string): Promise<boolean> {
  const rows = await query('SELECT 1 FROM information_schema.columns WHERE table_schema = $1 AND table_name = $2 '
    + 'AND column_name = $3', [schema, table, column]);
  return rows.length === 1;
}

export async function dropUpgradeSchema(schema: string): Promise<void> {
  await query(`DROP SCHEMA IF EXISTS ${schema} CASCADE`);
}

export async function schemaExists(schema: string): Promise<boolean> {
  return (await query('SELECT 1 FROM pg_namespace WHERE nspname = $1', [schema])).length > 0;
}

// ---------------- 运行时状态（临时目录） ----------------

function stateDir(): string {
  const dir = process.env.E2E_UPGRADE_STATE_DIR;
  if (!dir) throw new Error('缺少 E2E_UPGRADE_STATE_DIR');
  mkdirSync(dir, { recursive: true });
  return dir;
}

export function saveState(name: string, value: unknown): void {
  writeFileSync(join(stateDir(), `${name}.json`), JSON.stringify(value), { mode: 0o600 });
}

export function loadState<T>(name: string): T {
  return JSON.parse(readFileSync(join(stateDir(), `${name}.json`), 'utf8')) as T;
}

export function clearState(): void {
  if (process.env.E2E_UPGRADE_STATE_DIR) rmSync(process.env.E2E_UPGRADE_STATE_DIR, { recursive: true, force: true });
}

// ---------------- 独立口径计算（不复用应用 SQL） ----------------

export const HOURS: Record<string, number> = { HIGH: 4, MEDIUM: 24, LOW: 72 };

/** 公司时区（Asia/Shanghai）当前自然周的周一 00:00 时刻。 */
export function weekStartOf(now: Date): Date {
  const offsetMs = 8 * 3600 * 1000;
  const local = new Date(now.getTime() + offsetMs);
  const day = (local.getUTCDay() + 6) % 7;
  return new Date(Date.UTC(local.getUTCFullYear(), local.getUTCMonth(), local.getUTCDate() - day) - offsetMs);
}

export interface V1Report {
  categories: Record<string, number>;
  staff: Record<string, number>;
  unassigned: number;
  avgSeconds: string;
  backlog: number;
}

/** 按 V1 口径对 schema 中的数据独立计算周报表四项（公司时区周一 00:00 起的自然周）。 */
export async function v1Report(schema: string, weekStart: Date, now: Date): Promise<V1Report> {
  const weekEnd = new Date(weekStart.getTime() + 7 * 86400 * 1000);
  const categories: Record<string, number> = {};
  for (const r of await query<{ id: string; n: string }>(
    `SELECT c.id::text AS id, COUNT(t.id)::text AS n FROM ${schema}.category c LEFT JOIN ${schema}.ticket t
       ON t.category_id = c.id AND t.submitted_at >= $1 AND t.submitted_at < $2
     GROUP BY c.id HAVING c.active OR COUNT(t.id) > 0`, [weekStart, weekEnd])) {
    categories[r.id] = Number(r.n);
  }
  const staff: Record<string, number> = {};
  for (const r of await query<{ id: string; n: string }>(
    `SELECT u.id::text AS id, COUNT(t.id)::text AS n FROM ${schema}.app_user u LEFT JOIN ${schema}.ticket t
       ON t.first_resolved_assignee_id = u.id AND t.first_resolved_at >= $1 AND t.first_resolved_at < $2
     GROUP BY u.id HAVING u.role = 'IT_STAFF' OR COUNT(t.id) > 0`, [weekStart, weekEnd])) {
    staff[r.id] = Number(r.n);
  }
  const [row] = await query<{ unassigned: string; avg: string | null; backlog: string }>(
    `SELECT (SELECT COUNT(*) FROM ${schema}.ticket WHERE first_resolved_assignee_id IS NULL
               AND first_resolved_at >= $1 AND first_resolved_at < $2)::text AS unassigned,
            (SELECT ROUND(AVG(EXTRACT(EPOCH FROM first_resolved_at - submitted_at)))::text FROM ${schema}.ticket
               WHERE first_resolved_at >= $1 AND first_resolved_at < $2) AS avg,
            (SELECT COUNT(*) FROM ${schema}.ticket WHERE status IN ('OPEN','IN_PROGRESS')
               AND submitted_at < $3::timestamptz - interval '72 hours')::text AS backlog`,
    [weekStart, weekEnd, now]);
  return { categories, staff, unassigned: Number(row.unassigned), avgSeconds: row.avg ?? '',
    backlog: Number(row.backlog) };
}

/** 按 FR-017 口径独立计算 schema 中“本周超时解决的工单数量”。 */
export async function overdueResolved(schema: string, weekStart: Date): Promise<number> {
  const weekEnd = new Date(weekStart.getTime() + 7 * 86400 * 1000);
  const [row] = await query<{ n: string }>(
    `SELECT COUNT(*)::text AS n FROM ${schema}.ticket WHERE first_resolved_at >= $1 AND first_resolved_at < $2
       AND first_resolved_at > submitted_at + CASE urgency WHEN 'HIGH' THEN interval '4 hours'
         WHEN 'MEDIUM' THEN interval '1 day' ELSE interval '3 days' END`, [weekStart, weekEnd]);
  return Number(row.n);
}
