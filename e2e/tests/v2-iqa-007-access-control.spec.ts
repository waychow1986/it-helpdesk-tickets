import { deactivateViaUi, expect, login, logout, postWithCsrf, test, userStatus } from '../support/fixtures';

// V2 IQA-007 停用与启用的访问控制（AC-010；FR-015；TD-027）：员工与 IT 同事无入口，直接请求被拒，账号状态不变。

test('员工与 IT 同事看不到停用 / 启用入口，直接请求得 403，E4 仍启用、E5 仍停用', async ({ page, run }) => {
  const e1 = await run.user('e1', 'EMPLOYEE');
  const t1 = await run.user('t1', 'IT_STAFF');
  const e4 = await run.user('e4', 'EMPLOYEE');
  const e5 = await run.user('e5', 'EMPLOYEE');

  await login(page, run.admin());
  await deactivateViaUi(page, e5.login);
  await expect(page.getByTestId('flash')).toHaveText('已停用该账号');
  await logout(page);

  for (const actor of [e1, t1]) {
    // 1. 查找入口：导航与页面中均无账号管理或停用 / 启用入口
    await login(page, actor);
    await expect(page.getByTestId('nav-admin-users')).toHaveCount(0);
    await expect(page.locator('[data-testid="user-deactivate"], [data-testid="user-activate"]')).toHaveCount(0);
    const list = await page.goto('/admin/users');
    expect(list?.status()).toBe(403);
    await expect(page.locator('[data-testid="user-deactivate"], [data-testid="user-activate"]')).toHaveCount(0);

    // 2. 带 CSRF 的直接请求：停用 E4、启用 E5 均被拒
    await page.goto('/tickets');
    const deactivate = await postWithCsrf(page, `/admin/users/${e4.id}/deactivate`, {});
    expect(deactivate.status()).toBe(403);
    const activate = await postWithCsrf(page, `/admin/users/${e5.id}/activate`, {});
    expect(activate.status()).toBe(403);
    await logout(page);
  }

  // 3. A1 查看：E4 仍为启用、E5 仍为停用
  await login(page, run.admin());
  expect(await userStatus(page, e4.login)).toEqual({ text: '启用', active: true });
  expect(await userStatus(page, e5.login)).toEqual({ text: '停用', active: false });
});
