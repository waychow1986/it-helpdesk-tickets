# Coding 后 Convergence 报告

## 评审范围

- 模式：initial（一次穷尽评审）；nodeRunId `NR-DLV-20261116-72F2B9041E74-0027`。
- 被评审提交：`94b73d2`（aidh: coding NR-DLV-20261116-72F2B9041E74-0026-b01），Coding 第 1 批，覆盖 T001～T060。
- 权威制品（只读）：`intent.md`（INT-001～INT-024）、`spec.md`（FR-001～FR-030、NFR-001～NFR-002、IQA-001～IQA-015、AC-001～AC-020）、`design.md`（TD-001～TD-015、表结构、HTTP 路由、统一行为契约、`data-testid` 约定、配置表、部署契约、验证环境责任矩阵）、`technical-baseline.md`（方案 A）、`tasks-implementation.md`、`reports/implementation.md`、`docs/dependencies.json`。
- 代码与测试：`pom.xml`、`Dockerfile`、`.dockerignore`、`config/checkstyle/`、`src/main/**`（`auth`、`config`、`user`、`category`、`ticket`、`report`、`platform`、`web` 各包与模板）、`src/test/**`（单元 / 切片测试与 12 个 `*IT`）、`e2e/**`（Playwright 配置、`support/`、`tests/iqa-001`～`iqa-014`）、`runtime/deployment.json`、`runtime/developer-functional-run.sh`。
- 评审维度：制品到实现与测试的可追溯性、实施报告中验证证据与测试代码是否一致、用户可见行为与 spec / Intent 是否一致、架构 / Contract / 运维 / 任务覆盖、未批准的外部依赖、下游就绪制品。

## 结论

`通过`。本次未发现 critical / high 阻塞问题，结果文件 `findings` 为空，`outcome` 为 `passed`。

## 阻塞问题

无。

## 制品到实现与测试的可追溯关系

