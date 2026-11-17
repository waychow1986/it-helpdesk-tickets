# Coding 后 Convergence 报告

## 评审范围

- 模式：initial（一次穷尽评审）；nodeRunId `NR-DLV-20261117-88E4DAE97F6A-0028`。
- 被评审提交：`e30a6ba`（Coding Session `NR-DLV-20261117-88E4DAE97F6A-0027-b01`），对照基线为其父提交 `beb54a4`（task-planning.gate）。
- 权威制品（只读）：`intent.md`（INT-001～INT-016、BQ-001～BQ-003 人工答案、HT-DLV-20261117-88E4DAE97F6A-0002）、`spec.md`（US-001～US-006、FR-001～FR-023、NFR-001、AC-001～AC-016、IQA-001～IQA-010，按 HT-0005/0006/0007 修订版）、`design.md`（TD-020～TD-034、验证环境责任矩阵、快速验证指南）、`technical-baseline.md`、`tasks-implementation.md`（T001～T055）、`reports/implementation.md`。
- 代码与测试：`src/main/**` 全部 V2 改动（迁移、`Urgency`/`ExpectedResolution`/`Overdue`、`TicketRepository`/`TicketController`/`TicketPage`/`TicketDetail`、`TicketCommandService`/`TicketQueryService`、`AccountStatusService`/`UserRepository`/`UserAdminController`、`CurrentUser`/`AppUserDetailsService`/`RoleRefreshFilter`、`DatabaseAuthModeCheck`、`WeeklyReport*`、模板与 CSS）；`src/test/**` 新增与扩展的单元、切片与集成测试；`e2e/**` 的 Playwright 配置、支持模块与 V2 IQA-001～IQA-010 开发者功能测试；下游就绪制品 `Dockerfile`、`runtime/deployment.json`、`runtime/developer-functional-run.sh`、`platform/CandidateIdentityController`。
- 另核对：`tasks-implementation.md` 在 Coding 提交中只有复选框由 `[ ]` 改为 `[x]`，任务正文未被改写；`V1__init.sql`、`pom.xml`、`Dockerfile`、`docs/dependencies.json` 未被修改。

## 结论

通过。未发现阻塞审批的 critical / high 问题；结果文件 `outcome` 为 `passed`，`findings` 为空。

## 阻塞问题

无。

## 制品到实现与测试的可追溯关系

