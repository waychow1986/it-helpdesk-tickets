import { test as teardown } from '@playwright/test';
import { cleanup, closeDb } from './db';

teardown('按运行前缀清理合成数据', async () => {
  await cleanup(process.env.E2E_RUN_PREFIX!);
  await closeDb();
});
