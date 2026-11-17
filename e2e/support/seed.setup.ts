import { test as setup } from '@playwright/test';
import { cleanup, closeDb, seedAdmin } from './db';

setup('以运行前缀插入种子管理员', async () => {
  const prefix = process.env.E2E_RUN_PREFIX!;
  await cleanup(prefix);
  await seedAdmin(prefix, process.env.E2E_ADMIN_PASSWORD!);
  await closeDb();
});
