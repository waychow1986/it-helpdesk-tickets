import { companyDate, companyWeekStart, db, expect, login, test } from '../support/fixtures';

// IQA-013 周报表数量与处理时长（AC-017；FR-025、FR-026、FR-027；PQ-002 A）
test('本周起止日期、类别数量、处理量（含未分配）与平均处理时长符合口径', async ({ page, run }) => {
  const now = new Date();
  const ws = companyWeekStart(now);
  const span = now.getTime() - ws.getTime();
  const inWeek = (fraction: number) => new Date(ws.getTime() + Math.floor(span * fraction));
  const lastWeek = (hoursBeforeWeek: number) => new Date(ws.getTime() - hoursBeforeWeek * 3600 * 1000);

  const s1 = await run.user('s1', 'IT_STAFF');
  const s2 = await run.user('s2', 'IT_STAFF');
  const emp = await run.user('emp', 'EMPLOYEE');
  const cx = await run.category('X');
  const cy = await run.category('Y');
  const cz = await run.category('Z');

  // a：上周提交、本周首次解决（S1）→ 计入 S1，不计入类别数量
  await db.insertTicket({ title: run.name('a'), categoryId: cx.id, submitterId: emp.id, submittedAt: lastWeek(30),
    status: 'RESOLVED', assigneeId: s1.id, firstResolvedAt: inWeek(0.2), firstResolvedAssigneeId: s1.id,
    actorId: s1.id });
  // b：本周提交、本周首次解决（S1）
  await db.insertTicket({ title: run.name('b'), categoryId: cx.id, submitterId: emp.id, submittedAt: inWeek(0.1),
    status: 'RESOLVED', assigneeId: s1.id, firstResolvedAt: inWeek(0.3), firstResolvedAssigneeId: s1.id,
    actorId: s1.id });
  // c：本周提交、首次解决时无处理人 → 未分配
  await db.insertTicket({ title: run.name('c'), categoryId: cy.id, submitterId: emp.id, submittedAt: inWeek(0.15),
    status: 'RESOLVED', firstResolvedAt: inWeek(0.4), firstResolvedAssigneeId: null, actorId: s2.id });
  // d：上周首次解决，本周再次标记为已解决 → 不计入处理量与平均
  await db.insertTicket({ title: run.name('d'), categoryId: cx.id, submitterId: emp.id, submittedAt: lastWeek(50),
    status: 'RESOLVED', assigneeId: s2.id, firstResolvedAt: lastWeek(40), firstResolvedAssigneeId: s2.id,
    actorId: s2.id });
  // e：本周提交、未解决
  await db.insertTicket({ title: run.name('e'), categoryId: cx.id, submitterId: emp.id, submittedAt: inWeek(0.5) });
  await db.removeCategory(cy.id);

  const expectedSql = async () => {
    const [row] = await db.query(
      `SELECT (SELECT COUNT(*) FROM ticket WHERE first_resolved_assignee_id IS NULL AND first_resolved_at >= $1
                 AND first_resolved_at < $2)::int AS unassigned,
              (SELECT ROUND(AVG(EXTRACT(EPOCH FROM first_resolved_at - submitted_at)))::bigint FROM ticket
                 WHERE first_resolved_at >= $1 AND first_resolved_at < $2) AS avg_seconds,
              (SELECT COUNT(*) FROM ticket WHERE status IN ('OPEN','IN_PROGRESS')
                 AND submitted_at < now() - interval '72 hours')::int AS backlog`,
      [ws, new Date(ws.getTime() + 7 * 86400 * 1000)]);
    return row;
  };

  await login(page, run.admin());
  await page.getByTestId('nav-report').click();
  await expect(page).toHaveURL(/\/admin\/reports\/weekly$/);
  const expected = await expectedSql();

  const sunday = new Date(ws.getTime() + 6 * 86400 * 1000);
  await expect(page.getByTestId('report-week'))
    .toHaveText(`${companyDate(ws)}（周一）至 ${companyDate(sunday)}（周日）`);

  const catRow = (id: number) => page.locator(`[data-testid="report-category-row"][data-category-id="${id}"]`);
  await expect(catRow(cx.id).getByTestId('report-count')).toHaveText('2');
  await expect(catRow(cy.id).getByTestId('report-count')).toHaveText('1');
  await expect(catRow(cy.id).getByTestId('report-category-name')).toHaveText(`${cy.name}（已移除）`);
  await expect(catRow(cz.id).getByTestId('report-count')).toHaveText('0');

  const staffRow = (id: number) => page.locator(`[data-testid="report-staff-row"][data-user-id="${id}"]`);
  await expect(staffRow(s1.id).getByTestId('report-count')).toHaveText('2');
  await expect(staffRow(s2.id).getByTestId('report-count')).toHaveText('0');
  await expect(page.getByTestId('report-unassigned').getByTestId('report-count'))
    .toHaveText(String(expected.unassigned));
  expect(expected.unassigned).toBeGreaterThanOrEqual(1);

  const avg = page.getByTestId('report-avg');
  await expect(avg).toHaveAttribute('data-avg-seconds', String(expected.avg_seconds));
  const minutes = Math.round(Number(expected.avg_seconds) / 60);
  await expect(avg).toHaveText(`${Math.floor(minutes / 60)} 小时 ${minutes % 60} 分钟`);
});
