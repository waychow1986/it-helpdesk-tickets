# Coding 实施报告

## Coding Session NR-DLV-20261117-88E4DAE97F6A-0027-b01

### 实施范围

- 模式：`coding`（第 1 批）。范围为 `tasks-implementation.md` 全部任务 T001～T055（Phase 1 Setup、Phase 2 Foundational、US-001～US-006、Phase 9 Polish），来源为已批准的 `spec.md`（FR-001～FR-023、NFR-001、AC-001～AC-016、IQA-001～IQA-010）、`design.md`（TD-020～TD-034、验证环境责任矩阵、快速验证指南）与 `technical-baseline.md`。
- 在 V1 代码库上增量修改，未新增外部服务、遥测后端、身份产品或数据库；唯一外部依赖仍为 `ticket-db`（`postgres`，`dev` 实例真实连接，认证方式 `platform-identity`）。
- 落实的人工决定：BQ-001 A（4/24/72 小时自然时间，`Urgency` 唯一定义）、BQ-002 A（报表第五项按首次已解决时刻）、BQ-003 A（停用 IT 同事不改其名下工单）、HT-DLV-20261117-88E4DAE97F6A-0002（始终至少保留一个可用管理员，含停用自己）、HT-DLV-20261117-88E4DAE97F6A-0005（DEC-TS-V2-001 A：完整升级演练由开发者在真实 `dev` 实例上执行，`qa-run.sh` 不由 Coding 编写）。

### 完成与受阻任务

- 已完成（55 项）：T001、T002、T003、T004、T005、T006、T007、T008、T009、T010、T011、T012、T013、T014、T015、T016、T017、T018、T019、T020、T021、T022、T023、T024、T025、T026、T027、T028、T029、T030、T031、T032、T033、T034、T035、T036、T037、T038、T039、T040、T041、T042、T043、T044、T045、T046、T047、T048、T049、T050、T051、T052、T053、T054、T055。每项均在本 Session 内实际执行了对应的定向验证并通过后才勾选。
- 受阻任务：无。
- 跨故事功能测试（`v2-iqa-001`、`002`、`004`、`005`、`008`、`009`、`010`）在全部前置实现任务完成后，于本 Session 的完整 `developer-functional-run.sh` 运行中整体通过，未跳过或删除任何步骤。
- T054 说明：V1 回归测试中只有以下因 V2 行为确有变化或测试衔接需要而调整，未删除或跳过任何 V1 场景、未改名或删除任何 V1 `data-testid`：`MigrationIT` 当前版本断言 1→2（并新增 V2 断言）；`WeeklyReportTest` 因 `WeeklyReport` 新增第五项字段改用新构造器并改为 Web 切片以核对渲染；`iqa-016-initial-admin.spec.ts` 的初始管理员密码改为取自运行时随机值 `E2E_SETUP_ADMIN_PASSWORD`（缺省仍随机生成），供同一 schema 上随后执行的 V2 IQA-006 以 A1 登录，断言未改；`e2e/support/fixtures.ts` 的运行标签对 `v2-iqa-0xx` 文件名缩写为 `v0xx`（控制登录名长度）。其余 V1 单元、集成与 e2e 用例未改动即通过。`SecretUsagePolicyTest` 保留并通过。

### 变更文件

