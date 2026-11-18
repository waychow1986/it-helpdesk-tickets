import { changeStatusViaUi, companyWeekStart, db, expect, login, logout, test } from '../support/fixtures';

// V2 IQA-008 周报表本周超时解决的工单数量（AC-011、AC-012；FR-017、FR-018；BQ-002 人工答案 A；TD-028）
// P1～P4 按数据库数据契约回溯插入（P3 上周首次解决、本周再次解决只追加事件；P4 首次解决后改回待处理），
// P5、P6 由 IT 同事在页面上改状态。期望值以独立 SQL 在同一时刻按 FR-017 与 V1 口径计算。

const HOUR = 3600 * 1000;

test('第五项只计本周首次解决且晚于期望解决时间的工单，V1 四项口径不变，员工与 IT 同事无权访问',
  async ({ page, run }) => {
    const now = new Date();
    const ws = companyWeekStart(now);
    const span = now.getTime() - ws.getTime();
    const inWeek = (f: number) => new Date(ws.getTime() + Math.floor(span * f));
    const emp = await run.user('emp', 'EMPLOYEE');
    const st = await run.user('st', 'IT_STAFF');
    const cat = await run.category('超时解决');
    const base = { categoryId: cat.id, submitterId: emp.id, assigneeId: st.id, actorId: st.id };

    const p1Resolved = inWeek(0.5);
    const p1 = await db.insertTicket({ ...base, title: run.name('P1'), urgency: 'HIGH',
      submittedAt: new Date(p1Resolved.getTime() - 5 * HOUR), status: 'RESOLVED', firstResolvedAt: p1Resolved,
      firstResolvedAssigneeId: st.id });
    const p2Resolved = inWeek(0.6);
    const p2 = await db.insertTicket({ ...base, title: run.name('P2'), urgency: 'MEDIUM',
      submittedAt: new Date(p2Resolved.getTime() - 2 * HOUR), status: 'RESOLVED', firstResolvedAt: p2Resolved,
      firstResolvedAssigneeId: st.id });
    const p3 = await db.insertTicket({ ...base, title: run.name('P3'), urgency: 'LOW',
      submittedAt: new Date(ws.getTime() - 100 * HOUR), status: 'RESOLVED',
      firstResolvedAt: new Date(ws.getTime() - 20 * HOUR), firstResolvedAssigneeId: st.id });
    await db.appendStatusEvent(p3, st.id, 'RESOLVED', 'IN_PROGRESS', inWeek(0.2));
    await db.appendStatusEvent(p3, st.id, 'IN_PROGRESS', 'RESOLVED', inWeek(0.3));
    const p4Resolved = inWeek(0.4);
    const p4 = await db.insertTicket({ ...base, title: run.name('P4'), urgency: 'HIGH',
      submittedAt: new Date(p4Resolved.getTime() - 6 * HOUR), status: 'OPEN', firstResolvedAt: p4Resolved,
      firstResolvedAssigneeId: st.id });
    await db.appendStatusEvent(p4, st.id, 'RESOLVED', 'OPEN', inWeek(0.45));
    const p5 = await db.insertTicket({ ...base, title: run.name('P5'), urgency: 'HIGH', assigneeId: null,
      submittedAt: new Date(now.getTime() - 5 * HOUR) });
    const p6 = await db.insertTicket({ ...base, title: run.name('P6'), urgency: 'HIGH', assigneeId: null,
      submittedAt: new Date(now.getTime() - 5 * HOUR) });

    // 1. IT 同事把 P5 直接改为已关闭、把 P6 标记为已解决
    await login(page, st);
    await changeStatusViaUi(page, p5, '已关闭');
    await changeStatusViaUi(page, p6, '已解决');

    // 3（先行）. 员工、IT 同事访问报表被拒
    expect((await page.goto('/admin/reports/weekly'))?.status()).toBe(403);
    await expect(page.getByTestId('report-overdue-resolved')).toHaveCount(0);
    await page.goto('/tickets');
    await logout(page);
    await login(page, emp);
    expect((await page.goto('/admin/reports/weekly'))?.status()).toBe(403);
    await expect(page.getByTestId('report-overdue-resolved')).toHaveCount(0);
    await page.goto('/tickets');
    await logout(page);

    // 合成数据清单的独立核对：计入 P1、P4、P6，不计 P2、P3、P5
    expect(await db.overdueResolvedIds(ws, [p1, p2, p3, p4, p5, p6])).toEqual([p1, p4, p6].sort((a, b) => a - b));

    // 2. 管理员打开周报表，与同一时刻的独立 SQL 比对
    await login(page, run.admin());
    await page.getByTestId('nav-report').click();
    await expect(page).toHaveURL(/\/admin\/reports\/weekly$/);
    const weekEnd = new Date(ws.getTime() + 7 * 86400 * 1000);
    const [v1] = await db.query(
      `SELECT (SELECT COUNT(*) FROM ticket WHERE category_id = $3 AND submitted_at >= $1 AND submitted_at < $2)::int
                AS category_count,
              (SELECT COUNT(*) FROM ticket WHERE first_resolved_assignee_id = $4 AND first_resolved_at >= $1
                AND first_resolved_at < $2)::int AS staff_count,
              (SELECT COUNT(*) FROM ticket WHERE first_resolved_assignee_id IS NULL AND first_resolved_at >= $1
                AND first_resolved_at < $2)::int AS unassigned,
              (SELECT ROUND(AVG(EXTRACT(EPOCH FROM first_resolved_at - submitted_at)))::bigint FROM ticket
                WHERE first_resolved_at >= $1 AND first_resolved_at < $2) AS avg_seconds,
              (SELECT COUNT(*) FROM ticket WHERE status IN ('OPEN','IN_PROGRESS')
                AND submitted_at < now() - interval '72 hours')::int AS backlog`,
      [ws, weekEnd, cat.id, st.id]);
    const fifth = (await db.overdueResolvedIds(ws)).length;

    await expect(page.getByTestId('report-overdue-resolved')).toHaveText(String(fifth));
    expect(fifth).toBeGreaterThanOrEqual(3);
    await expect(page.locator(`[data-testid="report-category-row"][data-category-id="${cat.id}"]`)
      .getByTestId('report-count')).toHaveText(String(v1.category_count));
    await expect(page.locator(`[data-testid="report-staff-row"][data-user-id="${st.id}"]`)
      .getByTestId('report-count')).toHaveText(String(v1.staff_count));
    expect(v1.staff_count).toBe(3);
    await expect(page.getByTestId('report-unassigned').getByTestId('report-count'))
      .toHaveText(String(v1.unassigned));
    await expect(page.getByTestId('report-avg')).toHaveAttribute('data-avg-seconds', String(v1.avg_seconds));
    await expect(page.getByTestId('report-backlog')).toHaveText(String(v1.backlog));
    // 第五项位于 V1 四项之后
    const html = await page.content();
    expect(html.indexOf('report-backlog')).toBeLessThan(html.indexOf('report-overdue-resolved'));
  });
