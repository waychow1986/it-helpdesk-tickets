import { db, expect, listedTicketIds, login, logout, test } from '../support/fixtures';

// IQA-005 IT 同事查看全部工单、认领与分派（AC-010、AC-011；FR-014、FR-015、FR-016、FR-020）
test('全部可见、认领、分派对象只含其他当前 IT 同事、再认领记录前后值', async ({ page, run }) => {
  const t1 = await run.user('t1', 'IT_STAFF');
  const t2 = await run.user('t2', 'IT_STAFF');
  const e1 = await run.user('e1', 'EMPLOYEE');
  const e2 = await run.user('e2', 'EMPLOYEE');
  const otherAdmin = await run.user('adm', 'ADMIN');
  const cat = await run.category('认领');
  const ticketE1 = await db.insertTicket({ title: run.name('E1工单'), categoryId: cat.id, submitterId: e1.id });
  const ticketE2 = await db.insertTicket({ title: run.name('E2工单'), categoryId: cat.id, submitterId: e2.id });

  await login(page, t1);
  await page.goto(`/tickets?categoryId=${cat.id}`);
  expect((await listedTicketIds(page)).sort()).toEqual([ticketE1, ticketE2].sort());

  await page.goto(`/tickets/${ticketE1}`);
  await page.getByTestId('claim-button').click();
  await expect(page.getByTestId('ticket-assignee')).toHaveText(t1.display);
  const firstEvent = page.getByTestId('event-item').first();
  await expect(firstEvent.getByTestId('event-type')).toHaveText('认领');
  await expect(firstEvent.getByTestId('event-from')).toHaveText('未分配');
  await expect(firstEvent.getByTestId('event-to')).toHaveText(t1.display);

  await page.goto(`/tickets/${ticketE2}`);
  const options = await page.getByTestId('assign-select').locator('option:not([value=""])').allTextContents();
  expect(options).toContain(t2.display);
  for (const excluded of [t1.display, e1.display, e2.display, otherAdmin.display]) {
    expect(options).not.toContain(excluded);
  }
  await page.getByTestId('assign-select').selectOption({ label: t2.display });
  await page.getByTestId('assign-submit').click();
  await expect(page.getByTestId('ticket-assignee')).toHaveText(t2.display);
  await expect(page.getByTestId('event-item').first().getByTestId('event-type')).toHaveText('分派');
  await logout(page);

  await login(page, t2);
  await page.goto(`/tickets/${ticketE1}`);
  await page.getByTestId('claim-button').click();
  await expect(page.getByTestId('ticket-assignee')).toHaveText(t2.display);
  const events = page.getByTestId('event-item');
  await expect(events).toHaveCount(2);
  await expect(events.nth(1).getByTestId('event-actor')).toHaveText(t2.display);
  await expect(events.nth(1).getByTestId('event-from')).toHaveText(t1.display);
  await expect(events.nth(1).getByTestId('event-to')).toHaveText(t2.display);
  await expect(events.nth(1).getByTestId('event-time')).toHaveText(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/);
  await logout(page);

  await login(page, e1);
  await page.goto(`/tickets/${ticketE1}`);
  await expect(page.getByTestId('ticket-assignee')).toHaveText(t2.display);
  await expect(page.getByTestId('event-item')).toHaveCount(2);
});