| 路径 | 变更 | 来源任务 |
|---|---|---|
| `.ai-delivery/features/002-v1/runtime/deployment.json` | 新增：Candidate 部署与验证契约（8080、`/actuator/health`、`/api/candidate-identity`、分类验证命令、functional 脚本指向 002-v1） | T001 |
| `.ai-delivery/features/002-v1/runtime/developer-functional-run.sh` | 新增：8080/8081 功能测试 + 8082 升级演练（target=1 → seed → 升级 → verify → 重启 → repeat）+ 无论成败清理，首个失败码退出 | T002、T053 |
| `src/main/resources/db/migration/V2__account_active.sql` | 新增：只含 `ALTER TABLE app_user ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT TRUE;` | T008 |
| `src/main/java/com/ithelpdesk/config/DatabaseAuthModeCheck.java`、`src/main/resources/META-INF/spring.factories` | 新增：`EnvironmentPostProcessor`，`AIDH_DEP_TICKET_DB_AUTH_MODE` 非 `platform-identity` 时 ERROR 并终止启动 | T009 |
| `src/main/java/com/ithelpdesk/user/AppUser.java`、`src/main/java/com/ithelpdesk/user/UserRepository.java` | `AppUser.active`；`COLUMNS` 含 `active`；新增 `findAssignableItStaff`、`findAssigneeCandidateForShare`、`acquireAccountStatusLock`、`lockForStatusChange`、`countOtherUsableAdmins`、`updateActive`；移除被替代的 `findRoleForShare`；`insert` 不写 `active` | T010、T032、T040 |
| `src/main/java/com/ithelpdesk/ticket/Urgency.java`、`src/main/java/com/ithelpdesk/ticket/ExpectedResolution.java` | `resolutionHours()`/`resolutionDuration()`；SQL 表达式唯一来源 `ExpectedResolution.sql(alias)` | T014 |
| `src/main/java/com/ithelpdesk/ticket/TicketRepository.java`、`Ticket.java`、`TicketDetail.java`、`TicketPage.java` | 查询选出 `expected_resolve_at`；读取模型增加 `expectedResolveAt`；详情 / 列表视图增加超时标识 | T015、T020 |
| `src/main/java/com/ithelpdesk/ticket/Overdue.java` | 新增：`isOverdue(status, expectedAt, now)` | T019 |
| `src/main/java/com/ithelpdesk/ticket/TicketController.java` | 注入 `Clock`，每个请求取一次 `now` 计算超时标识 | T016、T020 |
| `src/main/resources/templates/tickets/list.html`、`src/main/resources/templates/tickets/detail.html`、`src/main/resources/static/css/app.css` | 期望解决时间列 / 项（`row-expected`、`ticket-expected`、`data-expected-at`）、超时标识（`row-overdue`、`ticket-overdue`、`data-overdue`）、`.overdue` 样式 | T016、T020 |
| `src/main/java/com/ithelpdesk/user/AccountStatusService.java` | 新增：advisory lock → `FOR UPDATE` → 状态判断 → 最后可用管理员保护 → 条件更新；日志只含 id | T033 |
| `src/main/java/com/ithelpdesk/auth/CurrentUser.java`、`src/main/java/com/ithelpdesk/auth/AppUserDetailsService.java` | `isEnabled = passwordHash != null && active` | T034 |
| `src/main/java/com/ithelpdesk/auth/RoleRefreshFilter.java` | 账号不存在或已停用：清上下文、失效会话、302 `/login` | T035 |
| `src/main/java/com/ithelpdesk/user/UserAdminController.java`、`src/main/resources/templates/admin/users.html` | `POST /admin/users/{id}/deactivate|activate`（正整数校验、PRG、自我停用 200 渲染）；状态列与按钮 | T036 |
| `src/main/java/com/ithelpdesk/ticket/TicketQueryService.java`、`src/main/java/com/ithelpdesk/ticket/TicketCommandService.java` | 分派候选只含启用 IT 同事；提交时 `FOR SHARE` 校验启用状态并记录 INFO；筛选与认领不按 `active` 过滤 | T037、T040 |
| `src/main/java/com/ithelpdesk/report/WeeklyReport.java`、`WeeklyReportRepository.java`、`WeeklyReportService.java`、`src/main/resources/templates/admin/report.html` | 第五项 `overdueResolved` 与 `report-overdue-resolved`；V1 四项 SQL 未改 | T044、T045 |
| `src/test/java/com/ithelpdesk/MigrationPolicyTest.java`、`MigrationIT.java` | 扩展禁止项与 V1 校验和；版本 2、`active` 列、V1 表列不变 | T003、T004、T054 |
| `src/test/java/com/ithelpdesk/config/DatabaseAuthModeCheckTest.java` | 新增 | T005 |
| `src/test/java/com/ithelpdesk/ticket/ExpectedResolutionTest.java`、`OverdueTest.java`、`TicketExpectedTimeIT.java`、`TicketAssignmentIT.java` | 新增 / 扩展 | T011、T012、T017、T027 |
| `src/test/java/com/ithelpdesk/user/AccountStatusServiceTest.java`、`AccountStatusIT.java`、`UserAdminControllerTest.java` | 新增 | T021、T022、T025、T038 |
| `src/test/java/com/ithelpdesk/auth/AppUserDetailsServiceTest.java`、`RoleRefreshFilterTest.java`、`DeactivatedLoginIT.java` | 扩展 / 新增 | T023、T024、T026 |
| `src/test/java/com/ithelpdesk/report/WeeklyReportTest.java`、`WeeklyReportIT.java` | 扩展第五项 | T041、T042 |
| `src/test/java/com/ithelpdesk/upgrade/UpgradeFixtures.java`、`UpgradeRehearsalIT.java`、`UpgradeInterruptionIT.java` | 新增：V1 合成数据集、快照比对、升级演练与中断重跑 | T046、T047、T048 |
| `src/test/java/com/ithelpdesk/support/WebSliceTestBase.java` | 切片基类增加 `AccountStatusService` 替身（控制器新依赖） | T025、T054 |
| `e2e/playwright.config.ts` | `chromium`/`initial-admin` 划分与 `upgrade-seed`/`upgrade-verify`/`upgrade-repeat`/`upgrade-cleanup` projects | T006、T052 |
| `e2e/support/db.ts`、`e2e/support/fixtures.ts` | 期望值独立计算、FR-017 独立 SQL、追加状态事件、清理前缀不得匹配 `q15-72f2b9041e74-`；账号状态与期望 / 超时读取辅助 | T007 |
| `e2e/tests/v2-iqa-001-expected-time.spec.ts` … `v2-iqa-008-overdue-resolved-report.spec.ts` | 新增 V2 IQA-001～008 开发者功能测试 | T013、T018、T028、T029、T030、T031、T039、T043 |
| `e2e/support/upgrade-seed.ts`、`e2e/support/upgrade-snapshot.ts`、`e2e/support/upgrade.teardown.ts` | 新增：V1 数据集写入、快照比对、schema 删除 | T049 |
| `e2e/tests/upgrade/v2-iqa-009-upgrade.spec.ts`、`e2e/tests/upgrade/v2-iqa-010-repeat-upgrade.spec.ts` | 新增：升级后核对与重复升级比对 | T050、T051 |
| `e2e/tests/iqa-016-initial-admin.spec.ts` | 初始管理员密码取自运行时随机值（供 V2 IQA-006 衔接），断言未改 | T030、T054 |
| `.ai-delivery/features/002-v1/tasks-implementation.md` | T001～T055 由 `[ ]` 改为 `[x]` | 全部 |
| `.ai-delivery/features/002-v1/reports/implementation.md` | 新建本报告 | T055 |

