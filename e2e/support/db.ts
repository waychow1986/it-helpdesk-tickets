import { DefaultAzureCredential } from '@azure/identity';
import bcrypt from 'bcryptjs';
import pg from 'pg';

/**
 * 功能测试数据准备：以 Entra 令牌（受众 ossrdbms-aad）连接真实 ticket-db dev 实例（sslmode=require 等价的 TLS 校验）。
 * 只从 AIDH_DEP_TICKET_DB_* 读取连接信息；所有合成数据带运行前缀，结束时按前缀清理。
 */
const SCOPE = 'https://ossrdbms-aad.database.windows.net/.default';
const credential = new DefaultAzureCredential();
let pool: pg.Pool | undefined;

function requireEnv(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`缺少环境变量 ${name}`);
  return value;
}

export function db(): pg.Pool {
  if (!pool) {
    pool = new pg.Pool({
      host: requireEnv('AIDH_DEP_TICKET_DB_HOST'),
      database: requireEnv('AIDH_DEP_TICKET_DB_DATABASE'),
      user: requireEnv('AIDH_DEP_TICKET_DB_USER'),
      port: Number(requireEnv('AIDH_DEP_TICKET_DB_PORT')),
      ssl: { rejectUnauthorized: true },
      max: 3,
      password: async () => (await credential.getToken(SCOPE)).token,
    });
  }
  return pool;
}

export async function closeDb(): Promise<void> {
  if (pool) {
    await pool.end();
    pool = undefined;
  }
}

export async function query<T extends pg.QueryResultRow = any>(sql: string, params: unknown[] = []): Promise<T[]> {
  return (await db().query<T>(sql, params)).rows;
}

export type Role = 'EMPLOYEE' | 'IT_STAFF' | 'ADMIN';
export type Status = 'OPEN' | 'IN_PROGRESS' | 'RESOLVED' | 'CLOSED';

export interface SeedUser { id: number; login: string; password: string; display: string; role: Role }

export async function createUser(login: string, password: string, display: string, role: Role): Promise<SeedUser> {
  const hash = await bcrypt.hash(password, 10);
  const rows = await query<{ id: string }>(
    'INSERT INTO app_user(login_name, password_hash, display_name, role, created_at) VALUES ($1, $2, $3, $4, now()) RETURNING id',
    [login, hash, display, role]);
  return { id: Number(rows[0].id), login, password, display, role };
}

/** 以运行前缀在 public schema 插入种子管理员（不依赖、不修改库中其他管理员）。 */
export async function seedAdmin(prefix: string, password: string): Promise<SeedUser> {
  return createUser(`${prefix}admin`, password, `${prefix}种子管理员`, 'ADMIN');
}

export async function createCategory(name: string): Promise<number> {
  const rows = await query<{ id: string }>(
    'INSERT INTO category(name, active, created_at) VALUES ($1, TRUE, now()) RETURNING id', [name]);
  return Number(rows[0].id);
}

export async function removeCategory(id: number): Promise<void> {
  await query('UPDATE category SET active = FALSE, removed_at = now() WHERE id = $1', [id]);
}

export interface TicketSeed {
  title: string;
  description?: string;
  categoryId: number;
  urgency?: 'LOW' | 'MEDIUM' | 'HIGH';
  submitterId: number;
  submittedAt?: Date;
  status?: Status;
  assigneeId?: number | null;
  /** 首次已解决时刻与当时处理人；同时写入对应 STATUS 事件，保持与应用写入一致的语义。 */
  firstResolvedAt?: Date | null;
  firstResolvedAssigneeId?: number | null;
  actorId?: number;
}

export async function insertTicket(t: TicketSeed): Promise<number> {
  const submittedAt = t.submittedAt ?? new Date();
  const rows = await query<{ id: string }>(
    `INSERT INTO ticket(title, description, category_id, urgency, submitter_id, submitted_at, status, assignee_id,
       first_resolved_at, first_resolved_assignee_id, updated_at)
     VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $6) RETURNING id`,
    [t.title, t.description ?? '合成描述', t.categoryId, t.urgency ?? 'MEDIUM', t.submitterId, submittedAt,
      t.status ?? 'OPEN', t.assigneeId ?? null, t.firstResolvedAt ?? null, t.firstResolvedAssigneeId ?? null]);
  const id = Number(rows[0].id);
  if (t.firstResolvedAt) {
    if (!t.actorId) throw new Error('写入首次已解决时需要 actorId');
    await query(
      `INSERT INTO ticket_event(ticket_id, actor_id, event_type, from_status, to_status, created_at)
       VALUES ($1, $2, 'STATUS', 'OPEN', 'RESOLVED', $3)`, [id, t.actorId, t.firstResolvedAt]);
  }
  return id;
}

