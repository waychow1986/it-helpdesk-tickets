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

## Coding Session NR-DLV-20261116-72F2B9041E74-0031-b01

### 实施范围

- 模式：`coding-repair`，第 1 批。处理两条机器反馈（非人工决定）：
  1. 平台执行结果（test-environment，NR-DLV-20261116-72F2B9041E74-0030:0）：候选部署后请求 `https://cand-72f2b9041e74.eastus2.cloudapp.azure.com/actuator/health` 报 `HttpConnectTimeoutException: HTTP connect timed out`。
  2. upstream-change：自基线 `94b73d20f1e6` 起新增 `reports/convergence.md`。该报告结论为“通过”、阻塞问题“无”、无 findings，没有需要 Coding 修订的条目。
- 只做根因排查和复现，不改动已批准的产品行为或技术决定，不追加任务。

### 完成与受阻任务

- 本次没有新勾选的任务：T001～T060 在上一 Session 已全部勾选，`progress.completedTaskIds` 为空。
- 受阻任务：无。
- 处理反馈的结论：在 Agent Pod 中按候选容器的条件运行，无法复现启动失败或就绪失败。平台报的是到网关公网地址的 TCP 连接超时，请求在到达容器之前就失败了。现有证据都不指向应用代码、`Dockerfile` 或 `deployment.json` 的缺陷，因此本次没有修改代码和运行时契约（理由见“执行命令与结果”和“剩余风险”）。

### 变更文件

| 路径 | 变更 | 来源任务 |
|---|---|---|
| `.ai-delivery/features/001-v1/reports/implementation.md` | 追加本 Session 记录（排查过程、复现命令与结论），未改写早先内容 | 修复反馈（候选健康检查连接超时） |
| `.ai-delivery/features/001-v1/.state/results/NR-DLV-20261116-72F2B9041E74-0031-b01.json` | 新增本次结果文件 | 修复反馈 |

### 需求与场景覆盖

| 来源（FR / AC / IQA） | 实现路径 | 测试路径 | 结果 |
|---|---|---|---|
| 部署契约 `healthPath` `/actuator/health`（design“部署契约”） | `src/main/resources/application.yml`（actuator `health` 暴露）、`config/SecurityConfig.java`（`permitAll`） | 本次 localhost 启动探测（见下表） | 通过：可执行 jar 启动约 6 秒后返回 `200 {"status":"UP"}` |
| 候选身份端点 `/api/candidate-identity` | `src/main/java/com/ithelpdesk/platform/CandidateIdentityController.java` | 本次 localhost 探测 | 通过：返回注入的 `AIDELIVERY_DELIVERY_ID` / `AIDELIVERY_CANDIDATE_SHA` 原值 |
| FR-029、FR-030、NFR-001（启动迁移与初始管理员引导） | `db/migration/V1__init.sql`、`InitialAdminBootstrap` | 本次 localhost 启动（真实 `ticket-db` dev 实例） | 通过：Flyway 与引导在启动阶段完成，没有 ERROR 日志 |
| 全部 FR / IQA-001～IQA-014 | 未改动 | `mvn test`（74 个单元/切片测试） | 通过（回归确认，代码未改动） |

### 执行命令与结果

