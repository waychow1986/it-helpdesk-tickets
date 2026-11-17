import { activateViaUi, createUserViaUi, deactivateViaUi, expect, expectLoginRefused, login, logout,
  randomPassword, submitTicketViaUi, test, userRow, userStatus } from '../support/fixtures';

// V2 IQA-003 管理员停用与重新启用账号（AC-005、AC-006；FR-007～FR-010、FR-016；TD-023、TD-024、TD-027）
// 凭据只以运行时随机值存在，断言页面不回显。

test('停用后已登录会话被引导至登录入口、正确与错误凭据提示一致、登录名仍被占用、重新启用后原凭据可用',
  async ({ page, browser, run }) => {
    const cat = await run.category('停用');
    const e2 = { login: run.name('e2'), password: randomPassword(), display: run.name('员工E2'),
      role: 'EMPLOYEE' as const };

    // A1 创建 E2；1. 列表显示“启用”
    await login(page, run.admin());
    await createUserViaUi(page, e2);
    await expect(page.getByTestId('flash')).toHaveText('账号已创建');
    expect(await userStatus(page, e2.login)).toEqual({ text: '启用', active: true });
    await expect(userRow(page, e2.login).getByTestId('user-deactivate')).toHaveCount(1);
    await expect(userRow(page, e2.login).getByTestId('user-activate')).toHaveCount(0);

    // 2. E2 在独立浏览器上下文中登录并保持登录
    const e2Context = await browser.newContext({ baseURL: test.info().project.use.baseURL });
    const e2Page = await e2Context.newPage();
    await login(e2Page, e2);
    await e2Page.goto('/tickets');
    await expect(e2Page.getByTestId('list-title')).toHaveText('我的工单');

    // 3. A1 停用 E2：列表显示“停用”，姓名与身份不变
    await deactivateViaUi(page, e2.login);
    await expect(page.getByTestId('flash')).toHaveText('已停用该账号');
    const row = userRow(page, e2.login);
    await expect(row.getByTestId('user-row-status')).toHaveText('停用');
    await expect(row.getByTestId('user-row-status')).toHaveAttribute('data-active', 'false');
    await expect(row.getByTestId('user-row-display')).toHaveText(e2.display);
    await expect(row.getByTestId('user-row-role')).toHaveText('员工');
    await expect(row.getByTestId('user-activate')).toHaveCount(1);
    // 停用已停用的账号：不产生变更
    const stale = await page.request.post(`/admin/users/${await row.getAttribute('data-user-id')}/deactivate`, {
      form: { _csrf: (await page.locator('input[name="_csrf"]').first().getAttribute('value'))! },
      maxRedirects: 0 });
    expect(stale.status()).toBe(302);
    await page.goto('/admin/users');
    await expect(page.getByTestId('flash')).toHaveText('账号已是停用状态');

    // 4. E2 的下一次操作被引导至登录入口
    await e2Page.goto('/tickets');
    await expect(e2Page).toHaveURL(/\/login$/);
    await expect(e2Page.getByTestId('logout')).toHaveCount(0);
    await e2Page.goto('/tickets/new');
    await expect(e2Page).toHaveURL(/\/login$/);

    // 5. 正确凭据与错误凭据：都被拒且提示一致
    const right = await expectLoginRefused(e2Page, e2);
    const wrong = await expectLoginRefused(e2Page, { login: e2.login, password: `${e2.password}x` });
    expect(right).toBe('登录名或密码错误');
    expect(wrong).toBe(right);
    expect((await e2Page.content()).includes(e2.password)).toBe(false);

    // 6. 以 E2 的登录名创建新账号被拒
    await createUserViaUi(page, { ...e2, display: run.name('同名'), password: randomPassword() });
    await expect(page.getByTestId('field-error-loginName')).toHaveText('登录名已存在');
    await expect(page.locator(`[data-testid="user-row"][data-login-name="${e2.login}"]`)).toHaveCount(1);
    expect((await page.content()).includes(e2.password)).toBe(false);

    // 7. 重新启用；E2 用原凭据登录、提交并查看工单
    await activateViaUi(page, e2.login);
    await expect(page.getByTestId('flash')).toHaveText('已启用该账号');
    expect(await userStatus(page, e2.login)).toEqual({ text: '启用', active: true });
    await login(e2Page, e2);
    await expect(e2Page.getByTestId('current-user')).toContainText('员工');
    const id = await submitTicketViaUi(e2Page, { title: run.name('启用后提交'), description: '合成描述',
      category: cat.name, urgency: '中' });
    await e2Page.goto('/tickets');
    await expect(e2Page.locator(`[data-testid="ticket-row"][data-ticket-id="${id}"]`)).toHaveCount(1);
    await logout(e2Page);
    await e2Context.close();
  });
