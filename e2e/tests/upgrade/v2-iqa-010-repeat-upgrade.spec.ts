import { expect, test, type Page } from '@playwright/test';
import { closeDb } from '../../support/db';
import {
  ALL_COLUMNS_V2, HOURS, diff, history, loadState, saveState, snapshot, upgradeAccount, upgradeSchema,
  type AccountKey, type Row, type Snapshot,
} from '../../support/upgrade-snapshot';

// V2 IQA-010 开发者重复升级核对（TD-030；FR-023、NFR-001；AC-015）
// “IQA-010 准备”在 upgrade-verify project 末尾执行：在 V2 中新增数据（新工单与回复、停用一个账号、把一张既有工单
// 改为已解决）并保存全列快照、迁移历史与页面所见报表；脚本随后停止并以相同参数重启 8082（重复执行升级，日志须含
// up to date）；“IQA-010 比对”在 upgrade-repeat project 中逐项比对。

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

interface ReportView { overdueResolved: string; unassigned: string; avg: string | null; staff: Record<string, string> }

async function readReport(page: Page): Promise<ReportView> {
  await page.goto('/admin/reports/weekly');
  const staff: Record<string, string> = {};
  for (const row of await page.getByTestId('report-staff-row').all()) {
    staff[(await row.getAttribute('data-user-id'))!] = (await row.getByTestId('report-count').textContent())!.trim();
  }
  return {
    overdueResolved: (await page.getByTestId('report-overdue-resolved').textContent())!.trim(),
    unassigned: (await page.getByTestId('report-unassigned').getByTestId('report-count').textContent())!.trim(),
    avg: await page.getByTestId('report-avg').getAttribute('data-avg-seconds'),
    staff,
  };
}

interface Prepared {
  snapshot: Snapshot;
  history: Row[];
  report: ReportView;
  newTicketId: string;
  reply: string;
  resolvedTicketId: string;
  deactivated: AccountKey;
  expected: Record<string, string>;
}

test('IQA-010 准备：在 V2 中新增数据并保存快照', async ({ page }) => {
  const schema = upgradeSchema();
  const v1 = loadState<Snapshot>('v1-snapshot');
  const network = v1.category.find(c => c.active === 'true')!;
  const open = v1.ticket.find(t => t.status === 'OPEN')!;
  const reply = `${process.env.E2E_RUN_PREFIX}up-V2 回复`;

  // 员工提交新工单
  await signIn(page, 'emp1');
  await page.goto('/tickets/new');
  await page.getByTestId('ticket-title').fill(`${process.env.E2E_RUN_PREFIX}up-V2 新工单`);
  await page.getByTestId('ticket-description').fill('V2 合成描述');
  await page.getByTestId('ticket-category').selectOption({ label: network.name! });
  await page.getByTestId('ticket-urgency').selectOption({ label: '高' });
  await page.getByTestId('ticket-submit').click();
  await expect(page).toHaveURL(/\/tickets\/\d+$/);
  const newTicketId = /\/tickets\/(\d+)/.exec(page.url())![1];
  await signOut(page);

  // IT 同事回复新工单，并把一张既有待处理工单改为已解决
  await signIn(page, 'it1');
  await page.goto(`/tickets/${newTicketId}`);
  await page.getByTestId('comment-body').fill(reply);
  await page.getByTestId('comment-submit').click();
  await expect(page.getByTestId('comment-text').filter({ hasText: reply })).toHaveCount(1);
  await page.goto(`/tickets/${open.id}`);
  await page.getByTestId('status-select').selectOption({ label: '已解决' });
  await page.getByTestId('status-submit').click();
  await expect(page.getByTestId('ticket-status')).toHaveText('已解决');
  await signOut(page);

  // 管理员停用一个账号
  await signIn(page, 'admin');
  await page.goto('/admin/users');
  await page.locator(`[data-testid="user-row"][data-login-name="${upgradeAccount('emp2').login}"]`)
    .getByTestId('user-deactivate').click();
  await expect(page.getByTestId('flash')).toHaveText('已停用该账号');
  const report = await readReport(page);
  await signOut(page);

  const all = await snapshot(schema, ALL_COLUMNS_V2);
  const expected: Record<string, string> = {};
  for (const t of all.ticket) {
    expected[t.id!] = new Date(new Date(t.submitted_at!).getTime() + HOURS[t.urgency!] * 3600 * 1000).toISOString();
  }
  saveState('v2-prepared', { snapshot: all, history: await history(schema), report, newTicketId, reply,
    resolvedTicketId: open.id!, deactivated: 'emp2', expected } satisfies Prepared);
});

test('IQA-010 比对：重启（重复执行升级）后数据、期望解决时间、停用状态与迁移历史均不变', async ({ page }) => {
  const schema = upgradeSchema();
  const prepared = loadState<Prepared>('v2-prepared');
  const v1 = loadState<Snapshot>('v1-snapshot');

  // 全部表全部列（含 active）逐行相等：无重复、无丢失、无改变；V1 数据仍与升级前一致
  const after = await snapshot(schema, ALL_COLUMNS_V2);
  expect(diff(prepared.snapshot, after), '重复升级前后差异（只含 id 与列名）').toEqual([]);
  // 与升级前 V1 快照相比，只允许 V2 中新增的行与被改为已解决的那张工单不同
  const v1Diff = diff(v1, await snapshot(schema))
    .filter(d => !d.endsWith(' 新增') && !d.startsWith(`ticket#${prepared.resolvedTicketId}.`));
  expect(v1Diff, 'V1 数据完整').toEqual([]);
  // 迁移历史行数与内容不变
  expect(await history(schema)).toEqual(prepared.history);

  // 期望解决时间不变（页面）
  await signIn(page, 'it1');
  await page.goto('/tickets');
  for (const [id, iso] of Object.entries(prepared.expected)) {
    const cell = page.locator(`[data-testid="ticket-row"][data-ticket-id="${id}"]`).getByTestId('row-expected');
    expect(new Date((await cell.getAttribute('data-expected-at'))!).toISOString(), `工单 ${id}`).toBe(iso);
  }
  await page.goto(`/tickets/${prepared.resolvedTicketId}`);
  await expect(page.getByTestId('ticket-status')).toHaveText('已解决');
  await expect(page.getByTestId('ticket-overdue')).toHaveCount(0);
  await signOut(page);

  // 新增数据完整
  await signIn(page, 'emp1');
  await page.goto(`/tickets/${prepared.newTicketId}`);
  await expect(page.getByTestId('comment-text').filter({ hasText: prepared.reply })).toHaveCount(1);
  expect(new Date((await page.getByTestId('ticket-expected').getAttribute('data-expected-at'))!).toISOString())
    .toBe(prepared.expected[prepared.newTicketId]);
  await signOut(page);

  // 停用账号仍停用且不能登录
  const account = upgradeAccount(prepared.deactivated);
  await page.goto('/login');
  await page.getByTestId('login-username').fill(account.login);
  await page.getByTestId('login-password').fill(account.password);
  await page.getByTestId('login-submit').click();
  await expect(page.getByTestId('login-error')).toHaveText('登录名或密码错误');
  await signIn(page, 'admin');
  await page.goto('/admin/users');
  await expect(page.locator(`[data-testid="user-row"][data-login-name="${account.login}"]`)
    .getByTestId('user-row-status')).toHaveText('停用');
  // 周报表与重启前一致
  expect(await readReport(page)).toEqual(prepared.report);
  await signOut(page);
});