| 命令 | 环境 | 依赖实例（真实 / 替身） | 结果 | 证据 |
|---|---|---|---|---|
| `mvn -B -ntp -q -DskipTests package` | Agent Pod | 不涉及 | 通过 | 生成 `target/helpdesk.jar`（53 MB） |
| 只复制 `pom.xml`、`config`、`src` 到仓库外临时目录后执行 `mvn -B -ntp -q -DskipTests package` | Agent Pod，模拟 `Dockerfile` 构建阶段的构建上下文 | 不涉及 | 通过 | jar 内包含 `application.yml`、`db/migration/V1__init.sql`、`azure-identity-extensions-1.2.10.jar`；说明 Dockerfile 的 COPY 范围够用 |
| `java -XX:MaxRAMPercentage=75 -jar target/helpdesk.jar`，再轮询 `/actuator/health` 和 `/api/candidate-identity` | Agent Pod localhost:8080 | 真实 `ticket-db` dev 实例（Pod 工作负载身份，Entra 令牌） | 通过 | `Started HelpdeskApplication in 5.464 seconds`；health 返回 200 `{"status":"UP",...}` |
| 同上，另加 `env -u HOME`、`-Duser.home=?`、`-XX:ActiveProcessorCount=1`（模拟 UID 10001 在镜像中没有 passwd 条目、单核） | Agent Pod localhost:8080 | 真实 `ticket-db` dev 实例 | 通过 | 约 6 秒后 UP；身份端点返回注入值 `{"deliveryId":"x","candidateSha":"y"}`（测试值） |
| `-Xmx192m -XX:ActiveProcessorCount=1` 启动后连续请求 `/login`、`/actuator/health`，读取 `/proc/<pid>/status` | Agent Pod localhost:8080 | 真实 `ticket-db` dev 实例 | 通过 | 约 8 秒后 UP；`VmRSS` 约 297 MB |
| `-Djava.io.tmpdir=<只读目录>` 启动 | Agent Pod localhost | 真实 `ticket-db` dev 实例 | 失败（预期内的诊断） | Tomcat 报 `Unable to create tempDir`。说明只有临时目录不可写才会导致启动失败；`eclipse-temurin:21-jre` 的 `/tmp` 是 1777 权限，平台运行环境画像也没有说明会使用只读根文件系统 |
| `getent hosts cand-72f2b9041e74.eastus2.cloudapp.azure.com`；`curl -m 15 https://cand-72f2b9041e74.eastus2.cloudapp.azure.com/actuator/health` | Agent Pod | 不涉及 | DNS 正常；TCP 连接超时 | DNS 解析到 `57.165.12.113`；`curl: (28) Connection timed out after 15002 milliseconds`，与平台观察一致。Pod 出站策略也可能限制这条访问，所以这只是辅助证据 |
| `mvn -B -ntp checkstyle:check` | Agent Pod | 不涉及 | 通过 | `You have 0 Checkstyle violations.` |
| `mvn -B -ntp test` | Agent Pod | 不涉及（单元/切片） | 通过 | `Tests run: 74, Failures: 0, Errors: 0, Skipped: 0` |
| `bash -n .ai-delivery/features/001-v1/runtime/developer-functional-run.sh` | Agent Pod | 不涉及 | 通过 | 语法检查通过 |

本次没有重复运行集成测试和功能测试：代码与测试都没有改动，上一 Session 已用真实 dev 实例运行并通过。启动探测用的是真实 `ticket-db` dev 实例，没有使用替身。

### Runtime 与下游门禁就绪制品

- `Dockerfile`：未改动。多阶段构建，运行阶段 `eclipse-temurin:21-jre`，`USER 10001`，监听 HTTP 8080。本次验证了构建上下文可以打出完整 jar。
- `runtime/deployment.json`：未改动。`containerPort` 8080、`healthPath` `/actuator/health`、`candidateIdentityPath` `/api/candidate-identity`、`requiresPrivateNetwork` false，验证命令按类别组织。
- Candidate identity endpoint：未改动。本次 localhost 验证它原样返回平台注入的两个变量，没有虚构的回退值。
- `runtime/developer-functional-run.sh`：未改动，`bash -n` 通过。

### 延后平台证据

- 候选重新部署后，`/actuator/health` 与 `/api/candidate-identity` 在 HTTPS 主机名上的可达性和返回值，由平台候选部署产生。本 Session 无法在 Agent Pod 中获得，不声称候选环境已通过。
- 候选容器日志（Flyway 迁移、`ticket-db` test 实例连接、Entra 令牌获取）和 Pod 就绪状态只有平台可以查看。
- 镜像构建（ACR Tasks）、Independent QA（IQA-001～IQA-015）。

### 剩余风险

- 根因判断：平台错误是 `HTTP connect timed out`，也就是到网关公网地址 `57.165.12.113` 的 TCP 连接没有建立，失败发生在 TLS 和 HTTP 之前。网关本身可达时，即使后端容器没有就绪，通常也会接受连接并返回 5xx，而不是连接超时。因此现有证据更指向网关、负载均衡、公网 IP 或网络策略这一层，或者探测时这些尚未就绪。这个判断无法在 Agent Pod 中证实。
- 如果平台日志显示容器本身没有就绪，可能的原因及其在 Pod 中的验证情况：
  1. 候选出站策略没有放行 Entra 令牌终结点，导致无法连接 `ticket-db` test 实例，启动失败。这属于平台和依赖绑定层面的问题。
  2. 根文件系统只读，`java.io.tmpdir` 不可写，Tomcat 无法启动。画像未说明会这样运行；如果确实如此，需要平台提供可写的 `/tmp`。
  3. 容器内存上限过低。启动后 RSS 约 297 MB，内存上限低于约 384 MiB 时有被 OOMKill 的风险。画像没有给出资源上限。
  这几项都没有平台证据支持，所以本次没有为假设的原因改动已批准的 JVM 参数（`-XX:MaxRAMPercentage=75`）或运行时契约。
