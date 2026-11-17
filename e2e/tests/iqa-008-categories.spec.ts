import { createCategoryViaUi, expect, listedTicketIds, login, logout, submitTicketViaUi, test } from '../support/fixtures';

// IQA-008 管理员维护类别（AC-005；FR-005、FR-006）
test('新增、空名与重名被拒、改名后工单显示新名、移除后不可选但历史可筛选', async ({ page, run }) => {
  const e1 = await run.user('e1', 'EMPLOYEE');
  const t1 = await run.user('t1', 'IT_STAFF');
  const nameA = run.name('测试类别甲');
  const nameB = run.name('测试类别乙');

  await login(page, run.admin());
  const catId = await createCategoryViaUi(page, nameA);
  await expect(page.getByTestId('flash')).toHaveText('类别已新增');
  await logout(page);

  await login(page, e1);
  const ticketId = await submitTicketViaUi(page, { title: run.name('类别工单'), description: '用于类别测试',
    category: nameA, urgency: '低' });
  await logout(page);

  await login(page, run.admin());
  await page.goto('/admin/categories');
  await page.getByTestId('category-new-name').fill('   ');
  await page.getByTestId('category-create').click();
  await expect(page.getByTestId('field-error-name')).toHaveText('类别名称不能为空');
  await page.getByTestId('category-new-name').fill(nameA);
  await page.getByTestId('category-create').click();
  await expect(page.getByTestId('field-error-name')).toHaveText('类别名称已存在');
  await expect(page.locator(`[data-testid="category-row"][data-category-name="${nameA}"]`)).toHaveCount(1);

  const row = page.locator(`[data-testid="category-row"][data-category-id="${catId}"]`);
  await row.getByTestId('category-rename-input').fill(nameB);
  await row.getByTestId('category-rename').click();
  await expect(page.getByTestId('flash')).toHaveText('类别已改名');
  await expect(page.locator(`[data-testid="category-row"][data-category-id="${catId}"]`)
    .getByTestId('category-row-name')).toHaveText(nameB);
  await logout(page);

  await login(page, e1);
  await page.goto(`/tickets/${ticketId}`);
  await expect(page.getByTestId('detail-category')).toHaveText(nameB);
  await logout(page);

  await login(page, run.admin());
  await page.goto('/admin/categories');
  await page.locator(`[data-testid="category-row"][data-category-id="${catId}"]`).getByTestId('category-remove')
    .click();
  await expect(page.getByTestId('flash')).toHaveText('类别已移除');
  await expect(page.locator(`[data-testid="category-row"][data-category-id="${catId}"]`)
    .getByTestId('category-row-name')).toHaveText(`${nameB}（已移除）`);
  await logout(page);

  await login(page, e1);
  await page.goto('/tickets/new');
  const options = await page.locator('[data-testid="ticket-category"] option').allTextContents();
  expect(options).not.toContain(nameB);
  expect(options).not.toContain(nameA);
  await page.goto(`/tickets/${ticketId}`);
  await expect(page.getByTestId('detail-category')).toHaveText(`${nameB}（已移除）`);
  await logout(page);

  await login(page, t1);
  await page.goto('/tickets');
  await page.getByTestId('filter-category').selectOption({ label: `${nameB}（已移除）` });
  await page.getByTestId('filter-submit').click();
  expect(await listedTicketIds(page)).toEqual([ticketId]);
});
