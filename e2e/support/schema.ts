import { query } from './db';

/**
 * 一次性 schema（TD-016）：只接受 e2e_setup_ 前缀、小写字母数字下划线、总长不超过 63 的名称，
 * 校验通过后才拼入 DDL（标识符不能参数化）。
 */
const SETUP_SCHEMA = /^e2e_setup_[a-z0-9_]{1,53}$/;

export function assertSetupSchema(name: string | undefined): string {
  if (!name || !SETUP_SCHEMA.test(name)) {
    throw new Error('一次性 schema 名称不合法（只接受 e2e_setup_ 前缀的小写字母数字下划线名）');
  }
  return name;
}

/** 一次性 schema 中 app_user 的行数（只读核对）。 */
export async function setupSchemaUserCount(name: string): Promise<number> {
  const rows = await query<{ n: string }>(`SELECT COUNT(*) AS n FROM ${assertSetupSchema(name)}.app_user`);
  return Number(rows[0].n);
}

export async function dropSetupSchema(name: string): Promise<void> {
  await query(`DROP SCHEMA IF EXISTS ${assertSetupSchema(name)} CASCADE`);
}

export async function setupSchemaExists(name: string): Promise<boolean> {
  const rows = await query('SELECT 1 FROM pg_namespace WHERE nspname = $1', [assertSetupSchema(name)]);
  return rows.length > 0;
}