- 如果重新部署仍然失败，需要平台提供候选 Pod 事件与容器日志，下一次修复 Session 再按日志定位。

### 实施结论

部分完成：本次只完成了排查和记录，没有修复任何问题。在 Agent Pod 中，构建、静态检查、74 个单元测试和功能脚本语法检查都通过；可执行 jar 连接真实 `ticket-db` dev 实例，并在模拟容器条件（无 HOME、未解析的 `user.home`、单核、受限堆）下约 6～8 秒就绪；健康端点和候选身份端点的行为符合部署契约。候选健康检查的连接超时无法在 Pod 中复现，现有证据指向网关、网络层，而不是应用代码。因此本次没有修改代码、`Dockerfile` 或 `deployment.json`。候选环境是否可达要等平台重新部署后确认，本次不声称候选环境已通过。

## Coding Session NR-DLV-20261116-72F2B9041E74-0035-b01

### 实施范围

- 模式：`coding-repair`，第 1 批。处理两条机器反馈（非人工决定）：
  1. 平台执行结果（test-environment，NR-DLV-20261116-72F2B9041E74-0034:0）：候选重新部署后，请求 `https://cand-72f2b9041e74.eastus2.cloudapp.azure.com/actuator/health` 仍报 `HttpConnectTimeoutException: HTTP connect timed out`。这是同一主机名第二次出现这个错误（第一次是 NR-DLV-20261116-72F2B9041E74-0030）。
  2. upstream-change：自基线 `5b07ffd945d1` 起修改了 `reports/convergence.md`。新增章节「本轮新增 Session（NR-DLV-20261116-72F2B9041E74-0031-b01）复核」，结论仍为“通过”，没有 findings。其中一条非阻塞观察指出，上一 Session「实施结论」写“部分完成”，而结果文件 `outcome` 为 `passed`，建议统一措辞。本 Session 的处理方式：本段「实施结论」与结果文件 `outcome` 使用一致的说法。早先 Session 的记录按账本规则保持不变。
- 本次只做根因定位和回归确认，不改动已批准的产品行为或技术决定，不追加任务。

### 完成与受阻任务

- 本次没有新勾选的任务：T001～T060 在早先 Session 已全部勾选，`progress.completedTaskIds` 为空。
- 受阻任务：无（所有实现任务都已完成）。候选部署后的健康检查受阻于平台网关的 HTTPS 入口，需要运维处理，见结果文件 `inputsNeeded`（`suggestedProvider`=operator）。
- 新定位到的根因证据：候选主机名的 **80 端口可以建立 TCP 连接**，网关返回 `HTTP/1.1 301 Moved Permanently` 并跳转到 `https://…`；**443 端口的 TCP 连接超时**，既没有被拒绝，也没有完成 TLS 握手。同一 Pod 访问其他公网 HTTPS 站点的 443 端口正常。所以失败点是网关或负载均衡上 443 的监听、规则或证书没有就绪，请求根本没有到达候选容器。应用、`Dockerfile` 和 `deployment.json` 都无法影响这一层。

### 变更文件

| 路径 | 变更 | 来源任务 |
|---|---|---|
| `.ai-delivery/features/001-v1/reports/implementation.md` | 在末尾追加本 Session 记录（端口级定位证据、回归命令与结论），早先内容未改写 | 修复反馈（候选健康检查连接超时） |
| `.ai-delivery/features/001-v1/.state/results/NR-DLV-20261116-72F2B9041E74-0035-b01.json` | 新增本次结果文件 | 修复反馈 |

### 需求与场景覆盖

