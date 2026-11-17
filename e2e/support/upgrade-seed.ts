import { expect, test as setup } from '@playwright/test';
import bcrypt from 'bcryptjs';
import { closeDb, query } from './db';
import { UPGRADE_ACCOUNTS, columnExists, history, saveState, snapshot, upgradeAccount, upgradeSchema, v1Report,
  weekStartOf } from './upgrade-snapshot';

/**
 * 升级演练第 ② 步（TD-030）：向已由 V2 jar 以 --spring.flyway.target=1 构造为 V1 结构的一次性 schema，
 * 按 V1 数据契约以 SQL 写入与 UpgradeFixtures 覆盖面相同的 V1 合成数据集：员工 / IT 同事 / 管理员各至少一个
 * （密码为进程环境中随机值派生，BCrypt 哈希）、被取消 IT 同事身份的账号、有效 / 已移除 / 改名类别、
 * 三种紧急程度 × 四种状态 × 有无处理人、已超期与未超期的未解决工单、含补充说明 / 回复 / 认领 / 分派 / 状态事件的工单、
 * 本周首次已解决工单；随后保存 V1 全列快照与按 V1 口径计算的报表四项期望值到临时目录。
 */
const URGENCIES = ['HIGH', 'MEDIUM', 'LOW'] as const;
const HOURS = [4, 24, 72];
const STATUSES = ['OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED'] as const;
const H = 3600 * 1000;

setup('以 SQL 写入 V1 合成数据集并保存升级前快照', async () => {
  const schema = upgradeSchema();
  const prefix = `${process.env.E2E_RUN_PREFIX}up-`;
  // 前置：schema 为 V1 结构（迁移历史只有版本 1，app_user 尚无 active 列）
  const before = await history(schema);
  expect(before.filter(r => r.type === 'SQL').map(r => r.version)).toEqual(['1']);
  expect(await columnExists(schema, 'app_user', 'active')).toBe(false);
  expect(Number((await query(`SELECT COUNT(*) AS n FROM ${schema}.app_user`))[0].n)).toBe(0);

  const now = new Date();
  const created = new Date(now.getTime() - 40 * 24 * H);
  const users: [string, string, string][] = [
    ['admin', 'ADMIN', 'V1 管理员'], ['emp1', 'EMPLOYEE', 'V1 员工甲'], ['emp2', 'EMPLOYEE', 'V1 员工乙'],
    ['it1', 'IT_STAFF', 'V1 IT 甲'], ['it2', 'IT_STAFF', 'V1 IT 乙'], ['former', 'EMPLOYEE', 'V1 前 IT 同事']];
  const ids: Record<string, number> = {};
  expect(users.map(u => u[0])).toEqual([...UPGRADE_ACCOUNTS]);
  for (const [key, role, display] of users) {
    const account = upgradeAccount(key as typeof UPGRADE_ACCOUNTS[number]);
    const [row] = await query<{ id: string }>(
      `INSERT INTO ${schema}.app_user(login_name, password_hash, display_name, role, created_at, created_by)
       VALUES ($1, $2, $3, $4, $5, $6) RETURNING id`,
      [account.login, await bcrypt.hash(account.password, 10), prefix + display, role, created,
        role === 'ADMIN' ? null : ids.admin]);
    ids[key] = Number(row.id);
  }
  const categories: number[] = [];
  for (const [name, active, removedAt] of [['网络', true, null], ['打印机（已移除）', false,
    new Date(now.getTime() - 5 * 24 * H)], ['账号权限-改名后', true, null]] as const) {
    const [row] = await query<{ id: string }>(
      `INSERT INTO ${schema}.category(name, active, created_at, removed_at) VALUES ($1, $2, $3, $4) RETURNING id`,
      [prefix + name, active, created, removedAt]);
    categories.push(Number(row.id));
  }

  const ws = weekStartOf(now);
  const inThisWeek = new Date(ws.getTime() + Math.floor((now.getTime() - ws.getTime()) / 2));
  let n = 0;
  for (let u = 0; u < URGENCIES.length; u++) {
    for (const status of STATUSES) {
      for (const assigned of [false, true]) {
        n++;
        const unresolved = status === 'OPEN' || status === 'IN_PROGRESS';
        const submitted = unresolved
          ? new Date(now.getTime() - (assigned ? HOURS[u] * H / 2 : (HOURS[u] + 5) * H))
          : new Date(now.getTime() - 10 * 24 * H + n * H);
        const submitter = n % 2 === 0 ? ids.emp2 : ids.emp1;
        const assignee = assigned ? (n % 4 === 0 ? ids.former : ids.it1) : null;
        const firstResolved = status === 'RESOLVED' || (status === 'CLOSED' && assigned)
          ? (n % 3 === 0 ? inThisWeek : new Date(submitted.getTime() + (HOURS[u] + 1) * H)) : null;
        const updated = firstResolved ?? new Date(submitted.getTime() + 30 * 60 * 1000);
        const [row] = await query<{ id: string }>(
          `INSERT INTO ${schema}.ticket(title, description, category_id, urgency, submitter_id, submitted_at, status,
             assignee_id, first_resolved_at, first_resolved_assignee_id, updated_at)
           VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11) RETURNING id`,
          [`${prefix}V1 工单 ${n} ${URGENCIES[u]} ${status}`, `V1 合成描述 ${n}`, categories[n % 3], URGENCIES[u],
            submitter, submitted, status, assignee, firstResolved, firstResolved ? assignee : null, updated]);
        const id = Number(row.id);
        await query(`INSERT INTO ${schema}.ticket_comment(ticket_id, author_id, author_role, body, created_at)
          VALUES ($1, $2, 'EMPLOYEE', $3, $4)`, [id, submitter, `V1 补充说明 ${n}`,
          new Date(submitted.getTime() + 5 * 60 * 1000)]);
        if (assignee !== null) {
          const claimed = n % 2 === 0;
          await query(`INSERT INTO ${schema}.ticket_event(ticket_id, actor_id, event_type, to_assignee_id, created_at)
            VALUES ($1, $2, $3, $4, $5)`, [id, claimed ? assignee : ids.it2, claimed ? 'CLAIM' : 'ASSIGN', assignee,
            new Date(submitted.getTime() + 10 * 60 * 1000)]);
          await query(`INSERT INTO ${schema}.ticket_comment(ticket_id, author_id, author_role, body, created_at)
            VALUES ($1, $2, 'IT_STAFF', $3, $4)`, [id, assignee, `V1 回复 ${n}`,
            new Date(submitted.getTime() + 12 * 60 * 1000)]);
        }
        const actor = assignee ?? ids.it1;
        let from = 'OPEN';
        if (status !== 'OPEN') {
          if (firstResolved) {
            await statusEvent(schema, id, actor, from, 'RESOLVED', firstResolved);
            from = 'RESOLVED';
          }
          if (status !== from) await statusEvent(schema, id, actor, from, status, new Date(updated.getTime() + 60_000));
        }
      }
    }
  }

  saveState('v1-snapshot', await snapshot(schema));
  saveState('v1-report', { weekStart: ws.toISOString(), at: now.toISOString(), report: await v1Report(schema, ws, now) });
  saveState('v1-ids', ids);
  await closeDb();
});

async function statusEvent(schema: string, ticketId: number, actor: number, from: string, to: string, at: Date):
  Promise<void> {
  await query(`INSERT INTO ${schema}.ticket_event(ticket_id, actor_id, event_type, from_status, to_status, created_at)
    VALUES ($1, $2, 'STATUS', $3, $4, $5)`, [ticketId, actor, from, to, at]);
}
