# Coding 实施报告

## Coding Session NR-DLV-20261116-72F2B9041E74-0026-b01

### 实施范围

- 模式：`coding`，第 1 批；交付 DLV-20261116-72F2B9041E74（V1 · 内部 IT 工单系统）。
- 范围：`tasks-implementation.md` 中全部任务 T001～T060（Phase 1 Setup、Phase 2 Foundational、US-001～US-010、Phase 13 Polish），全新实现（仓库此前只有 README 与交付制品）。
- 依据：`spec.md`（FR-001～FR-030、NFR-001～NFR-002、IQA-001～IQA-015）、`design.md`（TD-001～TD-015、表结构、HTTP 路由、`data-testid` 约定、配置表、部署契约、验证环境责任矩阵）、`technical-baseline.md`（方案 A：Java 21 + Spring Boot 3.5 单体 + Thymeleaf + Spring JDBC + Flyway + PostgreSQL Entra 无密码认证）。
- 执行方式：每个用户故事先写计划中的单元 / 切片测试、`*IT` 集成测试与 Playwright 功能测试，再写生产实现；随后在 Agent Pod 中按 `deployment.json` 的顺序完整执行一次候选验证同款命令序列。

### 完成与受阻任务

- 已完成（本次由 `[ ]` 改为 `[x]`，与结果文件 `progress.completedTaskIds` 一致）：T001、T002、T003、T004、T005、T006、T007、T008、T009、T010、T011、T012、T013、T014、T015、T016、T017、T018、T019、T020、T021、T022、T023、T024、T025、T026、T027、T028、T029、T030、T031、T032、T033、T034、T035、T036、T037、T038、T039、T040、T041、T042、T043、T044、T045、T046、T047、T048、T049、T050、T051、T052、T053、T054、T055、T056、T057、T058、T059、T060。
- 受阻任务：无。
- 跨故事 e2e：IQA-009（T028）、IQA-008（T032）、IQA-002（T036）、IQA-003 / IQA-004（T040）依赖的后续实现（T037、T041、T044、T048、T052）均已在本 Session 完成，这些 e2e 在最终完整序列中整体通过，没有跳过或删减步骤。
- 与设计文字的实现细节差异（均不改变已批准行为）：
  - `spring.datasource.hikari.max-lifetime` / `connection-timeout` 写为毫秒 `1800000` / `30000`（等价于 design 的 30m / 30s）。HikariCP 绑定的是 `long` 型字段，首次启动时 `30s` 无法解析，应用启动失败。
  - `/actuator/health` 返回 `{"status":"UP","groups":["liveness","readiness"]}`。Spring Boot 检测到 Kubernetes 环境后自动启用探针分组，`status` 语义与 design 一致，不暴露任何详情（`show-details=never`）。
  - Surefire 的 `skipTests` 绑定 `skipUnitTests`；`skipUnitTests` 默认取 `${skipTests}`，因此 `-DskipTests` 与 `-DskipUnitTests=true` 都能按预期生效（首次完整验证时发现直接绑定 `false` 会使 `-DskipTests` 失效，已修正并重跑）。
  - 在 design 已授权目录内补充了少量辅助类型与模板：`web/NotFoundException`、`web/ValidationException`、`config/TransactionConfig`、`user/UserForm`、`ticket/TicketPage`、`ticket/TicketDetail`、`templates/error/404.html`。另在 design 约定之外补充了 `data-testid`：`list-title`、`current-user`、`detail-*`、`row-*`、`comment-author/role/time/text`、`event-actor/type/from/to/time`、`user-login/password/display/role/create`、`user-row-*`、`role-select/submit`、`category-new-name/create/rename-input/rename/remove`、`category-row-name`、`report-category-name`、`report-staff-name`、`report-count`、`field-error-*`、`no-category`、`filter-error`。design 约定的全部 `data-testid` 均保留且含义不变。
  - IT 同事访问不存在的工单返回 404（`error/404.html`）。员工访问他人工单或不存在的工单仍统一返回 403（TD-014）。

### 变更文件

