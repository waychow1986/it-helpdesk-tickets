import { expect, listedTicketIds, login, logout, submitTicketViaUi, test } from '../support/fixtures';

// IQA-002 员工提交工单与必填校验（AC-006；FR-007、FR-008、FR-009；PQ-001）
test('紧急程度三级、逐项必填校验并保留已填内容、提交后出现在两个列表', async ({ page, run }) => {
  const e1 = await run.user('e1', 'EMPLOYEE');
  const t1 = await run.user('t1', 'IT_STAFF');
  const cat = await run.category('电脑');

  await login(page, e1);
  await page.goto('/tickets/new');
  const urgencyOptions = await page.getByTestId('ticket-urgency').locator('option:not([value=""])').allTextContents();
  expect(urgencyOptions).toEqual(['低', '中', '高']);

  const full = { title: '合成标题-校验', description: '合成描述-校验', category: cat.name, urgency: '中' };
  const fields: Array<[keyof typeof full, string, string]> = [
    ['title', 'field-error-title', '请填写标题'],
    ['description', 'field-error-description', '请填写问题描述'],
    ['category', 'field-error-categoryId', '请选择类别'],
    ['urgency', 'field-error-urgency', '请选择紧急程度'],
  ];
  for (const [missing, errorId, message] of fields) {
    await page.goto('/tickets/new');
    if (missing !== 'title') await page.getByTestId('ticket-title').fill(full.title);
    else await page.getByTestId('ticket-title').fill('   ');
    if (missing !== 'description') await page.getByTestId('ticket-description').fill(full.description);
    if (missing !== 'category') await page.getByTestId('ticket-category').selectOption({ label: full.category });
    if (missing !== 'urgency') await page.getByTestId('ticket-urgency').selectOption({ label: full.urgency });
    await page.getByTestId('ticket-submit').click();
    await expect(page.getByTestId(errorId)).toHaveText(message);
    await expect(page.locator('[data-testid^="field-error-"]')).toHaveCount(1);
    if (missing !== 'title') await expect(page.getByTestId('ticket-title')).toHaveValue(full.title);
    if (missing !== 'description') await expect(page.getByTestId('ticket-description')).toHaveValue(full.description);
    if (missing !== 'category') {
      await expect(page.getByTestId('ticket-category')).toHaveValue(String(cat.id));
    }
    if (missing !== 'urgency') await expect(page.getByTestId('ticket-urgency')).toHaveValue('MEDIUM');
  }

  const ids: number[] = [];
  for (const urgency of ['低', '中', '高'] as const) {
    const id = await submitTicketViaUi(page, { title: `合成工单-${urgency}`, description: `描述-${urgency}`,
      category: cat.name, urgency });
    ids.push(id);
    await expect(page.getByTestId('flash')).toHaveText('工单已提交');
    await expect(page.getByTestId('ticket-id')).toHaveText(`#${id}`);
    await expect(page.getByTestId('detail-urgency')).toHaveText(urgency);
    await expect(page.getByTestId('ticket-status')).toHaveText('待处理');
    await expect(page.getByTestId('ticket-assignee')).toHaveText('未分配');
    await expect(page.getByTestId('detail-submitter')).toHaveText(e1.display);
    await expect(page.getByTestId('detail-submitted')).toHaveText(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/);
    await expect(page.getByTestId('detail-category')).toHaveText(cat.name);
    // 提交后无修改基本信息的入口
    for (const editable of ['ticket-title', 'ticket-description', 'ticket-category', 'ticket-urgency']) {
      await expect(page.getByTestId(editable)).toHaveCount(0);
    }
  }
  expect(new Set(ids).size).toBe(3);

  await page.goto('/tickets');
  await expect(page.getByTestId('list-title')).toHaveText('我的工单');
  expect((await listedTicketIds(page)).sort()).toEqual([...ids].sort());
  await logout(page);

  await login(page, t1);
  await page.goto(`/tickets?categoryId=${cat.id}`);
  await expect(page.getByTestId('list-title')).toHaveText('全部工单');
  expect((await listedTicketIds(page)).sort()).toEqual([...ids].sort());
  await page.goto('/tickets');
  const all = await listedTicketIds(page);
  for (const id of ids) expect(all).toContain(id);
});
