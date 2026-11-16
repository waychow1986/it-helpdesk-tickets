import { createUserViaUi, expect, login, logout, randomPassword, test } from '../support/fixtures';

// IQA-001 管理员创建账号与登录（AC-001、AC-002、AC-003；FR-001、FR-002、FR-003）
test('无注册入口、未登录被引导、管理员创建三种身份账号并按身份登录', async ({ page, run }) => {
  await page.goto('/login');
  await expect(page.getByTestId('login-username')).toBeVisible();
  await expect(page.locator('body')).not.toContainText('注册');
  await expect(page.locator('a[href*="register"], a[href*="signup"]')).toHaveCount(0);

  await page.goto('/tickets');
  await expect(page).toHaveURL(/\/login$/);

  await login(page, run.admin());
  const accounts = [
    { login: run.name('emp'), password: randomPassword(), display: run.name('员工'), role: 'EMPLOYEE' as const },
    { login: run.name('it'), password: randomPassword(), display: run.name('IT同事'), role: 'IT_STAFF' as const },
    { login: run.name('adm'), password: randomPassword(), display: run.name('管理员'), role: 'ADMIN' as const },
  ];
  for (const a of accounts) {
    await createUserViaUi(page, a);
    await expect(page.getByTestId('flash')).toHaveText('账号已创建');
    const row = page.locator(`[data-testid="user-row"][data-login-name="${a.login}"]`);
    await expect(row).toHaveCount(1);
    await expect(row.getByTestId('user-row-display')).toHaveText(a.display);
  }

  // 重复登录名被拒，且密码不回显
  await createUserViaUi(page, { ...accounts[0], display: '重复' });
  await expect(page.getByTestId('field-error-loginName')).toHaveText('登录名已存在');
  await expect(page.getByTestId('user-password')).toHaveValue('');
  await expect(page.locator(`[data-testid="user-row"][data-login-name="${accounts[0].login}"]`)).toHaveCount(1);
  await logout(page);

  const allowed: Record<string, string[]> = {
    EMPLOYEE: ['nav-my-tickets', 'nav-new-ticket'],
    IT_STAFF: ['nav-all-tickets'],
    ADMIN: ['nav-admin-users', 'nav-admin-categories', 'nav-report'],
  };
  const allNav = ['nav-my-tickets', 'nav-new-ticket', 'nav-all-tickets', 'nav-admin-users', 'nav-admin-categories',
    'nav-report'];
  for (const a of accounts) {
    await login(page, a);
    for (const nav of allNav) {
      await expect(page.getByTestId(nav)).toHaveCount(allowed[a.role].includes(nav) ? 1 : 0);
    }
    await logout(page);
  }

  // 错误凭据登录失败
  await page.goto('/login');
  await page.getByTestId('login-username').fill(accounts[0].login);
  await page.getByTestId('login-password').fill(accounts[0].password + 'x');
  await page.getByTestId('login-submit').click();
  await expect(page.getByTestId('login-error')).toHaveText('登录名或密码错误');
  await expect(page.getByTestId('logout')).toHaveCount(0);
});