| 路径 | 变更 | 来源任务 |
|---|---|---|
| `pom.xml` | 新增：Spring Boot 3.5.16 parent，web / thymeleaf / security / jdbc / validation / actuator，Flyway（core + postgresql），PostgreSQL 驱动，`azure-identity-extensions` 1.2.10；Checkstyle 插件；Surefire `skipTests`→`skipUnitTests`；`integration` profile（Failsafe `*IT.java`） | T001 |
| `.gitignore` | 新增 | T002 |
| `config/checkstyle/checkstyle.xml` | 新增：UnusedImports、AvoidStarImport、禁止 `System.out/err` 与 `printStackTrace` | T003 |
| `src/main/java/com/ithelpdesk/HelpdeskApplication.java`、`src/main/resources/application.yml` | 新增：入口与 design 配置表 | T004 |
| `Dockerfile`、`.dockerignore` | 新增：多阶段构建，运行阶段 `USER 10001`、`EXPOSE 8080` | T005 |
| `e2e/package.json`、`e2e/package-lock.json`、`e2e/playwright.config.ts` | 新增：`@playwright/test` 1.65.1（与 Pod 预装 Chromium 1255 匹配）、`pg`、`@azure/identity`、`bcryptjs`；setup / chromium / cleanup 三个项目 | T006 |
| `src/test/resources/application-it.yml`、`src/test/java/com/ithelpdesk/support/`（`IntegrationTestBase`、`MutableClock`、`TestClockConfig`、`RunPrefix`、`TestData`、`LogCapture`、`WebSliceTestBase`、`WebSliceTestConfig`、`TicketSamples`） | 新增：可变测试时钟、运行前缀、合成数据与按前缀清理、随机历史周 | T007 |
| `src/test/java/com/ithelpdesk/MigrationIT.java` | 新增 | T008 |
| `src/test/java/com/ithelpdesk/user/InitialAdminBootstrapTest.java`、`src/test/java/com/ithelpdesk/auth/AppUserDetailsServiceTest.java` | 新增 | T009 |
| `src/test/java/com/ithelpdesk/user/InitialAdminBootstrapIT.java` | 新增 | T010 |
| `src/test/java/com/ithelpdesk/auth/SecurityConfigTest.java`、`.../auth/RoleRefreshFilterTest.java`、`.../platform/CandidateIdentityControllerTest.java` | 新增 | T011 |
| `src/test/java/com/ithelpdesk/web/RequestLogFilterTest.java`、`.../web/GlobalExceptionHandlerTest.java` | 新增 | T012 |
| `src/main/resources/db/migration/V1__init.sql` | 新增：五张表、索引与约束，与 design 一致，无 DROP / TRUNCATE / DELETE | T013 |
| `src/main/java/com/ithelpdesk/config/`（`AppProperties`、`ClockConfig`、`WebMvcConfig`、`TransactionConfig`） | 新增 | T014、T018 |
| `src/main/java/com/ithelpdesk/web/`（`AccessDeniedException`、`BadRequestException`、`NotFoundException`、`ValidationException`、`GlobalExceptionHandler`、`RequestLogFilter`、`TimeFormat`）、`src/main/resources/templates/error/`（400 / 403 / 404 / 500） | 新增 | T015 |
| `src/main/java/com/ithelpdesk/user/Role.java`、`AppUser.java`、`UserRepository.java` | 新增 | T016 |
| `src/main/java/com/ithelpdesk/auth/`（`AppUserDetailsService`、`CurrentUser`、`RoleRefreshFilter`、`LoginController`、`HomeController`）、`src/main/java/com/ithelpdesk/config/SecurityConfig.java`、`templates/login.html`、`templates/layout.html`、`static/css/app.css` | 新增 | T017 |
| `src/main/java/com/ithelpdesk/user/InitialAdminBootstrap.java`、`BootstrapProperties.java` | 新增 | T018 |
| `src/main/java/com/ithelpdesk/platform/CandidateIdentityController.java` | 新增 | T019 |
| `e2e/support/db.ts`、`e2e/support/fixtures.ts`、`e2e/support/seed.setup.ts`、`e2e/support/cleanup.teardown.ts` | 新增 | T020 |
| `.ai-delivery/features/001-v1/runtime/developer-functional-run.sh` | 新增 | T021 |
| `.ai-delivery/features/001-v1/runtime/deployment.json` | 新增 | T022 |
| `src/test/java/com/ithelpdesk/user/UserAdminServiceTest.java`、`UserAdminIT.java`、`e2e/tests/iqa-001-accounts.spec.ts` | 新增 | T023、T024、T025 |
| `src/main/java/com/ithelpdesk/user/UserAdminService.java`、`UserAdminController.java`、`UserForm.java`、`templates/admin/users.html` | 新增（含身份切换） | T026、T029 |
| `src/test/java/com/ithelpdesk/user/UserRoleChangeIT.java`、`e2e/tests/iqa-009-it-staff-role.spec.ts` | 新增 | T027、T028 |
| `src/test/java/com/ithelpdesk/category/CategoryServiceTest.java`、`CategoryIT.java`、`e2e/tests/iqa-008-categories.spec.ts` | 新增 | T030、T031、T032 |
| `src/main/java/com/ithelpdesk/category/`（`Category`、`CategoryRepository`、`CategoryService`、`CategoryAdminController`）、`templates/admin/categories.html` | 新增 | T033 |
| `src/test/java/com/ithelpdesk/ticket/TicketFormValidationTest.java`、`TicketSubmitIT.java`、`TicketNewPageTest.java`、`e2e/tests/iqa-002-submit-ticket.spec.ts` | 新增 | T034、T035、T060、T036 |
| `src/main/java/com/ithelpdesk/ticket/`（`TicketStatus`、`Urgency`、`EventType`、`Ticket`、`TicketForm`、`TicketRepository`、`TicketCommandService`、`TicketController`）、`templates/tickets/new.html`、`templates/tickets/detail.html` | 新增 | T037 |
| `src/test/java/com/ithelpdesk/ticket/TicketAccessIT.java`、`TicketControllerTest.java`、`e2e/tests/iqa-003-employee-isolation.spec.ts`、`e2e/tests/iqa-004-comments.spec.ts` | 新增 | T038、T039、T040 |
| `src/main/java/com/ithelpdesk/ticket/`（`TicketComment`、`TicketEvent`、`TicketCommentRepository`、`TicketEventRepository`、`CommentForm`、`TicketQueryService`、`TicketPage`、`TicketDetail`）、`templates/tickets/list.html` | 新增 | T041 |
| `src/test/java/com/ithelpdesk/ticket/TicketAssignmentIT.java`、`e2e/tests/iqa-005-claim-assign.spec.ts` | 新增 | T042、T043 |
| 认领 / 分派（`TicketCommandService.claim/assign`、`TicketRepository.lockForUpdate/updateAssignee`、`UserRepository.findRoleForShare`、详情页操作区） | 新增 | T044 |
| `src/test/java/com/ithelpdesk/ticket/TicketStatusIT.java`、`TicketActionAuthorizationTest.java`、`e2e/tests/iqa-006-status.spec.ts`、`e2e/tests/iqa-007-employee-no-actions.spec.ts` | 新增 | T045、T046、T047 |
| 状态修改与回复（`TicketCommandService.changeStatus`、`TicketRepository.updateStatus`、详情页状态表单） | 新增 | T048 |
| `src/test/java/com/ithelpdesk/ticket/TicketSearchCriteriaTest.java`、`TicketSearchIT.java`、`e2e/tests/iqa-011-keyword-search.spec.ts`、`e2e/tests/iqa-012-filters.spec.ts` | 新增 | T049、T050、T051 |
| `src/main/java/com/ithelpdesk/ticket/TicketSearchCriteria.java`、`TicketRepository.search/findAssigneeOptions`、列表筛选表单与分页 | 新增 | T052 |
| `src/test/java/com/ithelpdesk/report/WeekRangeTest.java`、`WeeklyReportTest.java`、`WeeklyReportIT.java`、`e2e/tests/iqa-010-admin-access-control.spec.ts`、`e2e/tests/iqa-013-weekly-report.spec.ts`、`e2e/tests/iqa-014-backlog.spec.ts` | 新增 | T053、T054、T055 |
| `src/main/java/com/ithelpdesk/report/`（`WeekRange`、`WeeklyReport`、`WeeklyReportRepository`、`WeeklyReportService`、`ReportController`）、`templates/admin/report.html` | 新增 | T056 |
| `src/test/java/com/ithelpdesk/MigrationPolicyTest.java`、`src/test/java/com/ithelpdesk/DataRetentionIT.java` | 新增 | T057、T058 |
| `.ai-delivery/features/001-v1/reports/implementation.md` | 新增（本报告） | T059 |
| `.ai-delivery/features/001-v1/tasks-implementation.md` | 仅把 T001～T060 的 `[ ]` 改为 `[x]` | 全部 |

