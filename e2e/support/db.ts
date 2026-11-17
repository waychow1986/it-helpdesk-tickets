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

/** 按"数据库数据契约"顺序清理本次运行前缀的数据。 */
export async function cleanup(prefix: string): Promise<void> {
  const p = `${prefix}%`;
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
