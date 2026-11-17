import { companyDate, db, expect, listedTicketIds, login, test } from '../support/fixtures';

// IQA-003 员工查看进度与工单隔离（AC-007、AC-009；FR-010、FR-011、FR-013、FR-024）
test('员工只看到本人工单，直接访问他人工单被拒，搜索筛选不出现他人工单', async ({ page, run }) => {
  const e1 = await run.user('e1', 'EMPLOYEE');
  const e2 = await run.user('e2', 'EMPLOYEE');
  const t1 = await run.user('t1', 'IT_STAFF');
  const cat = await run.category('隔离');
  const keyword = run.name('共同关键词');
  const mk = (who: number, title: string, status: 'OPEN' | 'IN_PROGRESS', assignee: number | null) =>
    db.insertTicket({ title: `${keyword} ${title}`, categoryId: cat.id, submitterId: who, status, assigneeId: assignee });
  const a1 = await mk(e1.id, 'E1-待处理', 'OPEN', null);
  const a2 = await mk(e1.id, 'E1-处理中', 'IN_PROGRESS', t1.id);
  const b1 = await mk(e2.id, 'E2-待处理', 'OPEN', null);
  const b2 = await mk(e2.id, 'E2-处理中', 'IN_PROGRESS', t1.id);

  await login(page, e1);
  await page.goto('/tickets');
  expect((await listedTicketIds(page)).sort()).toEqual([a1, a2].sort());
  const row2 = page.locator(`[data-testid="ticket-row"][data-ticket-id="${a2}"]`);
  await expect(row2.getByTestId('row-status')).toHaveText('处理中');
  await expect(row2.getByTestId('row-assignee')).toHaveText(t1.display);
  const row1 = page.locator(`[data-testid="ticket-row"][data-ticket-id="${a1}"]`);
  await expect(row1.getByTestId('row-status')).toHaveText('待处理');
  await expect(row1.getByTestId('row-assignee')).toHaveText('未分配');

  for (const id of [a1, a2]) {
    await page.goto(`/tickets/${id}`);
    await expect(page.getByTestId('ticket-id')).toHaveText(`#${id}`);
  }

  for (const id of [b1, b2]) {
    const response = await page.goto(`/tickets/${id}`);
    expect(response?.status()).toBe(403);
    await expect(page.getByTestId('forbidden')).toBeVisible();
    await expect(page.locator('body')).not.toContainText('E2-');
    await expect(page.locator('body')).not.toContainText(keyword);
    await expect(page.getByTestId('ticket-status')).toHaveCount(0);
  }

  const today = companyDate(new Date());
  const queries = [
    `q=${encodeURIComponent(keyword)}`,
    'status=OPEN', 'status=IN_PROGRESS',
    `categoryId=${cat.id}`,
    `assignee=${t1.id}`, 'assignee=unassigned',
    `from=${today}&to=${today}`,
  ];
  for (const q of queries) {
    await page.goto(`/tickets?${q}`);
    const ids = await listedTicketIds(page);
    expect(ids).not.toContain(b1);
    expect(ids).not.toContain(b2);
    for (const id of ids) expect([a1, a2]).toContain(id);
  }
  await page.goto(`/tickets?q=${encodeURIComponent(keyword)}`);
  expect((await listedTicketIds(page)).sort()).toEqual([a1, a2].sort());
});