| 来源 | 实现路径 | 测试/证据路径 | 结论 |
|---|---|---|---|
| FR-001、FR-002、FR-003 / AC-001～AC-003 / IQA-001 / T023～T026 | `src/main/java/com/ithelpdesk/user/UserAdminService.java`、`auth/AppUserDetailsService.java`、`auth/CurrentUser.java`、`config/SecurityConfig.java`、`templates/login.html`、`templates/layout.html`、`templates/admin/users.html` | `UserAdminServiceTest`、`AppUserDetailsServiceTest`、`SecurityConfigTest`、`UserAdminIT`、`e2e/tests/iqa-001-accounts.spec.ts` | 一致：仅管理员创建账号、登录名唯一、BCrypt(10)、失败统一 `/login?error`、无注册入口、按身份渲染导航 |
| FR-004 / AC-004 / IQA-009 / T027～T029 | `UserAdminService.changeRole`、`auth/RoleRefreshFilter.java`（`AuthorizationFilter` 之前） | `RoleRefreshFilterTest`、`UserRoleChangeIT`、`e2e/tests/iqa-009-it-staff-role.spec.ts` | 一致：仅 `EMPLOYEE ↔ IT_STAFF`，同一会话下一次请求即按新身份授权，历史处理人保留 |
| FR-005、FR-006 / AC-005 / IQA-008 / T030～T033 | `category/`、`templates/admin/categories.html` | `CategoryServiceTest`、`CategoryIT`、`e2e/tests/iqa-008-categories.spec.ts` | 一致：软删除、有效同名由部分唯一索引兜底、改名后工单显示新名 |
| FR-007～FR-009、PQ-001、前置条件"无有效类别" / AC-006 / IQA-002 / T034～T037、T060 | `ticket/TicketForm.java`、`TicketCommandService.submit`、`TicketController`、`templates/tickets/new.html`、`detail.html` | `TicketFormValidationTest`、`TicketNewPageTest`、`TicketSubmitIT`、`e2e/tests/iqa-002-submit-ticket.spec.ts` | 一致：紧急程度恰为低 / 中 / 高、逐项必填并保留已填内容、`OPEN` + 未分配、仓库层无修改基本信息语句 |
| FR-010～FR-013、FR-019 / AC-007～AC-009 / IQA-003、IQA-004 / T038～T041 | `TicketQueryService`（员工 `submitter_id` 限定、TD-014 统一 403）、`TicketCommandService.addComment`、`templates/tickets/list.html`、`detail.html` | `TicketAccessIT`、`TicketControllerTest`、`e2e/tests/iqa-003-employee-isolation.spec.ts`、`iqa-004-comments.spec.ts` | 一致 |
| FR-014～FR-016、FR-020 / AC-010、AC-011 / IQA-005 / T042～T044 | `TicketCommandService.claim/assign`（`FOR UPDATE` 行锁、目标 `FOR SHARE` 校验 `IT_STAFF`）、`TicketEventRepository` | `TicketAssignmentIT`、`e2e/tests/iqa-005-claim-assign.spec.ts` | 一致：分派对象为当前 IT 同事去除本人，事件记录前后值 |
| FR-017、FR-018、FR-020、FR-021 / AC-012～AC-014 / IQA-006、IQA-007 / T045～T048 | `TicketCommandService.changeStatus`、`TicketRepository.updateStatus`（首次已解决列仅在 NULL 时写入）、`SecurityConfig` URL 授权 | `TicketStatusIT`、`TicketActionAuthorizationTest`、`e2e/tests/iqa-006-status.spec.ts`、`iqa-007-employee-no-actions.spec.ts` | 一致 |
| FR-022～FR-024 / AC-015、AC-016 / IQA-011、IQA-012 / T049～T052 | `TicketSearchCriteria`、`TicketRepository.search`（命名参数、`ILIKE ... ESCAPE '\'`、`LIMIT 51`） | `TicketSearchCriteriaTest`、`TicketSearchIT`、`e2e/tests/iqa-011-keyword-search.spec.ts`、`iqa-012-filters.spec.ts` | 一致 |
| FR-025～FR-028、PQ-002、BQ-002、BQ-003 / AC-017～AC-019 / IQA-010、IQA-013、IQA-014 / T053～T056 | `report/WeekRange`、`WeeklyReportRepository`、`WeeklyReportService`、`ReportController`、`templates/admin/report.html` | `WeekRangeTest`、`WeeklyReportTest`、`WeeklyReportIT`、`e2e/tests/iqa-010-admin-access-control.spec.ts`、`iqa-013-weekly-report.spec.ts`、`iqa-014-backlog.spec.ts` | 一致：类别计本周提交、处理量计本周首次解决并归属当时处理人、"未分配"固定行、平均时长、积压严格 `< now − 72h` 且不受本周限制 |
| FR-029、FR-030、NFR-001 / AC-020 / IQA-015（开发者对应） / T013、T057、T058 | `db/migration/V1__init.sql`、Flyway 配置、`user/InitialAdminBootstrap.java` | `MigrationPolicyTest`、`MigrationIT`、`DataRetentionIT` | 一致（实际重新部署验收属延后平台证据） |
| NFR-002 | Thymeleaf 服务端渲染 | 全部 e2e 以 Chromium 执行 | 一致 |
| TD-011 初始管理员 / spec「前置条件」 / T009、T010、T018 | `InitialAdminBootstrap`、`BootstrapProperties`、`CurrentUser`（NULL 哈希 `enabled=false`） | `InitialAdminBootstrapTest`、`InitialAdminBootstrapIT` | 一致 |
| 宪章「安全与隐私」日志隐私 / T012、T015 | `web/RequestLogFilter.java`、`GlobalExceptionHandler` | `RequestLogFilterTest`、`GlobalExceptionHandlerTest` | 一致：只记录方法、路由模板、状态码、耗时、用户 id |
| 平台就绪制品 / T005、T019、T021、T022 | `Dockerfile`、`.dockerignore`、`platform/CandidateIdentityController.java`、`runtime/deployment.json`、`runtime/developer-functional-run.sh` | `CandidateIdentityControllerTest`；实施报告中 `bash -n` 与完整序列记录 | 一致 |

## 开发者验证证据复核