| 来源（FR / AC / IQA） | 实现路径 | 测试路径 | 结果 |
|---|---|---|---|
| 部署契约 `healthPath` `/actuator/health`（design“部署契约”） | `src/main/resources/application.yml`、`src/main/java/com/ithelpdesk/config/SecurityConfig.java` | 本次 localhost 启动探测 | 通过：可执行 jar 启动约 6 秒后返回 `200 {"status":"UP","groups":["liveness","readiness"]}` |
| 候选身份端点 `/api/candidate-identity` | `src/main/java/com/ithelpdesk/platform/CandidateIdentityController.java` | 本次 localhost 探测 | 通过：原样返回注入的测试值 `{"deliveryId":"DLV-test","candidateSha":"sha-test"}` |
| FR-029、FR-030、NFR-001（启动迁移与初始管理员引导） | `src/main/resources/db/migration/V1__init.sql`、`src/main/java/com/ithelpdesk/user/InitialAdminBootstrap.java` | 本次 localhost 启动（真实 `ticket-db` dev 实例） | 通过：`Successfully validated 1 migration`，启动无 ERROR 日志 |
| 全部 FR / IQA-001～IQA-014 | 未改动 | `mvn -B -ntp test`（74 个单元 / 切片测试） | 通过（回归确认，代码未改动） |

### 执行命令与结果

| 命令 | 环境 | 依赖实例（真实 / 替身） | 结果 | 证据 |
|---|---|---|---|---|
| `getent hosts cand-72f2b9041e74.eastus2.cloudapp.azure.com` | Agent Pod | 不涉及 | 通过 | 解析到 `57.165.12.113`，与上一 Session 相同 |
| `curl -m 15 https://cand-72f2b9041e74.eastus2.cloudapp.azure.com/actuator/health` | Agent Pod | 不涉及 | 失败（复现平台现象） | `curl: (28) Connection timed out after 15002 milliseconds` |
| `curl -m 15 -i http://cand-72f2b9041e74.eastus2.cloudapp.azure.com/actuator/health` | Agent Pod | 不涉及 | 网关在 80 端口响应 | `HTTP/1.1 301 Moved Permanently`，`location: https://cand-72f2b9041e74.eastus2.cloudapp.azure.com/actuator/health` |
| `curl -m 15 -i http://cand-72f2b9041e74.eastus2.cloudapp.azure.com/.well-known/acme-challenge/test` | Agent Pod | 不涉及 | 同样返回 301 跳转到 HTTPS | 说明 80 端口上的请求都由网关的 HTTP→HTTPS 跳转规则处理 |
| `timeout 10 bash -c 'echo > /dev/tcp/57.165.12.113/443'` 与 `/dev/tcp/57.165.12.113/80` | Agent Pod | 不涉及 | 443 未连通；80 连通 | 443 端口 TCP 连接超时，80 端口立即建立连接 |
| `curl https://www.microsoft.com`、`curl https://portal.azure.com` | Agent Pod | 不涉及 | 对照：能连通 | 分别返回 200、403。说明 Pod 出站的 443 端口没有被普遍阻断 |
| `mvn -B -ntp -q -DskipTests package` | Agent Pod | 不涉及 | 通过 | 生成 `target/helpdesk.jar`（53 MB） |
| `AIDELIVERY_DELIVERY_ID=DLV-test AIDELIVERY_CANDIDATE_SHA=sha-test java -XX:MaxRAMPercentage=75 -XX:ActiveProcessorCount=1 -jar target/helpdesk.jar`，再轮询 `/actuator/health`、`/api/candidate-identity` | Agent Pod localhost:8080 | 真实 `ticket-db` dev 实例（Pod 工作负载身份，Entra 令牌） | 通过 | `Started HelpdeskApplication in 5.932 seconds`；第 8 次轮询时 health 返回 200 UP；身份端点返回注入值 |
| `mvn -B -ntp checkstyle:check` | Agent Pod | 不涉及 | 通过 | `You have 0 Checkstyle violations.` |
| `mvn -B -ntp test` | Agent Pod | 不涉及（单元 / 切片测试，Mockito 替身只用于切片测试，不作为 functional 证据） | 通过 | `Tests run: 74, Failures: 0, Errors: 0, Skipped: 0` |
| `bash -n .ai-delivery/features/001-v1/runtime/developer-functional-run.sh` | Agent Pod | 不涉及 | 通过 | 语法检查通过 |

本次没有重复运行集成测试和功能测试：代码与测试都没有改动，第一个 Session 已用真实 `ticket-db` dev 实例运行并通过，Convergence 两轮复核也确认这些证据仍然有效。本次启动探测使用真实 dev 实例，没有使用数据库替身。

### Runtime 与下游门禁就绪制品