| 来源 | 实现路径 | 测试/证据路径 | 结论 |
|---|---|---|---|
| FR-001、FR-002、FR-003、INV-001 / AC-001、AC-002 / IQA-001 / T011～T016；BQ-001 A | `src/main/java/com/ithelpdesk/ticket/Urgency.java`（4/24/72 唯一定义）、`ticket/ExpectedResolution.java`、`ticket/TicketRepository.java`（`SELECT` 常量含 `expected_resolve_at`，列表/搜索/筛选/详情共用）、`templates/tickets/list.html`、`templates/tickets/detail.html` | `ExpectedResolutionTest`（5）、`TicketExpectedTimeIT`（2）、`e2e/tests/v2-iqa-001-expected-time.spec.ts` | 一致：派生不落库，所有读取路径同一表达式；页面无期望时间输入；操作后不变有端到端断言 |
| FR-004、FR-005、FR-006、INV-002 / AC-003、AC-004 / IQA-002 / T017～T020 | `ticket/Overdue.java`（严格晚于）、`TicketController.java`（每请求一次 `clock.instant()`）、`TicketPage.withOverdue`、`TicketDetail.withOverdue`、`static/css/app.css` `.overdue` | `OverdueTest`（3）、`e2e/tests/v2-iqa-002-overdue.spec.ts`（A～E 步骤 1/3 与样式断言） | 一致 |
| FR-007、FR-008、FR-009、FR-010、FR-016、INV-003、INV-005 / AC-005、AC-006 / IQA-003 / T021～T026、T028、T032～T036 | `user/AccountStatusService.java`、`user/UserRepository.java`（`updateActive` 条件更新、`insert` 不写 `active`）、`user/UserAdminController.java`、`templates/admin/users.html`、`auth/CurrentUser.java`、`auth/AppUserDetailsService.java`、`auth/RoleRefreshFilter.java` | `AccountStatusServiceTest`（8）、`UserAdminControllerTest`（7）、`AppUserDetailsServiceTest`（6）、`RoleRefreshFilterTest`（6）、`AccountStatusIT`（4）、`DeactivatedLoginIT`（1）、`e2e/tests/v2-iqa-003-deactivate-activate.spec.ts` | 一致：登录拒绝提示与凭据错误相同；已登录会话下一次请求 302 `/login` 且会话失效 |
| FR-011、FR-012 / AC-007 / IQA-004 / T027、T029、T037；BQ-003 A | `ticket/TicketQueryService.assignableStaff`（`findAssignableItStaff`）、`ticket/TicketCommandService.assign`（行锁后 `findAssigneeCandidateForShare` 校验 `active`） | `TicketAssignmentIT`（7，含并发两种顺序）、`e2e/tests/v2-iqa-004-assign-deactivated.spec.ts` | 一致：停用不改其名下工单 |
| FR-013 / AC-008 / IQA-004、IQA-005 / T038～T040 | 历史查询、筛选“处理人”选项、报表人员行均未加 `active` 条件 | `AccountStatusIT.historyOfDeactivatedAccountsIsShownAsBefore`、`v2-iqa-004`、`e2e/tests/v2-iqa-005-deactivated-history.spec.ts` | 一致 |
| FR-014、INV-004 / AC-009 / IQA-006 / T021、T022、T030、T033、T036；HT-DLV-20261117-88E4DAE97F6A-0002 | `AccountStatusService`（advisory lock 7243012 → `FOR UPDATE` → `countOtherUsableAdmins`）、`UserAdminController.deactivate`（自我停用 200 直接渲染） | `AccountStatusServiceTest`、`AccountStatusIT.lastUsableAdminIsProtectedEvenUnderConcurrency`、`e2e/tests/v2-iqa-006-last-admin.spec.ts`（8081 一次性 schema 紧随 IQA-016，六步全覆盖） | 一致：人工决定已落实 |
| FR-015 / AC-010 / IQA-007 / T025、T031 | `SecurityConfig` `/admin/**` 需 `ADMIN`（未改）+ `UserAdminService.requireAdmin`；路径 id 正整数校验 | `UserAdminControllerTest`、`e2e/tests/v2-iqa-007-access-control.spec.ts`（带 CSRF 直接请求 403，状态不变） | 一致 |
| FR-017、FR-018、FR-022 / AC-011、AC-012 / IQA-008 / T041～T045；BQ-002 A | `report/WeeklyReportRepository.overdueResolvedCount`、`WeeklyReport`、`WeeklyReportService`、`templates/admin/report.html`（V1 四项 SQL 未改） | `WeeklyReportTest`（6）、`WeeklyReportIT`（4）、`e2e/tests/v2-iqa-008-overdue-resolved-report.spec.ts`（P1～P6 与独立 SQL） | 一致 |
| FR-019～FR-022、NFR-001、INV-006 / AC-013、AC-014 / IQA-009 / T003、T004、T008、T046、T047、T049、T050；TD-030、TD-032 | `src/main/resources/db/migration/V2__account_active.sql`（仅一条 `ADD COLUMN IF NOT EXISTS`）；`/setup` 判定未改 | `MigrationPolicyTest`（4，含 V1 校验和）、`MigrationIT`（8）、`UpgradeRehearsalIT`（2）、`e2e/tests/upgrade/v2-iqa-009-upgrade.spec.ts` | 一致 |
| FR-023、NFR-001 / AC-015、AC-016 / IQA-010 / T048、T051～T053；HT-DLV-20261117-88E4DAE97F6A-0005 | 同上（Flyway 事务性 DDL、history 去重） | `UpgradeRehearsalIT`（再次 migrate 0 个）、`UpgradeInterruptionIT`（1，中断回滚后重跑与一次成功升级逐列相同）、`e2e/tests/upgrade/v2-iqa-010-repeat-upgrade.spec.ts`、`runtime/developer-functional-run.sh` 升级演练步骤 ①～⑤ | 一致 |
| TD-031（认证方式） / T005、T009 | `config/DatabaseAuthModeCheck.java`、`META-INF/spring.factories` | `DatabaseAuthModeCheckTest`（3）；实施报告记录 `AUTH_MODE=credential` 实际启动退出码 1 | 一致 |
| INV-007、A-001 / T054 | V1 代码语义未改；V1 `data-testid` 均保留（`ticket-status`、`row-status` 仅移入内层 `span`，标识与文本不变） | V1 单元、集成与 e2e 回归用例；`SecretUsagePolicyTest` | 一致 |
| 平台就绪 / T001、T002、T053、T055 | `runtime/deployment.json`、`runtime/developer-functional-run.sh`、`Dockerfile`、`platform/CandidateIdentityController.java` | 实施报告「执行命令与结果」「Runtime 与下游门禁就绪制品」 | 一致 |