- 命令与 `deployment.json` 一致：`developerSetupCommands`（`mvn -B -ntp -q dependency:go-offline`、`cd e2e && npm ci`），以及 `staticAnalysis`、`build`、`developerTests`、`integrationTests`、`functionalTests` 五类命令都与 design「部署拓扑」逐字一致。实施报告记录的执行顺序与这些命令相同。
- 测试类命令确实运行测试：
  - `mvn -B -ntp test`：`pom.xml` 中 Surefire 的 `skipTests` 绑定 `${skipUnitTests}`，默认值为 `${skipTests}`（即 false），因此会运行 `*Test.java`。报告记录 74 个测试通过。
  - `mvn -B -ntp verify -Pintegration -DskipUnitTests=true`：`integration` profile 中 Failsafe 只包含 `**/*IT.java`，并绑定 `integration-test`/`verify` 目标。仓库中有 12 个 `*IT` 类（`MigrationIT`、`DataRetentionIT`、`InitialAdminBootstrapIT`、`UserAdminIT`、`UserRoleChangeIT`、`CategoryIT`、`TicketSubmitIT`、`TicketAccessIT`、`TicketAssignmentIT`、`TicketStatusIT`、`TicketSearchIT`、`WeeklyReportIT`），与报告中"40 个测试 / 12 个 `*IT` 类"相符。
  - `functionalTests`：`developer-functional-run.sh` 校验 `AIDH_DEP_TICKET_DB_*`，然后构建并启动 jar，轮询 health（120 秒），执行 `npx playwright test --project=chromium`（依赖 `setup` 项目，teardown 为 `cleanup`），再执行保底清理，最后以 Playwright 退出码退出。`e2e/tests/` 下有 IQA-001～IQA-014 共 14 个 spec，与报告中"16 passed（seed + 14 + cleanup）"相符。
- 测试中没有跳过或弱化：`e2e/` 与 `src/test/` 中未出现 `.skip`、`.fixme`、`.only`、`@Disabled` 或假设性跳过。抽查 `iqa-002`、`iqa-007`、`iqa-009`、`iqa-013` 与 `DataRetentionIT`，断言覆盖了 IQA 预期的关键点：逐项校验与回填；越权 POST 返回 403 且库中状态 / 处理人 / 事件数不变；同一会话下一次请求即按新身份授权；报表各行与在同一时刻用独立 SQL 计算的期望一致；新应用上下文重启前后快照一致。
- 真实依赖与替身边界：集成测试和功能测试都通过 `AIDH_DEP_TICKET_DB_*` 加 Entra 令牌连接真实 `ticket-db` dev 实例，没有使用 H2、Testcontainers 或其他数据库替身。Mockito 替身只出现在单元 / Web 切片测试中，实施报告已注明这些测试不作为 functional 证据。
- 报告如实记录了两次完整序列，以及第一次运行后修正的问题（`skipTests` 绑定、切片测试打印 Boot 生成的密码）。镜像构建和候选部署被如实标为未执行。

## 用户可见行为比较

- 身份与访问控制：URL 层（`/admin/**` 需 ADMIN；`/tickets/new` 与 `POST /tickets` 需 EMPLOYEE；`claim|assign|status` 需 IT_STAFF；`/tickets/**` 需 EMPLOYEE 或 IT_STAFF）和服务层双重授权都与 design 一致。管理员不能访问工单（A-004）。员工访问他人或不存在的工单统一返回 403（TD-014）。
- 工单生命周期：提交、补充说明 / 回复、认领、分派、改状态的提示文本和事件记录都与 design「关键流程」一致。状态相同不写事件；首次已解决列只在为 NULL 时写入（INV-005）。
- 搜索筛选：关键词部分匹配且不区分大小写（A-005）；处理人支持"未分配"；日期为公司时区的闭区间；无结果时显示"无匹配工单"；分页保留查询条件。
- 周报表：标明本周起止日期（"YYYY-MM-DD（周一）至 YYYY-MM-DD（周日）"），四项指标口径与 FR-027、FR-028 以及 BQ-002 / BQ-003 / PQ-002 的人工答案一致，每次打开报表都实时计算。
- Intent 中的体验性要求（网页提交、随时看到进度、长期保存）均未被削弱。Intent / spec 没有"实时推送"或"流式"要求。
- 实现中没有超出 spec 的用户可见能力：无注册、改密码、删除、附件或通知。额外的 `data-testid` 只是定位属性。IT 同事访问不存在的工单返回 404，design 未禁止这一点，也不影响员工侧 TD-014 的统一 403。