### 需求与场景覆盖

| 来源（FR / AC / IQA） | 实现路径 | 测试路径 | 结果 |
|---|---|---|---|
| FR-001、FR-002、FR-003 / AC-001～AC-003 / IQA-001 | `user/UserAdminService.java`、`auth/`、`config/SecurityConfig.java`、`templates/login.html`、`templates/layout.html`、`templates/admin/users.html` | `UserAdminServiceTest`、`SecurityConfigTest`、`AppUserDetailsServiceTest`、`UserAdminIT`、`e2e/tests/iqa-001-accounts.spec.ts` | 通过 |
| FR-004 / AC-004 / IQA-009 | `UserAdminService.changeRole`、`auth/RoleRefreshFilter.java` | `RoleRefreshFilterTest`、`UserRoleChangeIT`、`e2e/tests/iqa-009-it-staff-role.spec.ts` | 通过 |
| FR-005、FR-006 / AC-005 / IQA-008 | `category/` | `CategoryServiceTest`、`CategoryIT`、`e2e/tests/iqa-008-categories.spec.ts` | 通过 |
| FR-007、FR-008、FR-009、PQ-001 / AC-006 / IQA-002；前置条件"无有效类别" | `TicketForm`、`TicketCommandService.submit`、`templates/tickets/new.html` | `TicketFormValidationTest`、`TicketNewPageTest`、`TicketSubmitIT`、`e2e/tests/iqa-002-submit-ticket.spec.ts` | 通过 |
| FR-010～FR-013、FR-019 / AC-007～AC-009 / IQA-003、IQA-004 | `TicketQueryService`、`TicketCommandService.addComment`、`templates/tickets/list.html`、`detail.html` | `TicketAccessIT`、`TicketControllerTest`、`e2e/tests/iqa-003-employee-isolation.spec.ts`、`iqa-004-comments.spec.ts` | 通过 |
| FR-014～FR-016、FR-020 / AC-010、AC-011 / IQA-005 | `TicketCommandService.claim/assign`、`TicketEventRepository` | `TicketAssignmentIT`、`e2e/tests/iqa-005-claim-assign.spec.ts` | 通过 |
| FR-017、FR-018、FR-020、FR-021 / AC-012～AC-014 / IQA-006、IQA-007 | `TicketCommandService.changeStatus`、`SecurityConfig` URL 授权 | `TicketStatusIT`、`TicketActionAuthorizationTest`、`e2e/tests/iqa-006-status.spec.ts`、`iqa-007-employee-no-actions.spec.ts` | 通过 |
| FR-022～FR-024 / AC-015、AC-016 / IQA-011、IQA-012 | `TicketSearchCriteria`、`TicketRepository.search` | `TicketSearchCriteriaTest`、`TicketSearchIT`、`e2e/tests/iqa-011-keyword-search.spec.ts`、`iqa-012-filters.spec.ts` | 通过 |
| FR-025～FR-028、PQ-002 / AC-017～AC-019 / IQA-010、IQA-013、IQA-014 | `report/`、`templates/admin/report.html` | `WeekRangeTest`、`WeeklyReportTest`、`WeeklyReportIT`、`e2e/tests/iqa-010/013/014` | 通过 |
| FR-029、FR-030、NFR-001 / AC-020 / IQA-015（开发者侧对应） | `db/migration/V1__init.sql`、Flyway 配置、`InitialAdminBootstrap` | `MigrationPolicyTest`、`MigrationIT`、`DataRetentionIT` | 通过（重新部署验收延后至 Independent QA） |
| NFR-002 | Thymeleaf 服务端渲染网页 | 全部 e2e 均以 Chromium 浏览器执行 | 通过 |
| TD-011 初始管理员引导 | `user/InitialAdminBootstrap.java`、`UserRepository` 引导方法 | `InitialAdminBootstrapTest`、`InitialAdminBootstrapIT` | 通过 |
| 宪章「安全与隐私」：日志不含客户内容 / 凭据 | `web/RequestLogFilter.java`、`GlobalExceptionHandler` | `RequestLogFilterTest`、`GlobalExceptionHandlerTest` | 通过 |
| 平台候选身份端点 | `platform/CandidateIdentityController.java` | `CandidateIdentityControllerTest`；localhost `curl` 返回注入值 | 通过 |

