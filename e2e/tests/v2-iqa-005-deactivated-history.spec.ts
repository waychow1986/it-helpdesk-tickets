import { activateViaUi, changeStatusViaUi, commentViaUi, db, deactivateViaUi, expect, login, logout,
  test } from '../support/fixtures';

// V2 IQA-005 停用员工的历史工单与沟通记录照常显示（AC-008；FR-013）

test('停用 E3 后其工单在列表、搜索与详情中照常，IT 同事可继续处理；重新启用后 E3 看到全部记录',
  async ({ page, browser, run }) => {
    const e3 = await run.user('e3', 'EMPLOYEE');
    const t1 = await run.user('t1', 'IT_STAFF');
    const cat = await run.category('历史');
    const title = run.name('W3-打印机卡纸');
    const w3 = await db.insertTicket({ title, categoryId: cat.id, submitterId: e3.id });
    const note = run.name('E3补充说明');
    const reply = run.name('T1回复');
    const replyDuring = run.name('停用期间回复');

    // 前置：E3 补充说明；T1 回复并认领
    await login(page, e3);
    await commentViaUi(page, w3, note);
    await logout(page);
    await login(page, t1);
    await commentViaUi(page, w3, reply);
    await page.getByTestId('claim-button').click();
    await expect(page.getByTestId('ticket-assignee')).toHaveText(t1.display);

    // 1. A1 停用 E3
    const adminContext = await browser.newContext({ baseURL: test.info().project.use.baseURL });
    const admin = await adminContext.newPage();
    await login(admin, run.admin());
    await deactivateViaUi(admin, e3.login);
    await expect(admin.getByTestId('flash')).toHaveText('已停用该账号');

    // 2. T1：全部工单列表、按标题片段搜索、详情；回复并修改状态
    await page.goto('/tickets');
    await expect(page.locator(`[data-testid="ticket-row"][data-ticket-id="${w3}"]`).getByTestId('row-submitter'))
      .toHaveText(e3.display);
    await page.goto(`/tickets?q=${encodeURIComponent(run.name('W3-打印'))}`);
    await expect(page.getByTestId('ticket-row')).toHaveCount(1);
    await expect(page.locator(`[data-testid="ticket-row"][data-ticket-id="${w3}"]`)).toHaveCount(1);
    await page.goto(`/tickets/${w3}`);
    await expect(page.getByTestId('detail-submitter')).toHaveText(e3.display);
    const noteItem = page.getByTestId('comment-item').filter({ hasText: note });
    await expect(noteItem.getByTestId('comment-author')).toHaveText(e3.display);
    await expect(noteItem.getByTestId('comment-role')).toHaveText('员工');
    await expect(page.getByTestId('comment-item').filter({ hasText: reply })).toHaveCount(1);
    const events = await page.getByTestId('event-item').count();
    await commentViaUi(page, w3, replyDuring);
    await changeStatusViaUi(page, w3, '处理中');
    await expect(page.getByTestId('event-item')).toHaveCount(events + 1);
    await expect(page.getByTestId('event-item').last().getByTestId('event-to')).toHaveText('处理中');
    await logout(page);

    // 3. A1 重新启用 E3；E3 登录查看我的工单与 W3 详情
    await activateViaUi(admin, e3.login);
    await expect(admin.getByTestId('flash')).toHaveText('已启用该账号');
    await adminContext.close();
    await login(page, e3);
    await page.goto('/tickets');
    await expect(page.locator(`[data-testid="ticket-row"][data-ticket-id="${w3}"]`)).toHaveCount(1);
    await page.goto(`/tickets/${w3}`);
    for (const text of [note, reply, replyDuring]) {
      await expect(page.getByTestId('comment-item').filter({ hasText: text })).toHaveCount(1);
    }
    await expect(page.getByTestId('ticket-status')).toHaveText('处理中');
    await expect(page.getByTestId('event-item')).toHaveCount(events + 1);
    await expect(page.getByTestId('ticket-assignee')).toHaveText(t1.display);
  });