## 开发者验证证据复核

- 记录的命令与 `deployment.json` 完全一致：`staticAnalysis`、`build`、`developerTests`（`mvn -B -ntp test`，确实运行 Surefire 测试）、`integrationTests`（`mvn -B -ntp verify -Pintegration -DskipUnitTests=true`；`pom.xml` 中 `skipUnitTests` 绑定 Surefire `skipTests`，`integration` profile 启用 Failsafe）、`functionalTests`（脚本确实运行 Playwright 与升级演练，非健康检查替代）。
- 测试数量复核：实施报告列出的新增 / 扩展测试方法数与仓库一致——`MigrationPolicyTest` 4、`DatabaseAuthModeCheckTest` 3、`ExpectedResolutionTest` 5、`OverdueTest` 3、`AccountStatusServiceTest` 8、`UserAdminControllerTest` 7、`WeeklyReportTest` 6、`RoleRefreshFilterTest` 6、`AppUserDetailsServiceTest` 6；`MigrationIT` 8、`TicketExpectedTimeIT` 2、`TicketAssignmentIT` 7、`AccountStatusIT` 4、`DeactivatedLoginIT` 1、`WeeklyReportIT` 4、`UpgradeRehearsalIT` 2、`UpgradeInterruptionIT` 1。
- 未发现被跳过或禁用的测试：`src/test/java` 无 `@Disabled` / `assume*`；`e2e/tests`、`e2e/support` 无 `test.skip` / `test.fixme` / `.only`。
- Playwright 项目划分与脚本相符：`chromium` 包含 V1 回归与 `v2-iqa-00{1,2,3,4,5,7,8}`；`initial-admin` 按文件名顺序先 `iqa-016` 后 `v2-iqa-006`；`upgrade-seed` / `upgrade-verify`（grep `IQA-009|IQA-010 准备`）/ `upgrade-repeat`（grep `IQA-010 比对`）/ `upgrade-cleanup` 与脚本步骤一一对应；脚本对“迁移到版本 2”“up to date”日志做硬断言，失败计入退出码。
- 集成、功能与升级演练均连接真实 `ticket-db` `dev` 实例（`AIDH_DEP_TICKET_DB_*`、`platform-identity`），单元测试中的 Mockito 替身只覆盖纯逻辑与 Web 层，实施报告已如实说明不计入 functional / integration 证据。
- 升级中断用例：实施报告如实记录 `dev` 角色无 `pg_terminate_backend` 权限，改用 design 验证矩阵明文允许的“关闭迁移所用物理连接”方式；测试代码先尝试服务端终止、无权限再回退，并断言迁移会话已结束、迁移失败、无成功的版本 2、`active` 列不存在、数据未变，重跑后与参照 schema 结构、全部列与迁移历史相同。

## 用户可见行为比较

- 期望解决时间（INT-005、INT-006、FR-001～FR-003）：列表（含搜索 / 筛选结果，与“我的工单”/“全部工单”为同一页面）与详情均显示，格式 `yyyy-MM-dd HH:mm`（公司时区），任何状态均显示；不提供设置或修改入口。符合 BQ-001 A 的自然时间口径。
- 超时标识（INT-007、FR-004～FR-006）：仅待处理 / 处理中且严格晚于期望时间时显示“已超时”红底白字粗体标签，满足 Intent“醒目标出”的体验性要求；已解决 / 已关闭不显示；按查看时刻判定。
- 账号停用 / 启用（INT-009～INT-012、INT-016）：账号列表新增“状态”列与每行“停用”/“启用”按钮；停用账号登录提示与凭据错误一致；已登录会话下一次操作被引导至登录入口；停用 IT 同事不出现在分派下拉中，提交时被拒并提示刷新；其名下工单、沟通与处理记录、按处理人筛选和报表处理量照常；最后一个可用管理员（含自己）不能停用并提示“系统必须至少保留一个可用的管理员”，有其他管理员时可停用自己且结果页可见。
- 周报表（INT-013、FR-017、FR-018）：V1 四项之后新增“本周超时解决的工单数量”，口径按 BQ-002 A；V1 四项与访问权限不变。
- 升级（INT-014、INT-015）：迁移只增加带默认值的 `active` 列，不读写业务行；V1 账号全部启用；`/setup` 仍关闭。
- 未发现未请求的行为、被削弱的控制或范围外能力（无通知、工作日日历、手工改时限、删除账号、批量停用、按超时筛选）。

