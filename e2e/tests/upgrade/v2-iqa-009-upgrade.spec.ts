import { expect, test, type Page } from '@playwright/test';
import { closeDb, query } from '../../support/db';
import {
  HOURS, UPGRADE_ACCOUNTS, diff, history, loadState, overdueResolved, snapshot, upgradeAccount, upgradeSchema,
  v1Report, weekStartOf, type AccountKey, type Row, type Snapshot, type V1Report,
} from '../../support/upgrade-snapshot';

// V2 IQA-009 开发者升级核对（TD-030、TD-032；FR-019～FR-022、NFR-001；AC-013、AC-014）
// localhost:8082 实例：同一一次性 schema 先以 --spring.flyway.target=1 构造为 V1 结构并写入 V1 数据集（upgrade-seed），
// 再以默认配置启动完成真实 V1→V2 升级；本文件以各身份原凭据逐项核对。

const STATUS: Record<string, string> = { OPEN: '待处理', IN_PROGRESS: '处理中', RESOLVED: '已解决', CLOSED: '已关闭' };
const ROLE: Record<string, string> = { EMPLOYEE: '员工', IT_STAFF: 'IT 同事', ADMIN: '管理员' };

test.afterAll(async () => {
  await closeDb();
});

async function signIn(page: Page, key: AccountKey): Promise<void> {
  const account = upgradeAccount(key);
  await page.goto('/login');
  await page.getByTestId('login-username').fill(account.login);
  await page.getByTestId('login-password').fill(account.password);
  await page.getByTestId('login-submit').click();
  await expect(page.getByTestId('logout')).toBeVisible();
}

async function signOut(page: Page): Promise<void> {
  await page.getByTestId('logout').click();
  await expect(page).toHaveURL(/\/login\?logout/);
}

function byId(rows: Row[]): Map<string, Row> {
  return new Map(rows.map(r => [r.id!, r]));
}

function companyMinute(d: Date): string {
  return new Date(d.getTime() + 8 * 3600 * 1000).toISOString().slice(0, 16).replace('T', ' ');
}

