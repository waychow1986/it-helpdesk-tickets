import { changeStatusViaUi, db, detailExpected, expect, expectOverdue, expectSameExpected, login, logout,
  rowExpected, test } from '../support/fixtures';
import type { Page } from '@playwright/test';
import type { Status, Urgency } from '../support/db';

// V2 IQA-002 超时未解决工单醒目标出（AC-003、AC-004；FR-004、FR-005、FR-006）
// 回溯工单按数据库数据契约插入（只写 submitted_at、urgency、status 与首次已解决），期望值独立计算。

const HOUR = 3600 * 1000;

test('只有超时的待处理 / 处理中工单显示“已超时”，状态改变后按查看时刻重新判定', async ({ page, run }) => {
  const e1 = await run.user('e1', 'EMPLOYEE');
  const t1 = await run.user('t1', 'IT_STAFF');
  const cat = await run.category('超时');
  const now = Date.now();
  const spec: Record<string, { urgency: Urgency; hoursAgo: number; status: Status }> = {
    A: { urgency: 'HIGH', hoursAgo: 5, status: 'OPEN' },
    B: { urgency: 'MEDIUM', hoursAgo: 25, status: 'IN_PROGRESS' },
    C: { urgency: 'LOW', hoursAgo: 10, status: 'OPEN' },
    D: { urgency: 'HIGH', hoursAgo: 6, status: 'RESOLVED' },
    E: { urgency: 'MEDIUM', hoursAgo: 30, status: 'CLOSED' },
  };
  const ids: Record<string, number> = {};
  const expected: Record<string, Date> = {};
  for (const [key, s] of Object.entries(spec)) {
    const submittedAt = new Date(now - s.hoursAgo * HOUR);
    ids[key] = await db.insertTicket({ title: run.name(key), categoryId: cat.id, submitterId: e1.id,
      urgency: s.urgency, submittedAt, status: s.status, assigneeId: t1.id,
      firstResolvedAt: s.status === 'RESOLVED' ? new Date(now - HOUR) : null,
      firstResolvedAssigneeId: s.status === 'RESOLVED' ? t1.id : null, actorId: t1.id });
    expected[key] = db.expectedResolveAt((await db.ticketBasics(ids[key])).submittedAt, s.urgency);
  }
  const listUrl = `/tickets?q=${encodeURIComponent(run.name(''))}`;

  const check = async (who: string, overdue: Record<string, boolean>) => {
    await page.goto(listUrl);
    for (const key of Object.keys(spec)) {
      const view = await rowExpected(page, ids[key]);
      expectSameExpected(view, expected[key], `${who} 列表 ${key}`);
      expectOverdue(view, overdue[key], `${who} 列表 ${key}`);
      if (overdue[key]) {
        await expect(page.locator(`[data-testid="ticket-row"][data-ticket-id="${ids[key]}"]`)
          .getByTestId('row-overdue')).toHaveText('已超时');
      }
    }
    for (const key of Object.keys(spec)) {
      const view = await detailExpected(page, ids[key]);
      expectOverdue(view, overdue[key], `${who} 详情 ${key}`);
      if (overdue[key]) await expect(page.getByTestId('ticket-overdue')).toHaveText('已超时');
    }
  };
  const step1 = { A: true, B: true, C: false, D: false, E: false };
  const step3 = { A: false, B: true, C: false, D: true, E: false };

  // 1. E1 与 T1 查看列表与详情
  await login(page, e1);
  await check('E1 步骤1', step1);
  await logout(page);
  await login(page, t1);
  await check('T1 步骤1', step1);

  // 2. T1 把 A 改为已解决，把 D 改回待处理
  await changeStatusViaUi(page, ids.A, '已解决');
  await changeStatusViaUi(page, ids.D, '待处理');

  // 3. 再次查看
  await check('T1 步骤3', step3);
  await logout(page);
  await login(page, e1);
  await check('E1 步骤3', step3);
  await expect.poll(async () => (await db.ticketBasics(ids.D)).status).toBe('OPEN');
});

test('超时标识的样式醒目且与未超时行可区分', async ({ page, run }) => {
  const e1 = await run.user('e2', 'EMPLOYEE');
  const cat = await run.category('样式');
  const late = await db.insertTicket({ title: run.name('late'), categoryId: cat.id, submitterId: e1.id,
    urgency: 'HIGH', submittedAt: new Date(Date.now() - 5 * HOUR) });
  const fresh = await db.insertTicket({ title: run.name('fresh'), categoryId: cat.id, submitterId: e1.id,
    urgency: 'HIGH', submittedAt: new Date(Date.now() - HOUR) });
  await login(page, e1);
  await page.goto(`/tickets?q=${encodeURIComponent(run.name(''))}`);
  const badge = page.locator(`[data-testid="ticket-row"][data-ticket-id="${late}"]`).getByTestId('row-overdue');
  await expect(badge).toHaveClass(/overdue/);
  const style = await badge.evaluate(el => {
    const cs = getComputedStyle(el);
    return { bg: cs.backgroundColor, color: cs.color, weight: cs.fontWeight };
  });
  expect(style.bg).toBe('rgb(200, 30, 30)');
  expect(style.color).toBe('rgb(255, 255, 255)');
  expect(Number(style.weight)).toBeGreaterThanOrEqual(700);
  await expect(page.locator(`[data-testid="ticket-row"][data-ticket-id="${fresh}"]`).getByTestId('row-overdue'))
    .toHaveCount(0);
  await expect(page.locator(`[data-testid="ticket-row"][data-ticket-id="${fresh}"]`))
    .toHaveAttribute('data-overdue', 'false');
});