## 架构、Contract、运维与任务覆盖

- 架构与契约：实现与 TD-020～TD-034 一致——期望时间不落库且 SQL 表达式唯一来源为 `ExpectedResolution.sql`；超时在 Java 中用注入 `Clock` 判定；`AccountStatusService` 事务内 advisory lock + 行锁 + 可用管理员计数 + 条件更新；分派提交以 `FOR SHARE` 与停用互斥；HTTP 路由、flash 文案、`data-testid` 与 `data-expected-at` / `data-overdue` / `data-active` 属性与 design「Interface 与契约」一致。
- 依赖与安全：未引入新的外部服务、遥测后端、身份产品、数据库或运维依赖；唯一依赖仍为 `ticket-db`。`DatabaseAuthModeCheck` 只接受 `platform-identity`，不读取密码变量；停用 / 启用与分派拒绝日志只含用户 / 工单 id；实施报告记录 `src/main` 唯一的 `AIDH_SECRET_*` 引用为 `AIDH_SECRET_INITIAL_SETUP_TOKEN`。
- 任务覆盖：T001～T055 全部有对应实现或测试产出，任务正文未被改写；`qa-run.sh` 未由 Coding 编写（符合 design）。
- 下游就绪制品：根目录 `Dockerfile`（多阶段、`USER 10001`、`EXPOSE 8080`）沿用 V1 且 V2 新增资源在构建范围内；`runtime/deployment.json` 字段与命令和 design 完全一致；Candidate identity endpoint `/api/candidate-identity` 沿用 V1 实现；`runtime/developer-functional-run.sh` 校验依赖变量与端口、随机凭据只在进程环境、`< /dev/null` 兼容、无论成败清理并以首个失败码退出。

## 延后平台证据

- 候选验证：Runner 在不可变候选提交上执行 `deployment.json` 中的命令（含每个候选的升级演练）及 `developer-functional-run.sh` 语法检查。
- 镜像构建与候选部署：ACR Tasks 以根目录 `Dockerfile` 构建；部署到 `cand-{deliveryShortId}` 并校验 `healthPath`、`candidateIdentityPath`；候选首次启动对 `test` 库（含 V1 IQA-015 持久基线，前缀 `q15-72f2b9041e74-`）执行真实 V1→V2 升级。
- Independent QA：IQA-001～IQA-010 在已部署候选上的验收；IQA-009 以 V1 持久基线逐项核对；IQA-010 在平台受控重新部署能力可用时重新部署同一候选比对，否则只读核对并引用开发者升级演练记录（HT-DLV-20261117-88E4DAE97F6A-0005）。
- 机密 `INITIAL_SETUP_TOKEN`、`INITIAL_ADMIN_*` 的 `test` 值由人在平台任务中提供。
- V1 线上数据的真实升级结果、UAT、人工审批、发布与合并。

## 非阻塞观察与剩余风险

- 升级中断用例在 `dev` 环境中实际走的是“客户端关闭物理连接”分支，未覆盖服务端 `pg_terminate_backend` 路径；design 明确允许该替代方式，且两者对未提交事务的效果相同，不构成阻塞。
- `DatabaseAuthModeCheck` 的错误说明经 Spring Boot `Application run failed` 异常日志输出（`EnvironmentPostProcessor` 延迟日志未单独回放），进程仍以非零退出；运维可在容器日志中看到原因，与 TD-031“失败即可见”目标一致。
- `v2-iqa-008` 与 V1 IQA-013/014 一样依赖共享 `dev` 库全局数据（第五项、积压数），以同一时刻独立 SQL 计算期望值；其他交付在读取间隙写入本周数据时可能出现偶发不一致。
- `v2-iqa-009` 开发者核对断言“演练在同一自然周内完成”，若脚本恰好跨越周一 00:00（公司时区）运行会失败重跑；属测试时序边界，不影响产品行为。
- 回滚到 V1 镜像期间 V1 不识别停用状态（design「Rollback」已记录），是否回滚属发布阶段人工决定。
