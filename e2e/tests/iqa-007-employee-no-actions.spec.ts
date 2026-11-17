import { db, eventCount, expect, login, postWithCsrf, test } from '../support/fixtures';

// IQA-007 员工无法执行处理操作（AC-014；FR-021）
test('员工详情无处理入口，直接发起认领 / 分派 / 改状态均被拒且工单不变', async ({ page, run }) => {
  const e1 = await run.user('e1', 'EMPLOYEE');
  const t1 = await run.user('t1', 'IT_STAFF');
  const cat = await run.category('越权');
  const id = await db.insertTicket({ title: run.name('员工工单'), categoryId: cat.id, submitterId: e1.id });

  await login(page, e1);
  await page.goto(`/tickets/${id}`);
  for (const testId of ['claim-button', 'assign-select', 'assign-submit', 'status-select', 'status-submit']) {
    await expect(page.getByTestId(testId)).toHaveCount(0);
  }
  const statusBefore = await page.getByTestId('ticket-status').innerText();
  const assigneeBefore = await page.getByTestId('ticket-assignee').innerText();
  const eventsBefore = await eventCount(page);

  const attempts: Array<[string, Record<string, string>]> = [
    [`/tickets/${id}/claim`, {}],
    [`/tickets/${id}/assign`, { assigneeId: String(t1.id) }],
    [`/tickets/${id}/status`, { status: 'RESOLVED' }],
  ];
  for (const [path, form] of attempts) {
    const response = await postWithCsrf(page, path, form);
    expect(response.status()).toBe(403);
    expect(await response.text()).toContain('data-testid="forbidden"');
  }

  await page.goto(`/tickets/${id}`);
  await expect(page.getByTestId('ticket-status')).toHaveText(statusBefore);
  await expect(page.getByTestId('ticket-assignee')).toHaveText(assigneeBefore);
  expect(await eventCount(page)).toBe(eventsBefore);
  const rows = await db.query('SELECT status, assignee_id FROM ticket WHERE id = $1', [id]);
  expect(rows[0]).toEqual({ status: 'OPEN', assignee_id: null });
});