根目录 `Dockerfile`、`.dockerignore`、`pom.xml` 未修改（design 无变更）；`V1__init.sql` 未修改（`MigrationPolicyTest` 以 V1 交付提交 7977bdd 的 SHA-256 校验）。

### 需求与场景覆盖

| 来源（FR / AC / IQA） | 实现路径 | 测试路径 | 结果 |
|---|---|---|---|
| FR-001、FR-002、FR-003、INV-001 / AC-001、AC-002 / IQA-001 | `ticket/Urgency.java`、`ticket/ExpectedResolution.java`、`ticket/TicketRepository.java`、`tickets/list.html`、`tickets/detail.html` | `ExpectedResolutionTest`、`TicketExpectedTimeIT`、`e2e/tests/v2-iqa-001-expected-time.spec.ts` | 通过 |
| FR-004、FR-005、FR-006、INV-002 / AC-003、AC-004 / IQA-002 | `ticket/Overdue.java`、`TicketController.java`、`TicketPage.java`、`TicketDetail.java`、`app.css` | `OverdueTest`、`e2e/tests/v2-iqa-002-overdue.spec.ts` | 通过 |
| FR-007、FR-008、FR-009、FR-010、FR-016、INV-003、INV-005 / AC-005、AC-006 / IQA-003 | `user/AccountStatusService.java`、`user/UserAdminController.java`、`admin/users.html`、`auth/CurrentUser.java`、`auth/RoleRefreshFilter.java` | `AccountStatusServiceTest`、`UserAdminControllerTest`、`AppUserDetailsServiceTest`、`RoleRefreshFilterTest`、`AccountStatusIT`、`DeactivatedLoginIT`、`e2e/tests/v2-iqa-003-deactivate-activate.spec.ts` | 通过 |
| FR-011、FR-012、FR-013 / AC-007、AC-008 / IQA-004 | `ticket/TicketQueryService.java`、`ticket/TicketCommandService.java`、`user/UserRepository.java` | `TicketAssignmentIT`（含两种并发顺序）、`AccountStatusIT`、`e2e/tests/v2-iqa-004-assign-deactivated.spec.ts` | 通过 |
| FR-013 / AC-008 / IQA-005 | 历史查询保持 V1 语义（未加 `active` 条件） | `AccountStatusIT.historyOfDeactivatedAccountsIsShownAsBefore`、`e2e/tests/v2-iqa-005-deactivated-history.spec.ts` | 通过 |
| FR-014、INV-004 / AC-009 / IQA-006；HT-DLV-20261117-88E4DAE97F6A-0002 | `user/AccountStatusService.java`、`user/UserAdminController.java`（自我停用 200 渲染） | `AccountStatusServiceTest`、`AccountStatusIT.lastUsableAdminIsProtectedEvenUnderConcurrency`（一次性 schema、并发互相停用只成功一个）、`e2e/tests/v2-iqa-006-last-admin.spec.ts`（8081 一次性 schema） | 通过 |
| FR-015 / AC-010 / IQA-007 | `SecurityConfig`（`/admin/**` 需 ADMIN，未改）、`AccountStatusService.requireAdmin` | `UserAdminControllerTest`、`AccountStatusServiceTest`、`e2e/tests/v2-iqa-007-access-control.spec.ts` | 通过 |
| FR-017、FR-018、FR-022 / AC-011、AC-012 / IQA-008 | `report/WeeklyReportRepository.java`、`WeeklyReport.java`、`WeeklyReportService.java`、`admin/report.html` | `WeeklyReportTest`、`WeeklyReportIT`、`e2e/tests/v2-iqa-008-overdue-resolved-report.spec.ts` | 通过 |
| FR-019、FR-020、FR-021、FR-022、NFR-001、INV-006 / AC-013、AC-014 / IQA-009 | `db/migration/V2__account_active.sql`；`/setup` 判定未改（TD-032） | `MigrationPolicyTest`、`MigrationIT`、`UpgradeRehearsalIT`、`e2e/tests/upgrade/v2-iqa-009-upgrade.spec.ts` | 通过 |
| FR-023、NFR-001 / AC-015、AC-016 / IQA-010 | 同上（Flyway 事务性 DDL、history 去重） | `UpgradeRehearsalIT`（再次 migrate 0 个）、`UpgradeInterruptionIT`（中断回滚后重跑）、`e2e/tests/upgrade/v2-iqa-010-repeat-upgrade.spec.ts` | 通过 |
| TD-031（平台运行环境画像「认证方式与机密」） | `config/DatabaseAuthModeCheck.java`、`META-INF/spring.factories` | `DatabaseAuthModeCheckTest`；`AIDH_DEP_TICKET_DB_AUTH_MODE=credential` 实际启动 jar 退出码 1 | 通过 |
| INV-007（V1 不变量） | V1 代码未改语义 | V1 全部单元、集成与 e2e 回归用例 | 通过 |

