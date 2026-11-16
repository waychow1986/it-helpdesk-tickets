import { db, eventCount, expect, login, logout, test } from '../support/fixtures';

// IQA-006 状态修改（AC-012；FR-017、FR-020）
test('四种状态间任意修改，每步立即显示并产生处理记录，提交人可见', async ({ page, run }) => {
  const t1 = await run.user('t1', 'IT_STAFF');
  const e1 = await run.user('e1', 'EMPLOYEE');
  const cat = await run.category('状态');
  const title = run.name('状态工单');
  const id = await db.insertTicket({ title, categoryId: cat.id, submitterId: e1.id });

  await login(page, t1);
  await page.goto(`/tickets/${id}`);
  const labels = await page.getByTestId('status-select').locator('option').allTextContents();
  expect(labels).toEqual(['待处理', '处理中', '已解决', '已关闭']);

  const steps: Array<[string, string]> = [['IN_PROGRESS', '处理中'], ['RESOLVED', '已解决'], ['IN_PROGRESS', '处理中'],
    ['RESOLVED', '已解决'], ['CLOSED', '已关闭'], ['OPEN', '待处理']];
  let previous = '待处理';
  for (let i = 0; i < steps.length; i++) {
    const [code, label] = steps[i];
    await page.goto(`/tickets/${id}`);
    await page.getByTestId('status-select').selectOption(code);
    await page.getByTestId('status-submit').click();
    await expect(page.getByTestId('ticket-status')).toHaveText(label);
    expect(await eventCount(page)).toBe(i + 1);
    const last = page.getByTestId('event-item').nth(i);
    await expect(last.getByTestId('event-type')).toHaveText('状态修改');
    await expect(last.getByTestId('event-from')).toHaveText(previous);
    await expect(last.getByTestId('event-to')).toHaveText(label);
    await page.goto(`/tickets?categoryId=${cat.id}`);
    await expect(page.locator(`[data-testid="ticket-row"][data-ticket-id="${id}"]`).getByTestId('row-status'))
      .toHaveText(label);
    previous = label;
  }
  await logout(page);

  await login(page, e1);
  await page.goto(`/tickets/${id}`);
  await expect(page.getByTestId('ticket-status')).toHaveText('待处理');
  await expect(page.getByTestId('event-item')).toHaveCount(steps.length);
  await page.goto('/tickets');
  await expect(page.locator(`[data-testid="ticket-row"][data-ticket-id="${id}"]`).getByTestId('row-status'))
    .toHaveText('待处理');
});