/** V1 持久升级基线前缀（TD-034）：任何测试都不得修改或清理该前缀的数据。 */
export const V1_BASELINE_PREFIX = 'q15-72f2b9041e74-';

/** 清理前缀必须是本次运行前缀，且不得与 V1 持久升级基线前缀相互匹配。 */
export function assertSafePrefix(prefix: string): string {
  if (!/^e2e-\d+-[0-9a-f]+-$/.test(prefix ?? '')) {
    throw new Error('清理前缀不合法（只接受 e2e-<时间>-<随机>- 形式的运行前缀）');
  }
  if (prefix.startsWith(V1_BASELINE_PREFIX) || V1_BASELINE_PREFIX.startsWith(prefix)) {
    throw new Error('清理前缀与 V1 持久升级基线前缀冲突');
  }
  return prefix;
}

export type Urgency = 'LOW' | 'MEDIUM' | 'HIGH';

/** 期望解决时长（小时），测试独立定义：高 4、中 24、低 72（BQ-001 人工答案 A）。 */
export const RESOLUTION_HOURS: Record<Urgency, number> = { HIGH: 4, MEDIUM: 24, LOW: 72 };

/** 独立计算期望解决时间 = 提交时间 + 4/24/72 小时（自然时间）。 */
export function expectedResolveAt(submittedAt: Date, urgency: Urgency): Date {
  return new Date(submittedAt.getTime() + RESOLUTION_HOURS[urgency] * 3600 * 1000);
}

/** 读取工单的提交时间与紧急程度（数据库原值）。 */
export async function ticketBasics(id: number): Promise<{ submittedAt: Date; urgency: Urgency; status: Status }> {
  const rows = await query<{ submitted_at: Date; urgency: Urgency; status: Status }>(
    'SELECT submitted_at, urgency, status FROM ticket WHERE id = $1', [id]);
  if (rows.length !== 1) throw new Error(`工单 ${id} 不存在`);
  return { submittedAt: rows[0].submitted_at, urgency: rows[0].urgency, status: rows[0].status };
}

/** 追加一条状态处理记录（与应用写入语义一致：再次解决不改首次已解决时刻）。 */
export async function appendStatusEvent(ticketId: number, actorId: number, from: Status, to: Status, at: Date):
  Promise<void> {
  await query(`INSERT INTO ticket_event(ticket_id, actor_id, event_type, from_status, to_status, created_at)
    VALUES ($1, $2, 'STATUS', $3, $4, $5)`, [ticketId, actorId, from, to, at]);
}

/**
 * 按 FR-017 口径独立计算“本周超时解决的工单数量”：首次已解决时刻在 [weekStart, weekStart+7d) 内且严格晚于
 * 提交时间 + 4/24/72 小时（时长在此独立写出，不复用应用 SQL）。可选 ids 时只统计这些工单并返回计入的 id。
 */
export async function overdueResolvedIds(weekStart: Date, ids?: number[]): Promise<number[]> {
  const weekEnd = new Date(weekStart.getTime() + 7 * 86400 * 1000);
  const rows = await query<{ id: string }>(
    `SELECT id FROM ticket WHERE first_resolved_at >= $1 AND first_resolved_at < $2
       AND first_resolved_at > submitted_at + CASE urgency WHEN 'HIGH' THEN interval '4 hours'
         WHEN 'MEDIUM' THEN interval '1 day' ELSE interval '3 days' END
       AND ($3::bigint[] IS NULL OR id = ANY($3::bigint[])) ORDER BY id`,
    [weekStart, weekEnd, ids ?? null]);
  return rows.map(r => Number(r.id));
}

/** 按"数据库数据契约"顺序清理本次运行前缀的数据（不匹配 V1 持久升级基线前缀）。 */
export async function cleanup(prefix: string): Promise<void> {
  const p = `${assertSafePrefix(prefix)}%`;
  const users = '(SELECT id FROM app_user WHERE login_name LIKE $1)';
  const categories = '(SELECT id FROM category WHERE name LIKE $1)';
  const tickets = `(SELECT id FROM ticket WHERE submitter_id IN ${users} OR category_id IN ${categories}
    OR assignee_id IN ${users} OR first_resolved_assignee_id IN ${users})`;
  await query(`DELETE FROM ticket_event WHERE ticket_id IN ${tickets} OR actor_id IN ${users}
    OR from_assignee_id IN ${users} OR to_assignee_id IN ${users}`, [p]);
  await query(`DELETE FROM ticket_comment WHERE ticket_id IN ${tickets} OR author_id IN ${users}`, [p]);
  await query(`DELETE FROM ticket WHERE id IN ${tickets}`, [p]);
  await query('DELETE FROM category WHERE name LIKE $1', [p]);
  await query(`UPDATE app_user SET created_by = NULL WHERE created_by IN ${users}`, [p]);
  await query('DELETE FROM app_user WHERE login_name LIKE $1', [p]);
}
