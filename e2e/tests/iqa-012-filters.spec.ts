import { db, expect, listedTicketIds, login, test } from '../support/fixtures';

// IQA-012 条件筛选与组合（AC-016；FR-023）
test('按状态、类别、处理人（含未分配）、提交时间筛选及组合，与预期集合一致，清除后恢复', async ({ page, run }) => {
  const t1 = await run.user('t1', 'IT_STAFF');
  const t2 = await run.user('t2', 'IT_STAFF');
  const e1 = await run.user('e1', 'EMPLOYEE');
  const ca = await run.category('网络');
  const cb = await run.category('账号');
  const kw = run.name('筛选');
  const at = (s: string) => new Date(`${s}+08:00`);
  const tickets = {
    k1: await db.insertTicket({ title: `${kw} 打印 一`, categoryId: ca.id, submitterId: e1.id, status: 'OPEN',
      submittedAt: at('2025-06-01T10:00:00') }),
    k2: await db.insertTicket({ title: `${kw} 打印 二`, categoryId: ca.id, submitterId: e1.id, status: 'IN_PROGRESS',
      assigneeId: t1.id, submittedAt: at('2025-06-05T09:00:00') }),
    k3: await db.insertTicket({ title: `${kw} 邮箱`, categoryId: cb.id, submitterId: e1.id, status: 'CLOSED',
      assigneeId: t2.id, submittedAt: at('2025-06-10T23:59:00') }),
    k4: await db.insertTicket({ title: `${kw} 打印 三`, categoryId: cb.id, submitterId: e1.id, status: 'OPEN',
      submittedAt: at('2025-06-11T00:00:00') }),
  };
  const mine = Object.values(tickets);
  const { k1, k2, k3, k4 } = tickets;

  await login(page, t1);
  const filter = async (opts: { q?: string; status?: string; category?: number; assignee?: string; from?: string;
    to?: string }) => {
    await page.goto('/tickets');
    if (opts.q) await page.getByTestId('filter-q').fill(opts.q);
    if (opts.status) await page.getByTestId('filter-status').selectOption(opts.status);
    if (opts.category) await page.getByTestId('filter-category').selectOption(String(opts.category));
    if (opts.assignee) await page.getByTestId('filter-assignee').selectOption(opts.assignee);
    if (opts.from) await page.getByTestId('filter-from').fill(opts.from);
    if (opts.to) await page.getByTestId('filter-to').fill(opts.to);
    await page.getByTestId('filter-submit').click();
    return (await listedTicketIds(page)).filter(id => mine.includes(id)).sort();
  };
  const sorted = (...ids: number[]) => [...ids].sort();

  expect(await filter({ status: 'OPEN' })).toEqual(sorted(k1, k4));
  expect(await filter({ status: 'CLOSED' })).toEqual(sorted(k3));
  expect(await filter({ category: ca.id })).toEqual(sorted(k1, k2));
  expect(await filter({ category: cb.id })).toEqual(sorted(k3, k4));
  expect(await filter({ assignee: String(t1.id) })).toEqual(sorted(k2));
  expect(await filter({ assignee: String(t2.id) })).toEqual(sorted(k3));
  expect(await filter({ assignee: 'unassigned' })).toEqual(sorted(k1, k4));
  expect(await filter({ from: '2025-06-05', to: '2025-06-10' })).toEqual(sorted(k2, k3));
  expect(await filter({ from: '2025-06-11' })).toEqual(sorted(k4));
  expect(await filter({ to: '2025-06-01' })).toEqual(sorted(k1));
  expect(await filter({ q: `${kw} 打印`, status: 'OPEN', category: ca.id })).toEqual(sorted(k1));
  expect(await filter({ q: `${kw} 打印`, status: 'OPEN', category: cb.id })).toEqual(sorted(k4));
  expect(await filter({ q: `${kw} 打印`, status: 'CLOSED', category: ca.id })).toEqual([]);

  // 清除条件后恢复完整列表
  await filter({ q: kw, status: 'OPEN' });
  await page.getByTestId('filter-clear').click();
  await expect(page).toHaveURL(/\/tickets$/);
  await expect(page.getByTestId('filter-q')).toHaveValue('');
  await expect(page.getByTestId('filter-status')).toHaveValue('');
  const all = (await listedTicketIds(page)).filter(id => mine.includes(id)).sort();
  expect(all).toEqual(sorted(...mine));
});