### 执行命令与结果

| 命令 | 环境 | 依赖实例（真实 / 替身） | 结果 | 证据 |
|---|---|---|---|---|
| `mvn -B -ntp -q dependency:go-offline`；`cd e2e && npm ci` | Agent Pod，`deployment.json` 的 `developerSetupCommands`，`bash -euo pipefail`、stdin `/dev/null` | 不涉及 | 通过（退出码 0） | 依次执行 |
| `mvn -B -ntp checkstyle:check` | Agent Pod（`staticAnalysis`） | 不涉及 | 通过 | BUILD SUCCESS，无违规 |
| `mvn -B -ntp -DskipTests package` | Agent Pod（`build`） | 不涉及 | 通过 | 生成 `target/helpdesk.jar` |
| `mvn -B -ntp test` | Agent Pod 单元 / Web 切片测试（`developerTests`） | 替身：仓库与服务层 Mockito 替身，只用于纯逻辑与 Web 层授权 / 渲染，不计入 functional / integration 证据 | 通过 | Tests run: 143, Failures: 0, Errors: 0, Skipped: 0（含新增 `MigrationPolicyTest` 4、`DatabaseAuthModeCheckTest` 3、`ExpectedResolutionTest` 5、`OverdueTest` 3、`AccountStatusServiceTest` 8、`UserAdminControllerTest` 7、`WeeklyReportTest` 6、`RoleRefreshFilterTest` 6、`AppUserDetailsServiceTest` 6） |
| `mvn -B -ntp verify -Pintegration -DskipUnitTests=true` | Agent Pod，Failsafe 集成测试（`integrationTests`） | 真实 `ticket-db` `dev` 实例（`AIDH_DEP_TICKET_DB_*`，`platform-identity`，Entra 令牌，`sslmode=require`，PostgreSQL 16.15） | 通过 | Tests run: 64, Failures: 0, Errors: 0（V2：`MigrationIT` 8、`TicketExpectedTimeIT` 2、`TicketAssignmentIT` 7、`AccountStatusIT` 4、`DeactivatedLoginIT` 1、`WeeklyReportIT` 4、`UpgradeRehearsalIT` 2、`UpgradeInterruptionIT` 1；其余为 V1 IT 回归）。首次连接时日志 `Migrating schema "public" to version "2 - account active"`，即 `dev` 库 `public` 完成真实 V1→V2 升级，之后为 `Schema "public" is up to date` |
| `bash .ai-delivery/features/002-v1/runtime/developer-functional-run.sh` | Agent Pod，localhost:8080 / 8081 / 8082 + Playwright Chromium（`functionalTests`） | 真实 `ticket-db` `dev` 实例 | 通过（退出码 0，258 秒） | 运行前缀 `e2e-1794914610-31da7b-`；chromium + initial-admin：27 passed（V1 IQA-001～014、016 回归 + V2 IQA-001～008）；升级演练 schema `e2e_upgrade_e2e_1794914610_31da7b`：① `now at version v1` → ② upgrade-seed 1 passed → ③ `Migrating … to version "2 - account active"`、`now at version v2` → upgrade-verify 2 passed（IQA-009、IQA-010 准备）→ ⑤ 重启 `is up to date. No migration necessary` → upgrade-repeat 1 passed；清理 3 passed |
| `bash -n .ai-delivery/features/002-v1/runtime/developer-functional-run.sh` | Agent Pod | 不涉及 | 通过 | 语法检查无输出 |
| `AIDH_DEP_TICKET_DB_AUTH_MODE=credential java -jar target/helpdesk.jar --server.port=8099` | Agent Pod | 不涉及（启动在连接数据库前终止） | 符合预期：启动失败、退出码 1 | 日志 ERROR `Application run failed` + `ticket-db 认证方式 credential 未在本版本实现`；日志不含任何密码变量 |
| `tsc --noEmit --strict`（临时安装于仓库外 `/tmp/tsc`） | Agent Pod，e2e TypeScript 类型检查（辅助） | 不涉及 | 通过 | `playwright.config.ts`、`support/*.ts`、`tests/**/*.ts` 无类型错误 |
| 清理核对（只读 SQL，Node + `@azure/identity`） | Agent Pod | 真实 `ticket-db` `dev` 实例 | 通过 | `it_*`/`e2e_*` 一次性 schema 残留：无；`it-%`/`e2e-%` 前缀账号残留：0；`public` 迁移最高成功版本：2 |