### 执行命令与结果

| 命令 | 环境 | 依赖实例（真实 / 替身） | 结果 | 证据 |
|---|---|---|---|---|
| `mvn -B -ntp -q dependency:go-offline` | Agent Pod，`bash -euo pipefail`，stdin `/dev/null`（按 `deployment.json` 顺序） | 不涉及 | 通过（退出码 0） | 依赖解析完成 |
| `cd e2e && npm ci` | 同上 | 不涉及 | 通过（退出码 0） | 安装 67 个包 |
| `mvn -B -ntp checkstyle:check` | 同上 | 不涉及 | 通过（退出码 0） | 0 违规 |
| `mvn -B -ntp -DskipTests package` | 同上 | 不涉及 | 通过（退出码 0） | 生成 `target/helpdesk.jar`，打包阶段未运行测试 |
| `mvn -B -ntp test` | 同上，单元 / Web 切片测试 | 替身：服务层与仓库层 Mockito 替身，只用于纯逻辑与 Web 层授权 / 渲染校验，不作为功能证据 | 通过（退出码 0） | `Tests run: 74, Failures: 0, Errors: 0, Skipped: 0` |
| `mvn -B -ntp verify -Pintegration -DskipUnitTests=true` | 同上，完整 Spring 上下文 + MockMvc | 真实 `ticket-db` dev 实例（`AIDH_DEP_TICKET_DB_*`、Pod 工作负载身份 Entra 令牌、`sslmode=require`） | 通过（退出码 0） | `Tests run: 40, Failures: 0, Errors: 0, Skipped: 0`（12 个 `*IT` 类）；每类使用 `it-<8 位随机十六进制>-` 前缀，`@AfterAll` 按前缀清理 |
| `bash .ai-delivery/features/001-v1/runtime/developer-functional-run.sh` | 同上；localhost:8080 启动 jar + Playwright 1.65.1 Chromium（headless） | 真实 `ticket-db` dev 实例 | 通过（退出码 0） | `16 passed`（seed setup + IQA-001～IQA-014 共 14 个流程 + cleanup teardown），保底 cleanup `1 passed`，输出"功能测试全部通过"；运行前缀形如 `e2e-<epoch>-<随机>-`，密码只存在于进程环境、未输出 |
| `bash -n .ai-delivery/features/001-v1/runtime/developer-functional-run.sh` | 同上 | 不涉及 | 通过 | 语法检查无输出 |
| 清理核对（`node` + `pg` + `@azure/identity` 查询） | Agent Pod | 真实 `ticket-db` dev 实例 | 通过 | 以 `it-` / `e2e-` 前缀的账号、类别均为 0，工单表 0 行；库中只剩启动引导创建的 `admin`（`password_hash` 为 NULL，待按 Runbook 设置） |
| `java -jar target/helpdesk.jar` + `curl /actuator/health`、`/api/candidate-identity`、`/tickets`、`-I /login` | Agent Pod，localhost | 真实 `ticket-db` dev 实例 | 通过（辅助证据） | Flyway 在空库上建表并创建 `flyway_schema_history`；日志"已创建初始管理员账号 admin，需按运维手册设置首个凭据"；health `UP`；身份端点返回注入的测试值；未登录 302 到 `/login`；响应头含 `X-Content-Type-Options: nosniff`、`X-Frame-Options: DENY`、`Content-Security-Policy: default-src 'self'` |
| 日志敏感信息检查（`grep` 运行日志中的随机密码前缀 `Pw-` 与 `generated security password`） | Agent Pod | 不涉及 | 通过 | 0 处命中（首次运行时 Web 切片测试曾因未加载 `AppUserDetailsService` 而由 Boot 自动配置内存用户并打印生成的密码，已修正并重跑） |

