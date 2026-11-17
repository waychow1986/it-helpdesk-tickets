import { defineConfig, devices } from '@playwright/test';
import { randomBytes } from 'node:crypto';

// 运行前缀与种子管理员密码：由 developer-functional-run.sh 注入；直接运行 npx playwright test 时在此生成。
// 写入 process.env 以便 worker 进程继承同一值。密码只存在于进程环境，不输出。
if (!process.env.E2E_RUN_PREFIX) {
  process.env.E2E_RUN_PREFIX = `e2e-${Date.now()}-${randomBytes(3).toString('hex')}-`;
}
// IQA-016 实例所用一次性 schema（TD-016）：由运行前缀转小写下划线派生，与 developer-functional-run.sh 一致。
if (!process.env.E2E_SETUP_SCHEMA) {
  process.env.E2E_SETUP_SCHEMA =
    `e2e_setup_${process.env.E2E_RUN_PREFIX.toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '')}`;
}
if (!process.env.E2E_ADMIN_PASSWORD) {
  process.env.E2E_ADMIN_PASSWORD = `Pw-${randomBytes(12).toString('hex')}`;
}

export default defineConfig({
  timeout: 90_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'playwright-report' }]],
  outputDir: 'test-results',
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:8080',
    headless: true,
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
  },
  projects: [
    { name: 'setup', testDir: './support', testMatch: /seed\.setup\.ts/, teardown: 'cleanup' },
    { name: 'cleanup', testDir: './support', testMatch: /cleanup\.teardown\.ts/ },
    {
      name: 'chromium',
      testDir: './tests',
      testIgnore: /iqa-016-initial-admin\.spec\.ts/,
      dependencies: ['setup'],
      use: { ...devices['Desktop Chrome'] },
    },
    // IQA-016：localhost:8081 上以一次性 schema 启动的第二个实例（TD-016），不插入任何种子数据
    {
      name: 'initial-admin',
      testDir: './tests',
      testMatch: /iqa-016-initial-admin\.spec\.ts/,
      teardown: 'initial-admin-cleanup',
      use: { ...devices['Desktop Chrome'], baseURL: process.env.E2E_SETUP_BASE_URL ?? 'http://localhost:8081' },
    },
    { name: 'initial-admin-cleanup', testDir: './support', testMatch: /schema\.teardown\.ts/ },
  ],
});