真实 `ticket-db` `dev` 实例使用清单与清理记录：

- 集成测试：每个测试类以 `it-<8位十六进制>-` 运行前缀写入合成账号 / 类别 / 工单并在 `@AfterAll` 按前缀清理；报表与超时测试使用可变测试时钟设到 1971～2000 年间随机历史周；一次性 schema `it_admin_<runId>`（`AccountStatusIT`）、`it_upgrade_<runId>`（`UpgradeRehearsalIT`）、`it_upgrade_<runId>_int` 与 `it_upgrade_<runId>_ref`（`UpgradeInterruptionIT`）、V1 的 `it_setup_<runId>`（`InitialAdminSetupIT`）均在测试结束时 `DROP SCHEMA … CASCADE` 并断言已不存在。
- 功能测试：`public` schema 中以运行前缀写入并由 `cleanup` 清理；`e2e_setup_<suffix>`（IQA-016 + V2 IQA-006）由 `initial-admin-cleanup` 删除；`e2e_upgrade_<suffix>` 由 `upgrade-cleanup` 删除，临时快照目录随之删除。清理前缀校验拒绝与 V1 持久升级基线前缀 `q15-72f2b9041e74-` 相互匹配的前缀；`dev` 库中该前缀行数为 0（基线只存在于 `test` 库）。
- 升级演练（含重复升级与中断重跑）记录：`UpgradeRehearsalIT`：`target=1` → 写入 V1 数据集（6 个账号、3 个类别、24 张工单及其沟通与处理记录）→ 快照 → 升级执行 1 个迁移、目标版本 2 → V1 列快照逐行逐列差异为空、`active` 全为 TRUE、迁移历史版本 1、2 均成功 → 24 张工单 `expected_resolve_at` = 提交时间 + 4/24/72 小时 → 再次 migrate 执行 0 个、快照差异为空、迁移历史不变。`UpgradeInterruptionIT`：测试连接对 `app_user` 持 `ACCESS EXCLUSIVE` 锁使 V2 迁移阻塞；`pg_terminate_backend` 在 `dev` 角色下返回 `permission denied to terminate process`，按 design 验证矩阵改为强制关闭迁移所用物理连接（会话设置 `client_connection_check_interval=1000`，并等待服务端迁移会话结束）→ 迁移失败、无成功的版本 2、`active` 列不存在、快照差异为空 → 释放锁重跑执行 1 个迁移 → 与另一 schema 一次成功升级相比：列结构相同、全部表全部列（含 `active`）差异为空、版本化迁移历史行相同且无失败行。e2e 升级演练快照比对输出为空（差异格式只含表名、id 与列名）。
- 机密引用检查：`grep -rn "AIDH_SECRET_" src/main` 的唯一结果为 `src/main/resources/application.yml` 中 `${AIDH_SECRET_INITIAL_SETUP_TOKEN:}`；`src/main`、`e2e` 中不引用 `AIDH_DEP_TICKET_DB_PASSWORD`；开发者测试不读取任何 `AIDH_SECRET_*`；测试密码与合成设置口令只以运行时随机值存在于进程环境，未写入仓库、日志或本报告。