本 Session 共执行两次完整序列。第一次全部通过，之后修正了 `skipTests` 绑定与测试日志问题；第二次在删除 `target/` 与 `e2e/node_modules/` 后从零执行，结果即上表。

### Runtime 与下游门禁就绪制品

- `Dockerfile`：多阶段构建。构建阶段 `maven:3.9-eclipse-temurin-21` 执行 `mvn -B -ntp -DskipTests package`；运行阶段 `eclipse-temurin:21-jre`，`/app/app.jar`，`USER 10001`（数字非 root UID），`EXPOSE 8080`，`ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app/app.jar"]`。`.dockerignore` 排除 `.git`、`target`、`e2e/node_modules`、`.ai-delivery`。Agent Pod 无 Docker 守护进程，镜像未在本地构建。
- `.ai-delivery/features/001-v1/runtime/deployment.json`：`containerPort` 8080；`healthPath` `/actuator/health`；`candidateIdentityPath` `/api/candidate-identity`；`requiresPrivateNetwork` false；`developerSetupCommands` 与五类 `developerValidationCommands` 按 design 填写；`developerFunctionalTestScript` 指向 `.ai-delivery/features/001-v1/runtime/developer-functional-run.sh`。不含 `postgresql` / `migrationCommand`，数据库通过 `AIDH_DEP_TICKET_DB_*` 注入。
- Candidate identity endpoint：`GET /api/candidate-identity` 匿名可访问，返回 `{"deliveryId": $AIDELIVERY_DELIVERY_ID, "candidateSha": $AIDELIVERY_CANDIDATE_SHA}`；变量缺失时为空字符串，不提供虚构值。
- 健康检查：`/actuator/health` 只报告应用就绪（含数据库连通性），`show-details=never`。
- 迁移：应用启动时 Flyway 执行 `V1__init.sql`（只增不删，`baseline-on-migrate=true`/`baseline-version=0`，保留 `*:future` 忽略），可重复执行（`MigrationIT` 验证）。
- `developer-functional-run.sh`：兼容 `bash -euo pipefail` 与 stdin `/dev/null`。会检查依赖变量与端口，生成运行前缀与随机密码（不输出），构建并启动应用，轮询 health 至 UP（120 秒），执行 Playwright。无论成败都执行 teardown 与保底按前缀清理，最后停止应用并以 Playwright 退出码退出。
- 本 Session 未编写或预置 `runtime/qa-run.sh`（由 Independent QA 负责）。