test('IQA-009 升级后数据保持不变、全部账号启用、补上期望解决时间、报表一致、设置入口不可用', async ({ page }) => {
  const schema = upgradeSchema();
  const before = loadState<Snapshot>('v1-snapshot');
  const saved = loadState<{ weekStart: string; report: V1Report }>('v1-report');

  // 迁移历史：版本 1 早于版本 2，版本 2 成功且只出现一次，无失败执行
  const rows = (await history(schema)).filter(r => r.type === 'SQL');
  expect(rows.map(r => r.version)).toEqual(['1', '2']);
  expect(rows.every(r => r.success === 'true')).toBe(true);
  expect((await history(schema)).filter(r => r.success !== 'true')).toEqual([]);

  // SQL 快照：V1 列逐行逐列相等；全部账号启用
  expect(diff(before, await snapshot(schema)), '升级前后 V1 列差异（只含 id 与列名）').toEqual([]);
  expect(Number((await query(`SELECT COUNT(*) AS n FROM ${schema}.app_user WHERE NOT active`))[0].n)).toBe(0);

  const users = byId(before.app_user);
  const categories = byId(before.category);
  const tickets = before.ticket;
  const display = (id: string | null) => (id === null ? '未分配' : users.get(id)!.display_name!);

  // 各身份原凭据登录、身份不变
  for (const key of UPGRADE_ACCOUNTS) {
    const user = before.app_user.find(u => u.login_name === upgradeAccount(key).login)!;
    await signIn(page, key);
    await expect(page.getByTestId('current-user')).toHaveText(`${user.display_name}（${ROLE[user.role!]}）`);
    await signOut(page);
  }

  // 管理员：账号列表全部“启用”，姓名与身份不变
  await signIn(page, 'admin');
  await page.goto('/admin/users');
  for (const user of before.app_user) {
    const row = page.locator(`[data-testid="user-row"][data-user-id="${user.id}"]`);
    await expect(row.getByTestId('user-row-status')).toHaveText('启用');
    await expect(row.getByTestId('user-row-status')).toHaveAttribute('data-active', 'true');
    await expect(row.getByTestId('user-row-display')).toHaveText(user.display_name!);
    await expect(row.getByTestId('user-row-role')).toHaveText(ROLE[user.role!]);
  }

  // 周报表：V1 四项等于升级前按 V1 口径计算的期望值；第五项符合 FR-017
  const ws = new Date(saved.weekStart);
  expect(weekStartOf(new Date()).getTime(), '演练应在同一自然周内完成').toBe(ws.getTime());
  const now = await v1Report(schema, ws, new Date());
  expect(now).toEqual(saved.report);
  await page.goto('/admin/reports/weekly');
  for (const [id, count] of Object.entries(saved.report.categories)) {
    await expect(page.locator(`[data-testid="report-category-row"][data-category-id="${id}"]`)
      .getByTestId('report-count')).toHaveText(String(count));
  }
  for (const [id, count] of Object.entries(saved.report.staff)) {
    await expect(page.locator(`[data-testid="report-staff-row"][data-user-id="${id}"]`)
      .getByTestId('report-count')).toHaveText(String(count));
  }
  await expect(page.getByTestId('report-unassigned').getByTestId('report-count'))
    .toHaveText(String(saved.report.unassigned));
  await expect(page.getByTestId('report-avg')).toHaveAttribute('data-avg-seconds', saved.report.avgSeconds);
  await expect(page.getByTestId('report-backlog')).toHaveText(String(saved.report.backlog));
  const fifth = await overdueResolved(schema, ws);
  expect(fifth).toBeGreaterThan(0);
  await expect(page.getByTestId('report-overdue-resolved')).toHaveText(String(fifth));
  await signOut(page);

  // /setup 不可用（V1 已完成初始管理员设置）
  const setup = await page.goto('/setup');
  expect(setup?.status()).toBe(403);
  await expect(page.getByTestId('setup-closed')).toBeVisible();

  // IT 同事：全部工单列表与每张工单详情（含沟通与处理记录）与快照一致；期望解决时间与超时标识
  await signIn(page, 'it1');
  await page.goto('/tickets');
  const listed = await page.getByTestId('ticket-row').evaluateAll(els => els.map(e => e.getAttribute('data-ticket-id')));
  expect([...listed].sort()).toEqual(tickets.map(t => t.id).sort());
  for (const t of tickets) {
    const expected = new Date(new Date(t.submitted_at!).getTime() + HOURS[t.urgency!] * 3600 * 1000);
    const unresolved = t.status === 'OPEN' || t.status === 'IN_PROGRESS';
    const overdue = unresolved && Date.now() > expected.getTime();
    const row = page.locator(`[data-testid="ticket-row"][data-ticket-id="${t.id}"]`);
    await expect(row.getByTestId('row-expected')).toHaveText(companyMinute(expected));
    expect(new Date((await row.getByTestId('row-expected').getAttribute('data-expected-at'))!).getTime())
      .toBe(expected.getTime());
    await expect(row).toHaveAttribute('data-overdue', String(overdue));
    await expect(row.getByTestId('row-overdue')).toHaveCount(overdue ? 1 : 0);
  }
  for (const t of tickets) {
    await page.goto(`/tickets/${t.id}`);
    const category = categories.get(t.category_id!)!;
    await expect(page.getByTestId('detail-title')).toHaveText(t.title!);
    await expect(page.getByTestId('detail-description')).toHaveText(t.description!);
    await expect(page.getByTestId('detail-category'))
      .toHaveText(category.active === 'true' ? category.name! : `${category.name}（已移除）`);
    await expect(page.getByTestId('ticket-status')).toHaveText(STATUS[t.status!]);
    await expect(page.getByTestId('ticket-assignee')).toHaveText(display(t.assignee_id));
    await expect(page.getByTestId('detail-submitter')).toHaveText(display(t.submitter_id));
    await expect(page.getByTestId('detail-submitted')).toHaveText(companyMinute(new Date(t.submitted_at!)));
    const comments = before.ticket_comment.filter(c => c.ticket_id === t.id);
    await expect(page.getByTestId('comment-text')).toHaveText(comments.map(c => c.body!));
    await expect(page.getByTestId('comment-author')).toHaveText(comments.map(c => display(c.author_id)));
    const events = before.ticket_event.filter(e => e.ticket_id === t.id);
    await expect(page.getByTestId('event-item')).toHaveCount(events.length);
    await expect(page.getByTestId('event-actor')).toHaveText(events.map(e => display(e.actor_id)));
    const expected = new Date(new Date(t.submitted_at!).getTime() + HOURS[t.urgency!] * 3600 * 1000);
    expect(new Date((await page.getByTestId('ticket-expected').getAttribute('data-expected-at'))!).getTime())
      .toBe(expected.getTime());
  }
  await signOut(page);

  // 员工：我的工单完整，访问他人工单 403
  for (const [key, other] of [['emp1', 'emp2'], ['emp2', 'emp1']] as const) {
    const me = before.app_user.find(u => u.login_name === upgradeAccount(key).login)!;
    const mine = tickets.filter(t => t.submitter_id === me.id).map(t => t.id).sort();
    const otherId = before.app_user.find(u => u.login_name === upgradeAccount(other).login)!.id;
    const theirs = tickets.find(t => t.submitter_id === otherId)!;
    await signIn(page, key);
    await page.goto('/tickets');
    const own = await page.getByTestId('ticket-row').evaluateAll(els => els.map(e => e.getAttribute('data-ticket-id')));
    expect([...own].sort()).toEqual(mine);
    expect((await page.goto(`/tickets/${theirs.id}`))?.status()).toBe(403);
    await page.goto('/tickets');
    await signOut(page);
  }
});
