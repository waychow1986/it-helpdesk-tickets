import { test as base, expect, type Locator, type Page, type APIResponse } from '@playwright/test';
import { createHash, randomBytes } from 'node:crypto';
import * as dbApi from './db';

export { expect };
export const db = dbApi;

export const RUN_PREFIX = (): string => process.env.E2E_RUN_PREFIX!;
export const randomPassword = (): string => `Pw-${randomBytes(12).toString('hex')}`;

export interface Account { id: number; login: string; password: string; display: string; role: dbApi.Role }

/** 本测试的合成数据工具：名称均带运行前缀 + 测试标签。 */
export class Run {
  constructor(readonly tag: string) {}

  name(suffix: string): string {
    return `${RUN_PREFIX()}${this.tag}-${suffix}`;
  }

  admin(): Account {
    return { id: 0, login: `${RUN_PREFIX()}admin`, password: process.env.E2E_ADMIN_PASSWORD!,
      display: `${RUN_PREFIX()}种子管理员`, role: 'ADMIN' };
  }

  async user(suffix: string, role: dbApi.Role): Promise<Account> {
    return dbApi.createUser(this.name(suffix), randomPassword(), this.name(suffix) + '姓名', role);
  }

  async category(suffix: string): Promise<{ id: number; name: string }> {
    const name = this.name(suffix);
    return { id: await dbApi.createCategory(name), name };
  }
}

export const test = base.extend<{ run: Run }>({
  run: async ({}, use, testInfo) => {
    // 标签：V1 iqa-0xx → q0xx；V2 v2-iqa-0xx → v0xx（控制登录名长度不超过 64）
    const file = testInfo.file.replace(/.*[\\/]/, '').replace(/^v2-iqa-(\d+).*/, 'v$1').replace(/^iqa-(\d+).*/, 'q$1');
    const tag = file + createHash('sha1').update(testInfo.title).digest('hex').slice(0, 4);
    await use(new Run(tag));
  },
});

test.afterAll(async () => {
  await dbApi.closeDb();
});

// ---------------- 页面操作 ----------------

export async function login(page: Page, account: { login: string; password: string }): Promise<void> {
  await page.goto('/login');
  await page.getByTestId('login-username').fill(account.login);
  await page.getByTestId('login-password').fill(account.password);
  await page.getByTestId('login-submit').click();
  await expect(page.getByTestId('logout')).toBeVisible();
}

export async function logout(page: Page): Promise<void> {
  await page.getByTestId('logout').click();
  await expect(page).toHaveURL(/\/login\?logout/);
}

/** 管理员在账号管理页创建账号。 */
export async function createUserViaUi(page: Page, a: { login: string; password: string; display: string;
  role: dbApi.Role }): Promise<void> {
  await page.goto('/admin/users');
  await page.getByTestId('user-login').fill(a.login);
  await page.getByTestId('user-password').fill(a.password);
  await page.getByTestId('user-display').fill(a.display);
  await page.getByTestId('user-role').selectOption(a.role);
  await page.getByTestId('user-create').click();
}

/** 管理员在类别管理页新增类别，返回类别 id。 */
export async function createCategoryViaUi(page: Page, name: string): Promise<number> {
  await page.goto('/admin/categories');
  await page.getByTestId('category-new-name').fill(name);
  await page.getByTestId('category-create').click();
  const row = page.locator(`[data-testid="category-row"][data-category-name="${name}"]`);
  await expect(row).toHaveCount(1);
  return Number(await row.getAttribute('data-category-id'));
}

/** 员工提交工单，返回工单编号。 */
export async function submitTicketViaUi(page: Page, t: { title: string; description: string; category: string;
  urgency: '低' | '中' | '高' }): Promise<number> {
  await page.goto('/tickets/new');
  await page.getByTestId('ticket-title').fill(t.title);
  await page.getByTestId('ticket-description').fill(t.description);
  await page.getByTestId('ticket-category').selectOption({ label: t.category });
  await page.getByTestId('ticket-urgency').selectOption({ label: t.urgency });
  await page.getByTestId('ticket-submit').click();
  await expect(page).toHaveURL(/\/tickets\/\d+$/);
  return ticketIdFromUrl(page);
}

export function ticketIdFromUrl(page: Page): number {
  const match = /\/tickets\/(\d+)/.exec(page.url());
  if (!match) throw new Error(`当前页面不是工单详情：${page.url()}`);
  return Number(match[1]);
}

/** 当前列表页（含后续分页）中全部工单编号。 */
export async function listedTicketIds(page: Page): Promise<number[]> {
  const ids: number[] = [];
  for (;;) {
    const rows = page.getByTestId('ticket-row');
    for (const id of await rows.evaluateAll(els => els.map(e => e.getAttribute('data-ticket-id')))) {
      ids.push(Number(id));
    }
    const next = page.getByTestId('page-next');
    if (await next.count() === 0) break;
    await next.click();
  }
  return ids;
}

/** 以页面上的 CSRF 令牌直接发起 POST（模拟绕过界面入口的直接请求）。 */
export async function postWithCsrf(page: Page, path: string, form: Record<string, string>): Promise<APIResponse> {
  const token = await page.locator('input[name="_csrf"]').first().getAttribute('value');
  return page.request.post(path, {
    form: { ...form, _csrf: token ?? '' },
    headers: { Accept: 'text/html,application/xhtml+xml' },
    maxRedirects: 0,
  });
}

/** 详情页处理记录行数。 */
export async function eventCount(page: Page): Promise<number> {
  return page.getByTestId('event-item').count();
}

