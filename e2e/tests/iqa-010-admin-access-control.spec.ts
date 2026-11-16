import { expect, login, logout, postWithCsrf, test } from '../support/fixtures';

// IQA-010 管理功能与报表的访问控制（AC-003、AC-019；FR-003、FR-025）
test('员工与 IT 同事无管理与报表入口，直接访问均被拒且不显示数据', async ({ page, run }) => {
  const e1 = await run.user('e1', 'EMPLOYEE');
  const t1 = await run.user('t1', 'IT_STAFF');
  for (const account of [e1, t1]) {
    await login(page, account);
    for (const nav of ['nav-admin-users', 'nav-admin-categories', 'nav-report']) {
      await expect(page.getByTestId(nav)).toHaveCount(0);
    }
    for (const path of ['/admin/users', '/admin/categories', '/admin/reports/weekly']) {
      const response = await page.goto(path);
      expect(response?.status()).toBe(403);
      await expect(page.getByTestId('forbidden')).toHaveText('您无权访问该页面或执行该操作');
      for (const leak of ['user-row', 'category-row', 'report-week', 'report-backlog', 'report-avg']) {
        await expect(page.getByTestId(leak)).toHaveCount(0);
      }
    }
    await page.goto('/tickets');
    const roleChange = await postWithCsrf(page, `/admin/users/${account.id}/role`, { role: 'IT_STAFF' });
    expect(roleChange.status()).toBe(403);
    const createCategory = await postWithCsrf(page, '/admin/categories', { name: run.name('越权类别') });
    expect(createCategory.status()).toBe(403);
    await logout(page);
  }
});
