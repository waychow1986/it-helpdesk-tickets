import { db, expect, login, logout, test } from '../support/fixtures';

// IQA-004 补充说明与回复的双向沟通（AC-008、AC-013；FR-012、FR-018、FR-019）
test('补充说明与回复按时间显示、空内容被拒、不改变状态与处理人、已关闭仍可补充', async ({ page, run }) => {
  const e1 = await run.user('e1', 'EMPLOYEE');
  const t1 = await run.user('t1', 'IT_STAFF');
  const cat = await run.category('沟通');
  const id = await db.insertTicket({ title: run.name('沟通工单'), categoryId: cat.id, submitterId: e1.id });

  await login(page, e1);
  await page.goto(`/tickets/${id}`);
  await page.getByTestId('comment-body').fill('员工补充：重启后依旧无法联网');
  await page.getByTestId('comment-submit').click();
  await expect(page.getByTestId('flash')).toHaveText('补充说明已添加');
  await page.getByTestId('comment-body').fill('   ');
  await page.getByTestId('comment-submit').click();
  await expect(page.getByTestId('field-error-body')).toHaveText('内容不能为空');
  await expect(page.getByTestId('comment-item')).toHaveCount(1);
  await logout(page);

  await login(page, t1);
  await page.goto(`/tickets/${id}`);
  await page.getByTestId('comment-body').fill('IT 回复：请检查网线');
  await page.getByTestId('comment-submit').click();
  await expect(page.getByTestId('flash')).toHaveText('回复已添加');
  await page.getByTestId('comment-submit').click();
  await expect(page.getByTestId('field-error-body')).toHaveText('内容不能为空');
  const staffView = await page.getByTestId('comment-item').allInnerTexts();
  await expect(page.getByTestId('ticket-status')).toHaveText('待处理');
  await expect(page.getByTestId('ticket-assignee')).toHaveText('未分配');
  await logout(page);

  await login(page, e1);
  await page.goto(`/tickets/${id}`);
  const items = page.getByTestId('comment-item');
  await expect(items).toHaveCount(2);
  await expect(items.nth(0).getByTestId('comment-author')).toHaveText(e1.display);
  await expect(items.nth(0).getByTestId('comment-role')).toHaveText('员工');
  await expect(items.nth(0).getByTestId('comment-text')).toHaveText('员工补充：重启后依旧无法联网');
  await expect(items.nth(0).getByTestId('comment-time')).toHaveText(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/);
  await expect(items.nth(1).getByTestId('comment-author')).toHaveText(t1.display);
  await expect(items.nth(1).getByTestId('comment-role')).toHaveText('IT 同事');
  await expect(items.nth(1).getByTestId('comment-text')).toHaveText('IT 回复：请检查网线');
  expect(await items.allInnerTexts()).toEqual(staffView);
  await expect(page.getByTestId('ticket-status')).toHaveText('待处理');
  await expect(page.getByTestId('ticket-assignee')).toHaveText('未分配');
  await logout(page);

  // 已关闭工单仍可补充说明
  await login(page, t1);
  await page.goto(`/tickets/${id}`);
  await page.getByTestId('status-select').selectOption('CLOSED');
  await page.getByTestId('status-submit').click();
  await expect(page.getByTestId('ticket-status')).toHaveText('已关闭');
  await logout(page);
  await login(page, e1);
  await page.goto(`/tickets/${id}`);
  await page.getByTestId('comment-body').fill('关闭后再补充一条');
  await page.getByTestId('comment-submit').click();
  await expect(page.getByTestId('comment-item')).toHaveCount(3);
  await expect(page.getByTestId('ticket-status')).toHaveText('已关闭');
});
