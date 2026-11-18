import { createCategoryViaUi, createUserViaUi, db, expect, login, logout, postWithCsrf, randomPassword, test }
  from '../support/fixtures';
import { assertSetupSchema, setupSchemaUserCount } from '../support/schema';
import type { Page } from '@playwright/test';

// IQA-016 业务方获得初始管理员凭据并登录（AC-021；FR-031、SC-007；TD-011、TD-016、TD-019）
// 在 localhost:8081 上以一次性空 schema 启动的实例上执行（情形①：尚无可用管理员），不插入任何数据。
// 设置口令取自脚本以环境变量传入的运行时随机合成值；口令与密码不打印、不写入报告。

const SETUP_TOKEN = (): string => {
  const value = process.env.E2E_SETUP_TOKEN;
  if (!value) throw new Error('缺少运行时合成设置口令 E2E_SETUP_TOKEN');
  return value;
};
const SCHEMA = (): string => assertSetupSchema(process.env.E2E_SETUP_SCHEMA);

async function fillSetup(page: Page, v: { token: string; login: string; password: string; display: string }):
  Promise<void> {
  await page.getByTestId('setup-token').fill(v.token);
  await page.getByTestId('setup-login-name').fill(v.login);
  await page.getByTestId('setup-password').fill(v.password);
  await page.getByTestId('setup-display-name').fill(v.display);
  await page.getByTestId('setup-submit').click();
}

async function expectNoSecretEcho(page: Page, secrets: string[]): Promise<void> {
  const html = await page.content();
  for (const s of secrets) {
    expect(html.includes(s), '页面不得回显密码或设置口令').toBe(false);
  }
  await expect(page.getByTestId('setup-password')).toHaveValue('');
  await expect(page.getByTestId('setup-token')).toHaveValue('');
}

test('一次性设置初始管理员：口令缺失或错误被拒、设置成功后永久关闭且不能新增或接管账号', async ({ page, run }) => {
  const token = SETUP_TOKEN();
  // 密码取自运行时随机值（只在进程环境），供同一 schema 上随后执行的 V2 IQA-006 以 A1 登录
  const admin = { login: run.name('root'), password: process.env.E2E_SETUP_ADMIN_PASSWORD ?? randomPassword(),
    display: run.name('初始管理员') };
  const secrets = [token, admin.password];
  expect(await setupSchemaUserCount(SCHEMA())).toBe(0);

  // 1. 登录页显示入口且无注册入口
  await page.goto('/login');
  await expect(page.getByTestId('setup-link')).toBeVisible();
  await expect(page.locator('body')).not.toContainText('注册');
  await page.getByTestId('setup-link').click();
  await expect(page).toHaveURL(/\/setup$/);
  await expect(page.getByTestId('setup-token')).toHaveAttribute('type', 'password');
  await expect(page.getByTestId('setup-token')).toHaveAttribute('autocomplete', 'off');
  await expect(page.locator('select[name="role"], [name="role"]')).toHaveCount(0);
  await expect(page.getByTestId('setup-token-missing')).toHaveCount(0);

  // 2. 正确口令但登录名留空：字段校验被拒，不创建账号，流程仍可用
  await fillSetup(page, { ...admin, token, login: '' });
  await expect(page.getByTestId('field-error-loginName')).toHaveText('请填写登录名');
  await expect(page.getByTestId('setup-display-name')).toHaveValue(admin.display);
  await expectNoSecretEcho(page, secrets);
  expect(await setupSchemaUserCount(SCHEMA())).toBe(0);

  // 3. 不填写口令、填写错误合成口令各提交一次：均被拒、不创建账号、流程仍可用
  await fillSetup(page, { ...admin, token: '' });
  await expect(page.getByTestId('field-error-setupToken')).toHaveText('请填写设置口令');
  await expect(page.getByTestId('setup-login-name')).toHaveValue(admin.login);
  await expectNoSecretEcho(page, secrets);
  const wrong = `Wrong-${randomPassword()}`;
  await fillSetup(page, { ...admin, token: wrong });
  await expect(page.getByTestId('field-error-setupToken')).toHaveText('设置口令不正确');
  await expectNoSecretEcho(page, [...secrets, wrong]);
  expect(await setupSchemaUserCount(SCHEMA())).toBe(0);
  await page.goto('/login');
  await expect(page.getByTestId('setup-link')).toBeVisible();

  // 4. 正确口令完成设置 → 登录页提示，不自动登录，不回显
  await page.goto('/setup');
  await fillSetup(page, { ...admin, token });
  await expect(page).toHaveURL(/\/login\?setup$/);
  await expect(page.getByTestId('setup-success')).toHaveText('初始管理员已设置，请使用该账号登录');
  await expect(page.getByTestId('logout')).toHaveCount(0);
  const html = await page.content();
  for (const s of secrets) expect(html.includes(s)).toBe(false);
  expect(await setupSchemaUserCount(SCHEMA())).toBe(1);

  // 5. 以初始管理员登录，创建员工、新增类别、打开周报表
  await login(page, admin);
  await expect(page.getByTestId('nav-admin-users')).toBeVisible();
  const employee = { login: run.name('emp'), password: randomPassword(), display: run.name('员工'),
    role: 'EMPLOYEE' as const };
  await createUserViaUi(page, employee);
  await expect(page.getByTestId('flash')).toHaveText('账号已创建');
  await expect(page.locator(`[data-testid="user-row"][data-login-name="${employee.login}"]`)).toHaveCount(1);
  await createCategoryViaUi(page, run.name('类别'));
  await page.getByTestId('nav-report').click();
  await expect(page.getByTestId('report-week')).toBeVisible();
  await logout(page);

  // 6. 退出后入口不再显示；直接访问被拒
  await expect(page.getByTestId('setup-link')).toHaveCount(0);
  const closed = await page.goto('/setup');
  expect(closed?.status()).toBe(403);
  await expect(page.getByTestId('setup-closed')).toHaveText('系统已完成初始管理员设置，此入口不可用');

  // 7. 带有效 CSRF 令牌的直接 POST：另一组信息与同名接管均被拒，账号数据不变
  const before = await db.query(`SELECT id, login_name, password_hash, display_name, role
    FROM ${SCHEMA()}.app_user ORDER BY id`);
  await page.goto('/login');
  const other = { login: run.name('other'), password: randomPassword(), display: run.name('他人') };
  for (const attempt of [other, { ...other, login: admin.login }]) {
    const res = await postWithCsrf(page, '/setup', { setupToken: token, loginName: attempt.login,
      password: attempt.password, displayName: attempt.display });
    expect(res.status()).toBe(403);
    expect(await res.text()).toContain('data-testid="setup-closed"');
  }
  expect(await db.query(`SELECT id, login_name, password_hash, display_name, role
    FROM ${SCHEMA()}.app_user ORDER BY id`)).toEqual(before);

  // 8. 该组凭据登录失败；原管理员仍可登录
  await page.goto('/login');
  await page.getByTestId('login-username').fill(other.login);
  await page.getByTestId('login-password').fill(other.password);
  await page.getByTestId('login-submit').click();
  await expect(page.getByTestId('login-error')).toHaveText('登录名或密码错误');
  await login(page, admin);
  await expect(page.getByTestId('nav-admin-users')).toBeVisible();
});