## 架构、Contract、运维与任务覆盖

- 架构：Spring Boot 3.5.16 单体，Thymeleaf + Spring JDBC + Flyway，与 TD-001～TD-004 一致。外部依赖只有 `docs/dependencies.json` 声明的 `ticket-db`，没有引入其他云服务、遥测后端、身份产品或数据库。`e2e` 依赖（`@playwright/test`、`pg`、`@azure/identity`、`bcryptjs`）与 TD-015 一致。
- 数据与迁移：`V1__init.sql` 与 design 表结构一致，没有 `DROP`/`TRUNCATE`/`DELETE`（由 `MigrationPolicyTest` 守护）。Flyway 设置 `baseline-on-migrate=true`/`baseline-version=0`。
- 配置：`application.yml` 只引用 `AIDH_DEP_TICKET_DB_*`，并带 `sslmode=require` 与 `AzurePostgresqlAuthenticationPlugin`。Hikari 的 `1800000`/`30000` 毫秒等价于 design 的 30m/30s，报告已说明原因。actuator 只暴露 health，且 `show-details=never`。
- 下游就绪制品：
  - `Dockerfile`：多阶段构建，运行阶段 `USER 10001`、`EXPOSE 8080`，ENTRYPOINT 与 design 一致。`.dockerignore` 与 design 一致。
  - `runtime/deployment.json`：`containerPort` 8080、`healthPath` `/actuator/health`、`candidateIdentityPath` `/api/candidate-identity`、`requiresPrivateNetwork` false，`developerFunctionalTestScript` 指向现有脚本。
  - Candidate identity endpoint：匿名访问，返回 `AIDELIVERY_DELIVERY_ID`/`AIDELIVERY_CANDIDATE_SHA`，缺失时为空字符串，不提供虚构值。
  - `developer-functional-run.sh`：兼容 `set -euo pipefail` 与 stdin 为 `/dev/null`，不输出密码。
- 任务覆盖：T001～T060 均已勾选，并都能在仓库中找到对应实现或测试。没有已批准行为缺少可执行任务；`runtime/qa-run.sh` 未被预置，符合 design 规定。

## 延后平台证据

- 候选验证：Runner 在不可变候选提交上执行 `deployment.json` 中的命令，并对 `developer-functional-run.sh` 做语法检查。
- 镜像构建：ACR Tasks 以根目录 `Dockerfile` 构建，镜像用户解析为 UID 10001（Agent Pod 无 Docker 守护进程，未在本地构建）。
- 候选部署：`cand-{deliveryShortId}` 命名空间部署，以及基于 `ticket-db` `test` 实例的 `healthPath` / `candidateIdentityPath` 校验。
- Independent QA：IQA-001～IQA-015 在已部署候选上的验收，其中 IQA-015 需要平台实际执行一次重新部署或升级。
- 生产首次投入使用时，运维按 design「支持 Runbook」设置初始管理员的首个凭据；以及 UAT、人工审批、发布与合并。

## 非阻塞观察与剩余风险

- `/actuator/health` 返回体含 `groups` 字段（Spring Boot 检测到 Kubernetes 后启用探针分组），`status` 语义与 design 一致，也不暴露详情。平台若对 health 做精确 JSON 匹配需注意，按通常的状态码 / `status` 判定不受影响。
- 报表中的"未分配"、平均处理时长和积压是全库指标。在共享 dev / test 库上，e2e 用同一时刻的独立 SQL 计算期望值，若期间有其他写入者，存在很小的竞态窗口。集成测试用随机历史周隔离，风险已在实施报告中如实记录。
- 筛选下拉中的处理人选项（当前 IT 同事以及曾作为处理人的账号）对员工也可见，只暴露 IT 同事姓名，不暴露他人工单内容，与 design「搜索与筛选」一致。
- HttpSession 存在内存中，单副本运行；重新部署后用户需要重新登录，design 已说明，数据不受影响。
- 初始管理员在首次启动时创建且没有凭据，投入使用前需运维执行 Runbook，这是已批准的 TD-011 设计。
