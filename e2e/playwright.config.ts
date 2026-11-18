import { defineConfig, devices } from '@playwright/test';
import { randomBytes } from 'node:crypto';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

// 运行前缀与各随机值：由 developer-functional-run.sh 注入；直接运行 npx playwright test 时在此生成。
// 写入 process.env 以便 worker 进程继承同一值。密码与口令只存在于进程环境，不输出。
if (!process.env.E2E_RUN_PREFIX) {
  process.env.E2E_RUN_PREFIX = `e2e-${Date.now()}-${randomBytes(3).toString('hex')}-`;
}
const suffix = process.env.E2E_RUN_PREFIX.toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '');
// IQA-016 / V2 IQA-006 实例所用一次性 schema（TD-016）：由运行前缀转小写下划线派生，与 developer-functional-run.sh 一致。
if (!process.env.E2E_SETUP_SCHEMA) {
  process.env.E2E_SETUP_SCHEMA = `e2e_setup_${suffix}`;
}
// V2 升级演练一次性 schema（TD-030）。
if (!process.env.E2E_UPGRADE_SCHEMA) {
  process.env.E2E_UPGRADE_SCHEMA = `e2e_upgrade_${suffix}`;
}
if (!process.env.E2E_UPGRADE_STATE_DIR) {
  process.env.E2E_UPGRADE_STATE_DIR = join(tmpdir(), `e2e-upgrade-${suffix}`);
}
for (const name of ['E2E_ADMIN_PASSWORD', 'E2E_SETUP_ADMIN_PASSWORD', 'E2E_UPGRADE_PASSWORD']) {
  if (!process.env[name]) {
    process.env[name] = `Pw-${randomBytes(12).toString('hex')}`;
  }
}

const chrome = devices['Desktop Chrome'];
const upgradeBaseURL = process.env.E2E_UPGRADE_BASE_URL ?? 'http://localhost:8082';

export default defineConfig({
  timeout: 120_000,
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
    // localhost:8080（public schema）：V1 回归用例 + V2 IQA-001～005、007、008
    {
      name: 'chromium',
      testDir: './tests',
      testMatch: [/(^|[\\/])iqa-\d+-[^\\/]*\.spec\.ts$/, /v2-iqa-00[1234578]-[^\\/]*\.spec\.ts$/],
      testIgnore: [/iqa-016-initial-admin\.spec\.ts/, /v2-iqa-006-last-admin\.spec\.ts/, /[\\/]upgrade[\\/]/],
      dependencies: ['setup'],
      use: { ...chrome },
    },
    // localhost:8081 一次性 schema（TD-016）：先 V1 IQA-016 设置唯一管理员，再在同一 schema 上执行 V2 IQA-006
    {
      name: 'initial-admin',
      testDir: './tests',
      testMatch: [/iqa-016-initial-admin\.spec\.ts/, /v2-iqa-006-last-admin\.spec\.ts/],
      fullyParallel: false,
      teardown: 'initial-admin-cleanup',
      use: { ...chrome, baseURL: process.env.E2E_SETUP_BASE_URL ?? 'http://localhost:8081' },
    },
    { name: 'initial-admin-cleanup', testDir: './support', testMatch: /schema\.teardown\.ts/ },
    // localhost:8082 升级演练（TD-030）：seed（V1 结构）→ verify（升级后，含 IQA-010 新增数据与快照）→ repeat（重启后比对）
    { name: 'upgrade-seed', testDir: './support', testMatch: /upgrade-seed\.ts/ },
    {
      name: 'upgrade-verify',
      testDir: './tests/upgrade',
      testMatch: [/v2-iqa-009-upgrade\.spec\.ts/, /v2-iqa-010-repeat-upgrade\.spec\.ts/],
      grep: /IQA-009|IQA-010 准备/,
      use: { ...chrome, baseURL: upgradeBaseURL },
    },
    {
      name: 'upgrade-repeat',
      testDir: './tests/upgrade',
      testMatch: /v2-iqa-010-repeat-upgrade\.spec\.ts/,
      grep: /IQA-010 比对/,
      use: { ...chrome, baseURL: upgradeBaseURL },
    },
    { name: 'upgrade-cleanup', testDir: './support', testMatch: /upgrade\.teardown\.ts/ },
  ],
});