### 使用模拟服务的验证

不涉及：所有依赖都使用真实实例。`docs/dependencies.json` 中唯一依赖 `ticket-db` 的 `realInstance.dev` 与 `realInstance.test` 均为 true，本 Session 的集成测试、功能测试与升级演练全部连接其真实 `dev` 实例，没有使用模拟服务。

| 依赖 | 接口契约（设计中的位置） | 验证内容 | 结果 | 证据 |
|---|---|---|---|---|
| 不涉及 | 不涉及（无 `realInstance.dev` 为 false 的依赖） | 不涉及 | 不涉及 | 不涉及 |

### Runtime 与下游门禁就绪制品

- 根目录 `Dockerfile`：沿用 V1（多阶段构建，`USER 10001`，`EXPOSE 8080`），本 Session 未修改；新增的 `src/main/resources/META-INF/spring.factories` 与迁移文件在 `COPY src` 范围内。
- `.ai-delivery/features/002-v1/runtime/deployment.json`：`containerPort` 8080、`healthPath` `/actuator/health`、`candidateIdentityPath` `/api/candidate-identity`、`requiresPrivateNetwork` false、`developerSetupCommands` 两项、`developerValidationCommands` 五类（与 design 一致，`functionalTests` 指向 002-v1 脚本）、`developerFunctionalTestScript` 指向 `.ai-delivery/features/002-v1/runtime/developer-functional-run.sh`；本 Session 按其顺序完整执行一遍，全部退出码 0。
- Candidate identity endpoint：沿用 V1 `platform/CandidateIdentityController`（返回注入的 `AIDELIVERY_DELIVERY_ID`、`AIDELIVERY_CANDIDATE_SHA`，无虚构回退值），`CandidateIdentityControllerTest` 通过。
- developer functional 脚本：校验依赖变量与端口、生成随机值、两阶段功能测试与四步升级演练、日志断言迁移到版本 2 与 up to date、无论成败清理、以首个失败码退出；`bash -n` 通过，兼容 `bash -euo pipefail` 与 stdin `/dev/null`（实际以该方式执行）。
- 启动迁移：应用启动即由 Flyway 执行 `V2__account_active.sql`，无需平台迁移命令；`deployment.json` 不含 `postgresql` 或 `migrationCommand`。