- `Dockerfile`：未改动。多阶段构建，运行阶段 `eclipse-temurin:21-jre`，`USER 10001`，`EXPOSE 8080`，容器监听 HTTP 8080，TLS 按画像由网关终止。
- `runtime/deployment.json`：未改动。`containerPort` 8080、`healthPath` `/actuator/health`、`candidateIdentityPath` `/api/candidate-identity`、`requiresPrivateNetwork` false，验证命令按类别组织。
- Candidate identity endpoint：未改动。本次在 localhost 再次验证，它原样返回注入值，没有虚构的回退值。
- `runtime/developer-functional-run.sh`：未改动，`bash -n` 通过。

### 延后平台证据

- 平台网关为 `cand-72f2b9041e74.eastus2.cloudapp.azure.com` 提供的 HTTPS（443）监听、负载均衡规则和 Let's Encrypt 证书状态，只有平台和运维能查看与修复。
- 网关 443 恢复后，候选重新部署后 `/actuator/health` 与 `/api/candidate-identity` 在 HTTPS 主机名上的返回值，以及候选容器日志（Flyway、`ticket-db` test 实例连接）。
- 镜像构建（ACR Tasks）、Independent QA（IQA-001～IQA-015）、UAT、审批与合并。

### 剩余风险

- 根因定位：同一网关 IP `57.165.12.113` 在 80 端口有响应（301 跳转到 HTTPS），443 端口的 TCP 连接超时。所以平台探测失败发生在网关或负载均衡的 443 入口，与应用是否就绪无关：后端没有就绪时，网关应当完成 TLS 并返回 5xx，而不是让 TCP 连接超时。可能的原因包括：网关 HTTPS 监听器没有生效（例如证书尚未签发，或签发失败导致 listener 未就绪），以及负载均衡或 NSG 没有放行 443。具体是哪一项需要平台查看 Gateway、Certificate 和 LB 的状态，Agent Pod 无法确认。
- 80 端口上 `/.well-known/acme-challenge/*` 也被跳转到 HTTPS。如果证书使用 HTTP-01 验证，这可能导致证书一直无法签发，进而导致 443 监听不可用。这是一个推测，需要平台核实。
- 已经连续两次重新部署失败，现象相同。如果不经运维处理就再次部署，大概率仍然失败，所以本次请求运维介入，而不是再次直接提交 `passed`。
- 应用侧的潜在风险（出站到 Entra 受限、`/tmp` 不可写、内存上限低于约 384 MiB）在上一 Session 已经列出。目前没有平台证据支持其中任何一项，因此本次没有为这些假设修改已批准的 JVM 参数或运行时契约。

### 实施结论

需要运维输入：实现任务 T001～T060 都已完成，本次没有修改代码。构建、静态检查、74 个单元测试和功能脚本语法检查都通过；应用连接真实 `ticket-db` dev 实例约 6 秒就绪，健康端点和候选身份端点符合部署契约。候选健康检查失败的根因已经定位到平台网关：80 端口有响应，443 端口的 TCP 连接超时。应用侧没有可以修复的问题，需要运维修复网关的 HTTPS 入口后重新部署。本次不声称候选环境已通过。

## Coding Session NR-DLV-20261116-72F2B9041E74-0036-b01

### 实施范围

- 模式：`coding-repair`，第 1 批。处理两条反馈：
  1. 平台执行结果（机器判断，NR-DLV-20261116-72F2B9041E74-0034:0）：候选 `/actuator/health` 在 HTTPS 主机名上报 `HttpConnectTimeoutException: HTTP connect timed out`。
  2. 人工审批意见（HT-DLV-20261116-72F2B9041E74-0009，决定 ProvideInput）：平台缺陷已定位并修复。候选入口 Service 的端口只在创建时从共享网关复制一次，而网关当时没有 HTTPS 监听器，因此入口缺少 443。Runner 已更新，要求按原代码重新部署候选，应用侧不需要修改。
- 这与上一 Session（NR-DLV-20261116-72F2B9041E74-0035-b01）定位的结论一致：80 端口可达，443 端口 TCP 超时。本次按人工意见不修改应用代码、`Dockerfile` 或 `deployment.json`，只做回归确认，然后提交 `passed`，交给平台重新部署。

### 完成与受阻任务

- 本次没有新勾选的任务：T001～T060 在早先 Session 已全部勾选，`progress.completedTaskIds` 为空。
- 受阻任务：无。上一 Session 的运维输入请求 IN-OPS-001 已由人工审批意见答复（平台缺陷已修复）。

### 变更文件