/** 公司时区（Asia/Shanghai）当前自然周的周一 00:00 时刻。 */
export function companyWeekStart(now: Date): Date {
  const offsetMs = 8 * 3600 * 1000;
  const local = new Date(now.getTime() + offsetMs);
  const day = (local.getUTCDay() + 6) % 7; // 周一 = 0
  const mondayLocal = Date.UTC(local.getUTCFullYear(), local.getUTCMonth(), local.getUTCDate() - day);
  return new Date(mondayLocal - offsetMs);
}

/** 公司时区日期 yyyy-MM-dd。 */
export function companyDate(d: Date): string {
  return new Date(d.getTime() + 8 * 3600 * 1000).toISOString().slice(0, 10);
}

// ---------------- V2：期望解决时间、超时标识与账号状态（TD-033） ----------------

/** 公司时区 yyyy-MM-dd HH:mm（与页面显示格式一致）。 */
export function companyMinute(d: Date): string {
  return new Date(d.getTime() + 8 * 3600 * 1000).toISOString().slice(0, 16).replace('T', ' ');
}

export function ticketRow(page: Page, id: number): Locator {
  return page.locator(`[data-testid="ticket-row"][data-ticket-id="${id}"]`);
}

export interface ExpectedView { at: Date; text: string; overdue: boolean; badge: boolean }

/** 读取列表行的期望解决时间（data-expected-at 与显示文本）与超时标识。 */
export async function rowExpected(page: Page, id: number): Promise<ExpectedView> {
  const row = ticketRow(page, id);
  await expect(row).toHaveCount(1);
  const cell = row.getByTestId('row-expected');
  return {
    at: new Date((await cell.getAttribute('data-expected-at'))!),
    text: (await cell.textContent())!.trim(),
    overdue: (await row.getAttribute('data-overdue')) === 'true',
    badge: (await row.getByTestId('row-overdue').count()) === 1,
  };
}

/** 读取详情页的期望解决时间与超时标识。 */
export async function detailExpected(page: Page, id: number): Promise<ExpectedView> {
  await page.goto(`/tickets/${id}`);
  const cell = page.getByTestId('ticket-expected');
  return {
    at: new Date((await cell.getAttribute('data-expected-at'))!),
    text: (await cell.textContent())!.trim(),
    overdue: (await page.getByTestId('ticket-detail').getAttribute('data-overdue')) === 'true',
    badge: (await page.getByTestId('ticket-overdue').count()) === 1,
  };
}

/** 断言期望解决时间视图与独立计算值一致（毫秒精度与显示到分钟）。 */
export function expectSameExpected(view: ExpectedView, expected: Date, label: string): void {
  expect(view.at.getTime(), `${label}：data-expected-at`).toBe(expected.getTime());
  expect(view.text, `${label}：显示文本`).toBe(companyMinute(expected));
}

/** 断言超时标识：data-overdue 属性、“已超时”元素与期望一致。 */
export function expectOverdue(view: ExpectedView, overdue: boolean, label: string): void {
  expect(view.overdue, `${label}：data-overdue`).toBe(overdue);
  expect(view.badge, `${label}：已超时标识`).toBe(overdue);
}

export function userRow(page: Page, login: string): Locator {
  return page.locator(`[data-testid="user-row"][data-login-name="${login}"]`);
}

/** 管理员账号列表中某账号的状态文字与 data-active。 */
export async function userStatus(page: Page, login: string): Promise<{ text: string; active: boolean }> {
  await page.goto('/admin/users');
  const cell = userRow(page, login).getByTestId('user-row-status');
  await expect(cell).toHaveCount(1);
  return { text: (await cell.textContent())!.trim(), active: (await cell.getAttribute('data-active')) === 'true' };
}

/** 管理员在账号管理页停用账号（点击该行“停用”按钮）。 */
export async function deactivateViaUi(page: Page, login: string): Promise<void> {
  await page.goto('/admin/users');
  await userRow(page, login).getByTestId('user-deactivate').click();
}

/** 管理员在账号管理页重新启用账号。 */
export async function activateViaUi(page: Page, login: string): Promise<void> {
  await page.goto('/admin/users');
  await userRow(page, login).getByTestId('user-activate').click();
}

/** 以登录页提交凭据并断言登录被拒（提示与 V1 一致）。 */
export async function expectLoginRefused(page: Page, account: { login: string; password: string }): Promise<string> {
  await page.goto('/login');
  await page.getByTestId('login-username').fill(account.login);
  await page.getByTestId('login-password').fill(account.password);
  await page.getByTestId('login-submit').click();
  await expect(page).toHaveURL(/\/login\?error/);
  await expect(page.getByTestId('logout')).toHaveCount(0);
  return (await page.getByTestId('login-error').textContent())!.trim();
}

/** IT 同事在详情页修改状态。 */
export async function changeStatusViaUi(page: Page, id: number, label: '待处理' | '处理中' | '已解决' | '已关闭'):
  Promise<void> {
  await page.goto(`/tickets/${id}`);
  await page.getByTestId('status-select').selectOption({ label });
  await page.getByTestId('status-submit').click();
  await expect(page.getByTestId('ticket-status')).toHaveText(label);
}

/** 在详情页添加补充说明 / 回复。 */
export async function commentViaUi(page: Page, id: number, body: string): Promise<void> {
  await page.goto(`/tickets/${id}`);
  await page.getByTestId('comment-body').fill(body);
  await page.getByTestId('comment-submit').click();
  await expect(page.getByTestId('comment-text').filter({ hasText: body })).toHaveCount(1);
}
