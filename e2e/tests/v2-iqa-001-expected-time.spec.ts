import { commentViaUi, changeStatusViaUi, db, detailExpected, expect, expectSameExpected, login, logout,
  rowExpected, submitTicketViaUi, test } from '../support/fixtures';
import type { Page } from '@playwright/test';
import type { Urgency } from '../support/db';

// V2 IQA-001 新工单按紧急程度获得期望解决时间（AC-001、AC-002；FR-001、FR-002、FR-003；BQ-001 人工答案 A）
// 期望值由测试按数据库中的提交时间 + 4/24/72 小时独立计算；页面以 data-expected-at（ISO-8601 UTC）与显示文本核对。

const URGENCY: Record<'高' | '中' | '低', Urgency> = { 高: 'HIGH', 中: 'MEDIUM', 低: 'LOW' };

async function noExpectedInputs(page: Page): Promise<void> {
  await expect(page.locator('input[name*="expect" i], select[name*="expect" i], textarea[name*="expect" i]'))
    .toHaveCount(0);
}

test('三种紧急程度的期望解决时间在列表、搜索 / 筛选与详情中一致且各类操作后不变', async ({ page, run }) => {
  const e1 = await run.user('e1', 'EMPLOYEE');
  const t1 = await run.user('t1', 'IT_STAFF');
  const t2 = await run.user('t2', 'IT_STAFF');
  const cat = await run.category('期望');
  const keyword = run.name('期望词');

  // 1. E1 以高、中、低各提交一张工单；记录数据库中的提交时间
  await login(page, e1);
  await page.goto('/tickets/new');
  await noExpectedInputs(page);
  const tickets: { id: number; expected: Date; label: string }[] = [];
  for (const label of ['高', '中', '低'] as const) {
    const id = await submitTicketViaUi(page, { title: `${keyword}-${label}`, description: '合成描述',
      category: cat.name, urgency: label });
    const basics = await db.ticketBasics(id);
    expect(basics.urgency).toBe(URGENCY[label]);
    tickets.push({ id, expected: db.expectedResolveAt(basics.submittedAt, basics.urgency), label });
  }

  const checkLists = async (role: string, urls: string[]) => {
    for (const url of urls) {
      await page.goto(url);
      for (const t of tickets) {
        expectSameExpected(await rowExpected(page, t.id), t.expected, `${role} ${url} ${t.label}`);
      }
    }
  };
  const checkDetails = async (role: string) => {
    for (const t of tickets) {
      expectSameExpected(await detailExpected(page, t.id), t.expected, `${role} 详情 ${t.label}`);
      await noExpectedInputs(page);
    }
  };
  const q = encodeURIComponent(keyword);

  // 2. E1：我的工单、关键词搜索、按状态筛选、详情
  await checkLists('E1', ['/tickets', `/tickets?q=${q}`, `/tickets?q=${q}&status=OPEN`]);
  await checkDetails('E1');
  await logout(page);
  // T1：全部工单（按类别）、关键词搜索、按状态筛选、详情
  await login(page, t1);
  await checkLists('T1', [`/tickets?categoryId=${cat.id}`, `/tickets?q=${q}`, `/tickets?q=${q}&status=OPEN`]);
  await checkDetails('T1');

  // 3. T1 对“高”工单依次认领、分派给 T2、处理中、已解决、已关闭、回复；每步后期望值不变
  const high = tickets[0];
  const unchanged = async (step: string) =>
    expectSameExpected(await detailExpected(page, high.id), high.expected, `操作后：${step}`);
  await page.goto(`/tickets/${high.id}`);
  await page.getByTestId('claim-button').click();
  await expect(page.getByTestId('ticket-assignee')).toHaveText(t1.display);
  await unchanged('认领');
  await page.getByTestId('assign-select').selectOption({ label: t2.display });
  await page.getByTestId('assign-submit').click();
  await expect(page.getByTestId('ticket-assignee')).toHaveText(t2.display);
  await unchanged('分派');
  for (const status of ['处理中', '已解决', '已关闭'] as const) {
    await changeStatusViaUi(page, high.id, status);
    await unchanged(status);
  }
  await commentViaUi(page, high.id, run.name('回复'));
  await unchanged('回复');
  await logout(page);

  // E1 补充说明；4. 再次查看列表项与详情（已关闭仍显示期望解决时间）
  await login(page, e1);
  await commentViaUi(page, high.id, run.name('补充说明'));
  await unchanged('补充说明');
  await expect(page.getByTestId('ticket-status')).toHaveText('已关闭');
  await expect(page.getByTestId('ticket-expected')).toBeVisible();
  await checkLists('E1 操作后', ['/tickets', `/tickets?q=${q}`]);
  await page.goto(`/tickets?q=${q}&status=CLOSED`);
  expectSameExpected(await rowExpected(page, high.id), high.expected, 'E1 已关闭筛选');
  await logout(page);
  await login(page, t1);
  await checkLists('T1 操作后', [`/tickets?q=${q}`]);
  await checkDetails('T1 操作后');
});
