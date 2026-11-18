import { activateViaUi, commentViaUi, changeStatusViaUi, db, deactivateViaUi, expect, listedTicketIds, login,
  logout, test } from '../support/fixtures';
import type { Page } from '@playwright/test';

// V2 IQA-004 停用 IT 同事不再可分派且历史照常显示（AC-007、AC-008；FR-011、FR-012、FR-013；BQ-003 人工答案 A；TD-026）

async function assignOptions(page: Page): Promise<string[]> {
  return page.getByTestId('assign-select').locator('option:not([value=""])').allTextContents();
}

test('停用 T1 后分派被拒、下拉不含 T1、名下工单与历史照常、报表计入，重新启用后重新可分派',
  async ({ page, browser, run }) => {
    const t1 = await run.user('t1', 'IT_STAFF');
    const t2 = await run.user('t2', 'IT_STAFF');
    const t3 = await run.user('t3', 'IT_STAFF');
    const e1 = await run.user('e1', 'EMPLOYEE');
    const cat = await run.category('停用分派');
    const w1 = await db.insertTicket({ title: run.name('W1'), categoryId: cat.id, submitterId: e1.id });
    const w2 = await db.insertTicket({ title: run.name('W2'), categoryId: cat.id, submitterId: e1.id });
    const reply = run.name('T1回复');

    // 前置：T1 认领 W1 并回复、改为处理中；认领 W2 并改为已解决（首次已解决在本周）
    await login(page, t1);
    await page.goto(`/tickets/${w1}`);
    await page.getByTestId('claim-button').click();
    await commentViaUi(page, w1, reply);
    await changeStatusViaUi(page, w1, '处理中');
    await page.goto(`/tickets/${w2}`);
    await page.getByTestId('claim-button').click();
    await changeStatusViaUi(page, w2, '已解决');
    await logout(page);

    // 1. T2 打开 W1 的分派对象列表并保持页面不刷新
    const t2Context = await browser.newContext({ baseURL: test.info().project.use.baseURL });
    const t2Page = await t2Context.newPage();
    await login(t2Page, t2);
    await t2Page.goto(`/tickets/${w1}`);
    expect(await assignOptions(t2Page)).toContain(t1.display);
    const eventsBefore = await t2Page.getByTestId('event-item').count();

    // 2. A1 停用 T1
    await login(page, run.admin());
    await deactivateViaUi(page, t1.login);
    await expect(page.getByTestId('flash')).toHaveText('已停用该账号');

    // 3. T2 在旧页面上提交分派给 T1：被拒、W1 不变
    await t2Page.getByTestId('assign-select').selectOption({ label: t1.display });
    await t2Page.getByTestId('assign-submit').click();
    await expect(t2Page.getByTestId('flash')).toHaveText('该 IT 同事已停用，不能分派，请刷新后重新选择');
    await expect(t2Page.getByTestId('ticket-assignee')).toHaveText(t1.display);
    await expect(t2Page.getByTestId('ticket-status')).toHaveText('处理中');
    await expect(t2Page.getByTestId('event-item')).toHaveCount(eventsBefore);

    // 4. 刷新后分派对象不含 T1
    await t2Page.reload();
    const refreshed = await assignOptions(t2Page);
    expect(refreshed).not.toContain(t1.display);
    expect(refreshed).toContain(t3.display);

    // 5. E1、T2 查看列表与详情；T2 按处理人 T1 筛选
    const checkHistory = async (p: Page) => {
      await p.goto(`/tickets?q=${encodeURIComponent(run.name(''))}`);
      for (const id of [w1, w2]) {
        await expect(p.locator(`[data-testid="ticket-row"][data-ticket-id="${id}"]`).getByTestId('row-assignee'))
          .toHaveText(t1.display);
      }
      await p.goto(`/tickets/${w1}`);
      await expect(p.getByTestId('ticket-assignee')).toHaveText(t1.display);
      await expect(p.getByTestId('ticket-status')).toHaveText('处理中');
      const comment = p.getByTestId('comment-item').filter({ hasText: reply });
      await expect(comment.getByTestId('comment-author')).toHaveText(t1.display);
      await expect(comment.getByTestId('comment-role')).toHaveText('IT 同事');
      await expect(p.getByTestId('event-item').first().getByTestId('event-actor')).toHaveText(t1.display);
      await p.goto(`/tickets/${w2}`);
      await expect(p.getByTestId('ticket-assignee')).toHaveText(t1.display);
      await expect(p.getByTestId('ticket-status')).toHaveText('已解决');
    };
    await checkHistory(t2Page);
    await t2Page.goto('/tickets');
    const assigneeFilter = await t2Page.getByTestId('filter-assignee').locator('option').allTextContents();
    expect(assigneeFilter).toContain(t1.display);
    await t2Page.goto(`/tickets?assignee=${t1.id}`);
    expect((await listedTicketIds(t2Page)).sort()).toEqual([w1, w2].sort());
    const e1Context = await browser.newContext({ baseURL: test.info().project.use.baseURL });
    const e1Page = await e1Context.newPage();
    await login(e1Page, e1);
    await checkHistory(e1Page);
    await e1Context.close();

    // 6. A1 打开周报表：T1 的处理量照常计入 W2
    await page.getByTestId('nav-report').click();
    await expect(page.locator(`[data-testid="report-staff-row"][data-user-id="${t1.id}"]`)
      .getByTestId('report-count')).toHaveText('1');

    // 7. T3 认领 W1：处理记录前值 T1、后值 T3
    const t3Context = await browser.newContext({ baseURL: test.info().project.use.baseURL });
    const t3Page = await t3Context.newPage();
    await login(t3Page, t3);
    await t3Page.goto(`/tickets/${w1}`);
    await t3Page.getByTestId('claim-button').click();
    await expect(t3Page.getByTestId('ticket-assignee')).toHaveText(t3.display);
    const last = t3Page.getByTestId('event-item').last();
    await expect(last.getByTestId('event-type')).toHaveText('认领');
    await expect(last.getByTestId('event-from')).toHaveText(t1.display);
    await expect(last.getByTestId('event-to')).toHaveText(t3.display);
    await t3Context.close();

    // 8. A1 重新启用 T1；T2 再看分派对象：重新出现 T1
    await activateViaUi(page, t1.login);
    await expect(page.getByTestId('flash')).toHaveText('已启用该账号');
    await t2Page.goto(`/tickets/${w2}`);
    expect(await assignOptions(t2Page)).toContain(t1.display);
    await t2Context.close();
  });
