import { db, expect, login, logout, postWithCsrf, test } from '../support/fixtures';

// IQA-009 管理员设置与取消 IT 同事（AC-004；FR-004）
test('设为 IT 同事后获得处理能力，取消后回到员工能力且历史处理人保留', async ({ browser, page, run }) => {
  const u1 = await run.user('u1', 'EMPLOYEE');
  const t1 = await run.user('t1', 'IT_STAFF');
  const e1 = await run.user('e1', 'EMPLOYEE');
  const cat = await run.category('身份');
  const ticketA = await db.insertTicket({ title: run.name('工单A'), categoryId: cat.id, submitterId: e1.id });
  const ticketB = await db.insertTicket({ title: run.name('工单B'), categoryId: cat.id, submitterId: e1.id });

  const setRole = async (role: 'IT_STAFF' | 'EMPLOYEE') => {
    await page.goto('/admin/users');
    const row = page.locator(`[data-testid="user-row"][data-login-name="${u1.login}"]`);
    await row.getByTestId('role-select').selectOption(role);
    await row.getByTestId('role-submit').click();
    await expect(page.getByTestId('flash')).toHaveText('身份已更新');
    await expect(page.locator(`[data-testid="user-row"][data-login-name="${u1.login}"]`)
      .getByTestId('user-row-role')).toHaveText(role === 'IT_STAFF' ? 'IT 同事' : '员工');
  };

  await login(page, run.admin());
  await setRole('IT_STAFF');

  const u1Context = await browser.newContext();
  const u1Page = await u1Context.newPage();
  await login(u1Page, u1);
  await expect(u1Page.getByTestId('nav-all-tickets')).toHaveCount(1);
  await u1Page.goto(`/tickets/${ticketA}`);
  await u1Page.getByTestId('claim-button').click();
  await expect(u1Page.getByTestId('ticket-assignee')).toHaveText(u1.display);

  await setRole('EMPLOYEE');

  // 同一会话的下一次操作即按员工身份生效
  await u1Page.goto('/tickets');
  await expect(u1Page.getByTestId('nav-my-tickets')).toHaveCount(1);
  await expect(u1Page.getByTestId('nav-all-tickets')).toHaveCount(0);
  await expect(u1Page.getByTestId('list-title')).toHaveText('我的工单');
  const denied = await u1Page.goto(`/tickets/${ticketA}`);
  expect(denied?.status()).toBe(403);
  await u1Page.goto('/tickets');
  const claim = await postWithCsrf(u1Page, `/tickets/${ticketB}/claim`, {});
  expect(claim.status()).toBe(403);
  await u1Context.close();
  await logout(page);

  await login(page, t1);
  await page.goto(`/tickets/${ticketB}`);
  const options = await page.getByTestId('assign-select').locator('option').allTextContents();
  expect(options).not.toContain(u1.display);
  await page.goto(`/tickets/${ticketA}`);
  await expect(page.getByTestId('ticket-assignee')).toHaveText(u1.display);
  const rows = await db.query('SELECT assignee_id FROM ticket WHERE id = $1', [ticketB]);
  expect(rows[0].assignee_id).toBeNull();
});