| 路径 | 变更 | 来源任务 |
|---|---|---|
| `.ai-delivery/features/001-v1/reports/implementation.md` | 在末尾追加本 Session 记录，早先内容未改写 | 修复反馈（候选健康检查连接超时，人工确认属于平台缺陷） |
| `.ai-delivery/features/001-v1/.state/results/NR-DLV-20261116-72F2B9041E74-0036-b01.json` | 新增本次结果文件 | 修复反馈 |

### 需求与场景覆盖

| 来源（FR / AC / IQA） | 实现路径 | 测试路径 | 结果 |
|---|---|---|---|
| 部署契约 `healthPath` / `candidateIdentityPath`（design“部署契约”） | `src/main/java/com/ithelpdesk/platform/CandidateIdentityController.java`、`src/main/resources/application.yml` | 上一 Session 在 localhost 连接真实 `ticket-db` dev 实例完成探测 | 未改动；上一 Session 已验证通过（health 返回 200 UP，身份端点原样返回注入值） |
| 全部 FR / IQA-001～IQA-014 | 未改动 | `mvn -B -ntp test`（74 个单元 / 切片测试） | 通过（回归确认） |
| 功能脚本契约 | `.ai-delivery/features/001-v1/runtime/developer-functional-run.sh` | `bash -n` | 通过 |

### 执行命令与结果

| 命令 | 环境 | 依赖实例（真实 / 替身） | 结果 | 证据 |
|---|---|---|---|---|
| `timeout 10 bash -c 'echo > /dev/tcp/cand-72f2b9041e74.eastus2.cloudapp.azure.com/443'` | Agent Pod | 不涉及 | 仍未连通（预期之中） | 当前部署的候选是在 Runner 修复之前创建的，入口 Service 尚未重新同步端口，要等平台重新部署后才会生效 |
| `curl -m 15 https://cand-72f2b9041e74.eastus2.cloudapp.azure.com/actuator/health` | Agent Pod | 不涉及 | 超时（同上） | `curl: (28) Connection timed out after 15002 milliseconds` |
| `mvn -B -ntp -q checkstyle:check` | Agent Pod | 不涉及 | 通过 | 没有违规输出 |
| `mvn -B -ntp test` | Agent Pod | 不涉及（单元 / 切片测试，Mockito 替身只用于切片测试，不作为 functional 证据） | 通过 | `Tests run: 74, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS` |
| `bash -n .ai-delivery/features/001-v1/runtime/developer-functional-run.sh` | Agent Pod | 不涉及 | 通过 | 语法检查通过 |

本次没有重复运行集成测试和功能测试，因为代码与测试都没有改动。第一个 Session 已用真实 `ticket-db` dev 实例运行并通过，上一 Session 也已用真实 dev 实例完成启动探测。

### Runtime 与下游门禁就绪制品

- `Dockerfile`：未改动（多阶段构建，`USER 10001`，监听 HTTP 8080）。
- `runtime/deployment.json`：未改动（`containerPort` 8080、`healthPath` `/actuator/health`、`candidateIdentityPath` `/api/candidate-identity`、`requiresPrivateNetwork` false）。
- Candidate identity endpoint：未改动，只返回平台注入的值，没有回退值。
- `runtime/developer-functional-run.sh`：未改动，`bash -n` 通过。

### 延后平台证据

- 由更新后的 Runner 重新部署候选，然后在 HTTPS 主机名上请求 `/actuator/health` 与 `/api/candidate-identity` 的结果。
- 候选验证、镜像构建（ACR Tasks）、Independent QA（IQA-001～IQA-015）、UAT、审批与合并。

### 剩余风险

- 本次在 Agent Pod 中探测时，443 端口仍不可达。按人工说明，这是因为已有入口要等重新部署后才会同步端口；Agent 无法在重新部署前验证修复是否生效。如果重新部署后仍然超时，需要平台提供入口 Service 与 Gateway 的状态。
- 应用侧的假设风险（内存上限、`/tmp` 可写性、到 Entra 的出站）与上一 Session 相同，目前没有平台证据支持其中任何一项。

### 实施结论

完成：T001～T060 都已完成，本次没有修改代码。静态检查、74 个单元测试和功能脚本语法检查在本次都实际通过。候选健康检查超时经人工确认是平台入口缺陷，Runner 已修复。现提交 `passed`，交给平台按原代码重新部署。本次不声称候选环境已通过，相关证据要等平台重新部署后产生。