### 延后平台证据

- 候选验证：Runner 在不可变候选提交上执行 `deployment.json` 命令（含每个候选的升级演练）与脚本语法检查。
- 镜像构建与候选部署：ACR Tasks 以根目录 `Dockerfile` 构建、部署到 `cand-{deliveryShortId}`、`healthPath` 与 `candidateIdentityPath` 校验；候选首次启动对 `test` 库（含 V1 IQA-015 持久基线）执行真实 V1→V2 升级。
- Independent QA：IQA-001～IQA-010 在已部署候选上的验收（IQA-009 以前缀 `q15-72f2b9041e74-` 的 V1 持久基线逐项核对；IQA-010 视平台受控重新部署能力执行，否则只读核对并引用本报告中的开发者升级演练记录）；`qa-run.sh` 与 `qa-result.json` 由 Independent QA 编写与执行，Coding 未编写或预置。
- 机密 `INITIAL_SETUP_TOKEN`、`INITIAL_ADMIN_*` 的 `test` 值由人在平台任务中提供；Coding 未使用、未预置。
- V1 线上数据的真实升级结果、UAT、人工审批、发布与合并。

### 剩余风险

- `dev` 角色无 `pg_terminate_backend` 权限，中断重跑以“强制关闭迁移物理连接”实现（design 允许的同等方式）；这验证了客户端连接中断时的回滚与重跑，未覆盖服务端主动终止会话的路径（二者对 PostgreSQL 事务的效果相同：未提交事务整体回滚）。
- `DatabaseAuthModeCheck` 失败时的说明经 Spring Boot 的 `Application run failed` ERROR 日志输出（`EnvironmentPostProcessor` 的延迟日志在日志系统初始化前不会单独回放），进程以非零退出码终止，运维可在容器日志中看到“ticket-db 认证方式 … 未在本版本实现”。
- e2e 中依赖共享 `dev` 库全局数据的断言（周报表第五项与 V1 四项、积压数）以同一时刻的独立 SQL 计算期望值；若其他交付在页面读取与 SQL 之间的瞬间写入本周首次解决数据，可能产生偶发不一致（与 V1 IQA-013/014 的既有做法相同）。
- 回滚到 V1 镜像期间 V1 不识别停用状态（design「Rollback」已记录）；是否回滚属发布阶段的人工决定。

### 实施结论

完成：T001～T055 全部实现并在本 Session 内实际验证通过。按 `deployment.json` 顺序执行的 setup、staticAnalysis、build、developerTests（143 通过）、integrationTests（64 通过，真实 `ticket-db` `dev` 实例）与 functionalTests（V1 回归 + V2 IQA-001～008 共 27 项通过；localhost:8082 真实升级演练 seed → 升级到版本 2 → IQA-009 核对 → IQA-010 准备 → 重启 up to date → IQA-010 比对全部通过）均退出码 0，合成数据与一次性 schema 已全部清理。没有使用模拟服务；候选部署、Independent QA、UAT 与发布证据按平台流程延后产生。
