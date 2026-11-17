import { db, expect, login, logout, test } from '../support/fixtures';

// IQA-014 超过 3 天未解决积压数（AC-018；FR-028）
test('积压数等于超过 72 小时的待处理 / 处理中工单数，改为已解决后立即减一', async ({ page, run }) => {
  const emp = await run.user('emp', 'EMPLOYEE');
  const t1 = await run.user('t1', 'IT_STAFF');
  const cat = await run.category('积压');
  const hoursAgo = (h: number) => new Date(Date.now() - h * 3600 * 1000);
  const mk = (suffix: string, h: number, status: 'OPEN' | 'IN_PROGRESS' | 'RESOLVED' | 'CLOSED') =>
    db.insertTicket({ title: run.name(suffix), categoryId: cat.id, submitterId: emp.id, submittedAt: hoursAgo(h),
      status, firstResolvedAt: status === 'RESOLVED' ? hoursAgo(h - 1) : null, actorId: t1.id });
  const overdueOpen = await mk('超期待处理', 80, 'OPEN');
  await mk('超期处理中', 100, 'IN_PROGRESS');
  await mk('超期已解决', 90, 'RESOLVED');
  await mk('超期已关闭', 90, 'CLOSED');
  await mk('未满待处理', 10, 'OPEN');
  await mk('未满处理中', 71, 'IN_PROGRESS');
  await mk('本周之前', 24 * 30, 'OPEN');

  const sqlBacklog = async () => Number((await db.query(
    "SELECT COUNT(*)::int AS n FROM ticket WHERE status IN ('OPEN','IN_PROGRESS') AND submitted_at < now() - interval '72 hours'"))[0].n);
  const myOverdue = async () => Number((await db.query(
    `SELECT COUNT(*)::int AS n FROM ticket WHERE category_id = $1 AND status IN ('OPEN','IN_PROGRESS')
       AND submitted_at < now() - interval '72 hours'`, [cat.id]))[0].n);
  expect(await myOverdue()).toBe(3);

  await login(page, run.admin());
  await page.goto('/admin/reports/weekly');
  const before = Number(await page.getByTestId('report-backlog').innerText());
  expect(before).toBe(await sqlBacklog());
  expect(before).toBeGreaterThanOrEqual(3);
  await logout(page);

  await login(page, t1);
  await page.goto(`/tickets/${overdueOpen}`);
  await page.getByTestId('status-select').selectOption('RESOLVED');
  await page.getByTestId('status-submit').click();
  await expect(page.getByTestId('ticket-status')).toHaveText('已解决');
  await logout(page);

  await login(page, run.admin());
  await page.goto('/admin/reports/weekly');
  const after = Number(await page.getByTestId('report-backlog').innerText());
  expect(after).toBe(before - 1);
  expect(after).toBe(await sqlBacklog());
});
