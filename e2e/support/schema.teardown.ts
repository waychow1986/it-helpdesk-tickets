import { expect, test as teardown } from '@playwright/test';
import { closeDb } from './db';
import { dropSetupSchema, setupSchemaExists } from './schema';

teardown('删除一次性 schema', async () => {
  const name = process.env.E2E_SETUP_SCHEMA!;
  await dropSetupSchema(name);
  expect(await setupSchemaExists(name)).toBe(false);
  await closeDb();
});