### 延后平台证据

- 候选验证：Runner 在不可变候选提交上执行 `deployment.json` 中的命令，并对 `developer-functional-run.sh` 做 `bash -n`。本 Session 已在 Agent Pod 中以相同命令与 dev 实例执行通过，但候选验证结果本身由平台产生。
- 镜像构建：ACR Tasks 以根目录 `Dockerfile` 构建，镜像用户解析为 UID 10001。本环境无 Docker 守护进程，未构建镜像。
- 候选部署：`cand-{deliveryShortId}` 命名空间部署，以及使用 `ticket-db` `test` 实例时 `healthPath` 与 `candidateIdentityPath` 的校验。
- Independent QA：IQA-001～IQA-015 在已部署候选 HTTPS 地址上的验收，其中 IQA-015 需要平台实际执行一次重新部署 / 升级。
- 初始管理员首个凭据的设置（运维按 design「支持 Runbook」在生产首次投入使用时执行）、UAT、人工审批、发布与合并。

### 剩余风险

- 共享 dev / test 库：报表的"未分配"、平均处理时长与积压是全库指标。e2e 在同一时刻以独立 SQL 计算期望值；若同一时间有其他交付 / 候选在同一项目库写入本周数据，存在极小的竞态窗口。集成测试使用随机历史周隔离。
- 启动引导账号 `admin` 在空库上首次启动时创建，无凭据、不可登录。dev 库中现已存在该账号，test / 生产库首次启动时也会创建。投入使用前需运维按 Runbook 写入 BCrypt 哈希，否则没有可登录的管理员（属于已批准的 TD-011 设计）。
- 会话存于内存，单副本运行。重新部署后用户需重新登录（design 已说明，数据不受影响）。
- `/actuator/health` 在应用启动完成前返回 `OUT_OF_SERVICE`（503），启动完成后为 `UP`。平台健康检查需等待启动完成（本地冷启动约 7 秒）。
- 镜像构建与候选部署尚未执行，Dockerfile 只经过人工审查与本地等价构建（`mvn package`）验证。

### 实施结论

完成。T001～T060 全部实现并勾选，无受阻任务。在 Agent Pod 中按 `deployment.json` 顺序从零执行的结果：静态检查、构建、单元测试 74 个、集成测试 40 个（真实 `ticket-db` dev 实例）、功能测试 IQA-001～IQA-014 共 14 个浏览器流程（localhost + 真实 dev 实例）全部通过；功能测试脚本 `bash -n` 通过；测试数据已按前缀清理。镜像构建、候选部署、Independent QA（含 IQA-015 重新部署验证）等为延后的平台证据，本 Session 未产生也未声称这些结果。
