import { activateViaUi, createUserViaUi, db, deactivateViaUi, expect, expectLoginRefused, login, randomPassword,
  test, userRow } from '../support/fixtures';
import { assertSetupSchema } from '../support/schema';

// V2 IQA-006 系统始终至少保留一个可用管理员（AC-009；FR-014、FR-009；人工审批意见 HT-DLV-20261117-88E4DAE97F6A-0002）
// 在 localhost:8081 一次性 schema 上紧随 V1 IQA-016 执行：此时 schema 中恰有 IQA-016 设置的唯一管理员 A1
// （密码为运行时随机值 E2E_SETUP_ADMIN_PASSWORD，只在进程环境）。

const SCHEMA = (): string => assertSetupSchema(process.env.E2E_SETUP_SCHEMA);

async function usableAdmins(): Promise<{ login_name: string }[]> {
  return db.query(`SELECT login_name FROM ${SCHEMA()}.app_user
    WHERE role = 'ADMIN' AND active AND password_hash IS NOT NULL ORDER BY id`);
}

test('唯一可用管理员不能停用自己；有其他管理员时可以，停用后被引导至登录入口；A2 重新启用 A1',
  async ({ page, run }) => {
    const admins = await usableAdmins();
    expect(admins, '前置条件：一次性 schema 中仅有 IQA-016 设置的一个可用管理员').toHaveLength(1);
    const password = process.env.E2E_SETUP_ADMIN_PASSWORD;
    if (!password) throw new Error('缺少运行时随机值 E2E_SETUP_ADMIN_PASSWORD');
    const a1 = { login: admins[0].login_name, password };
    const a2 = { login: run.name('a2'), password: randomPassword(), display: run.name('管理员A2'), role: 'ADMIN' as const };

    // 1. A1 尝试停用自己：被拒并提示原因，仍为启用
    await login(page, a1);
    await deactivateViaUi(page, a1.login);
    await expect(page).toHaveURL(/\/admin\/users$/);
    await expect(page.getByTestId('flash')).toHaveText('系统必须至少保留一个可用的管理员');
    await expect(userRow(page, a1.login).getByTestId('user-row-status')).toHaveText('启用');

    // 2. A1 创建管理员 A2
    await createUserViaUi(page, a2);
    await expect(page.getByTestId('flash')).toHaveText('账号已创建');

    // 3. A1 再次停用自己：成功，列表显示为停用
    await deactivateViaUi(page, a1.login);
    await expect(page.getByTestId('flash')).toHaveText('已停用该账号');
    await expect(userRow(page, a1.login).getByTestId('user-row-status')).toHaveText('停用');
    await expect(userRow(page, a1.login).getByTestId('user-row-status')).toHaveAttribute('data-active', 'false');

    // 4. A1 的下一次操作被拒并被引导至登录入口，之后无法再登录
    await page.goto('/admin/users');
    await expect(page).toHaveURL(/\/login$/);
    expect(await expectLoginRefused(page, a1)).toBe('登录名或密码错误');

    // 5. A2 登录后尝试停用自己：被拒，仍为启用
    await login(page, a2);
    await deactivateViaUi(page, a2.login);
    await expect(page.getByTestId('flash')).toHaveText('系统必须至少保留一个可用的管理员');
    await expect(userRow(page, a2.login).getByTestId('user-row-status')).toHaveText('启用');
    expect((await usableAdmins()).map(r => r.login_name)).toEqual([a2.login]);

    // 6. A2 重新启用 A1：A1 可再次登录
    await activateViaUi(page, a1.login);
    await expect(page.getByTestId('flash')).toHaveText('已启用该账号');
    await page.getByTestId('logout').click();
    await login(page, a1);
    await expect(page.getByTestId('nav-admin-users')).toBeVisible();
    expect((await usableAdmins()).length).toBe(2);
  });
