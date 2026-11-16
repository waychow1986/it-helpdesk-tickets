import { db, expect, listedTicketIds, login, test } from '../support/fixtures';

// IQA-011 关键词搜索（AC-015；FR-022、FR-024）
test('标题或描述的部分文字可找到工单，对照工单不出现，无匹配有明确提示', async ({ page, run }) => {
  const t1 = await run.user('t1', 'IT_STAFF');
  const e1 = await run.user('e1', 'EMPLOYEE');
  const cat = await run.category('搜索');
  const printer = await db.insertTicket({ title: `打印机卡纸 ${run.name('甲')}`, description: '三楼打印机',
    categoryId: cat.id, submitterId: e1.id });
  const vpn = await db.insertTicket({ title: run.name('远程办公问题'), description: '家里 VPN 连接断开',
    categoryId: cat.id, submitterId: e1.id });
  const control = await db.insertTicket({ title: run.name('对照工单'), description: '显示器闪烁',
    categoryId: cat.id, submitterId: e1.id });
  const mine = [printer, vpn, control];

  await login(page, t1);
  const search = async (q: string) => {
    await page.goto('/tickets');
    await page.getByTestId('filter-q').fill(q);
    await page.getByTestId('filter-submit').click();
    await expect(page.getByTestId('filter-q')).toHaveValue(q);
    return (await listedTicketIds(page)).filter(id => mine.includes(id));
  };
  expect(await search('卡纸')).toEqual([printer]);
  expect(await search('打印')).toEqual([printer]);
  expect(await search('VPN 连接')).toEqual([vpn]);
  expect(await search('不存在的文字XYZ')).toEqual([]);
  await expect(page.getByTestId('ticket-empty')).toHaveText('无匹配工单');
});
