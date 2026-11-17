import { expect, test as teardown } from '@playwright/test';
import { closeDb } from './db';
import { clearState, dropUpgradeSchema, schemaExists, upgradeSchema } from './upgrade-snapshot';

// 升级演练清理（TD-030）：删除一次性 schema e2e_upgrade_… 与临时快照目录。
teardown('删除升级演练一次性 schema', async () => {
  const schema = upgradeSchema();
  await dropUpgradeSchema(schema);
  expect(await schemaExists(schema)).toBe(false);
  clearState();
  await closeDb();
});
