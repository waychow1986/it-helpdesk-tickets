# 内部 IT 工单系统 V2 实施任务

## 任务规则

- 每项任务使用格式：`- [ ] T### [P?] [US#?] 描述（路径；来源：…；依赖：…）`
- 路径必须位于 design 的 "Component 边界与项目结构" 路径清单内。
- 每项任务引用来源：Specification 中的 US / FR / AC / IQA、design 中的 Contract 或技术决策。
- 测试任务位于对应生产实现任务之前；测试素材优先自行合成，并提交到 design 声明的测试路径。
- 候选验证、部署、Independent QA、UAT、发布和合并结果属于下游平台证据，不作为任务。

补充约定（全部来自 design，不新增决定）：

- 用户故事标签与 spec 对应：`[US1]`=US-001 查看期望解决时间，`[US2]`=US-002 识别超时，`[US3]`=US-003 停用与重新启用，`[US4]`=US-004 停用账号历史照常，`[US5]`=US-005 周报表第五项，`[US6]`=US-006 从 V1 升级。spec 中全部用户故事均为 P1，阶段按 spec 编号顺序排列。
- 本交付在 V1 代码库上增量修改（design「技术上下文」）：Java 包根 `com.ithelpdesk`；`*Test.java` 为单元 / 切片测试（Surefire），`*IT.java` 为连接 `ticket-db` `dev` 实例的集成测试（Failsafe，`-Pintegration`）。根目录 `pom.xml`、`Dockerfile`、`.dockerignore` 在 design 中无变更，本计划不为其创建任务。
- 集成测试、功能测试与升级演练一律连接真实 `ticket-db` `dev` 实例（只从 `AIDH_DEP_TICKET_DB_*` 读取配置，`DefaultAzureCredential` 取令牌），不使用 H2、Testcontainers 或其他数据库替身；单元测试中的替身只用于纯逻辑，不计入 functional / integration 证据（design「验证环境责任矩阵」替身使用说明）。本交付没有使用模拟服务的依赖。
- 全部测试数据为合成数据：集成测试使用 `it-<runId>-` 前缀，功能测试使用运行前缀；报表与超时测试使用可变测试时钟设到随机历史周；“唯一可用管理员”与升级测试使用一次性 schema（`it_admin_<runId>`、`it_upgrade_<runId>`、`e2e_setup_<suffix>`、`e2e_upgrade_<suffix>`）并在结束时 `DROP SCHEMA … CASCADE`（design「快速验证指南」）。任何测试不得修改或清理 V1 持久升级基线前缀 `q15-72f2b9041e74-` 的数据，也不得处置其他交付创建的管理员账号（design「数据库数据契约」）。
- 测试不直接写 `app_user.active` 列：启用状态只经 `AccountStatusService` 或页面改变（design「数据库数据契约」V2 补充）；工单回溯数据只写 `submitted_at` 与 `urgency`，期望值由测试按 `submitted_at + 4/24/72 小时` 独立计算。
- `src/main/resources/db/migration/V1__init.sql` 不得修改（TD-029）；`.ai-delivery/features/002-v1/runtime/qa-run.sh` 只由 Independent QA 编写，Coding 不编写、不预置（design「Component 边界与项目结构」）。
- 测试密码与合成设置口令只以运行时随机值存在于进程环境，不写入仓库、日志或报告；开发者测试不读取任何 `AIDH_SECRET_*`。
- 页面元素定位一律使用 design「页面自动化定位约定」中的 `data-testid` 与 `data-expected-at`、`data-overdue`、`data-active` 属性（TD-033）；V1 `data-testid` 只新增，不改名、不删除。

## 依赖关系

- Phase 1（Setup）→ Phase 2（Foundational）→ 各用户故事阶段。Phase 2 完成后各用户故事的测试任务可并行编写。
- Foundational 关键链：T003、T004 → T008（`V2__account_active.sql`）→ T010（`AppUser.active`）；T005 → T009（`DatabaseAuthModeCheck`）；T006（Playwright 项目划分）与 T007（e2e 数据与页面辅助）是全部 V2 e2e 的前置。
- 用户故事实现链：US-001（T014 → T015 → T016）→ US-002（T019 → T020，依赖 T016）；US-003（T032 → T033、T034 → T035 → T036；T032 → T037）→ US-004（T040，依赖 T037）；US-005（T044 → T045，T044 依赖 US-001 的 T014 `ExpectedResolution`）；US-006（T046 → T047、T048；T049 → T050、T051 → T052 → T053）。
- 跨故事功能测试：以下 e2e 在测试先行阶段编写，但其步骤依赖其他故事的实现，只能在所列前置实现任务全部完成后整体通过，并在前置任务完成后重新执行，作为所属故事的完成证据；在此之前以该故事的 `*Test` 与 `*IT` 作为阶段内证据，不得以跳过或删除步骤代替：
  - `v2-iqa-001`（T013，US-001）→ 需要 T016；步骤中的认领 / 分派使用 V1 能力。
  - `v2-iqa-002`（T018，US-002）→ 需要 T016、T020。
  - `v2-iqa-004`（T029，US-003）→ 需要 T036、T037 与 T040（按处理人筛选、周报表处理量）。
  - `v2-iqa-005`（T039，US-004）→ 需要 T036（停用 / 启用入口）。
  - `v2-iqa-008`（T043，US-005）→ 需要 T045。
  - `v2-iqa-009`、`v2-iqa-010`（T050、T051，US-006）→ 需要 T008、T016、T020、T036、T045（核对期望解决时间、超时标识、账号状态、报表第五项与停用后不能登录）。
- Phase 9（Polish）：T054 依赖全部生产实现任务；T055 依赖全部任务。

## Phase 1：Setup

- [ ] T001 编写本交付的 Candidate 部署与验证契约：`containerPort` 8080、`healthPath` `/actuator/health`、`candidateIdentityPath` `/api/candidate-identity`、`requiresPrivateNetwork` false；`developerSetupCommands` 为 `mvn -B -ntp -q dependency:go-offline`、`cd e2e && npm ci`；`developerValidationCommands` 为 `staticAnalysis`=`mvn -B -ntp checkstyle:check`、`build`=`mvn -B -ntp -DskipTests package`、`developerTests`=`mvn -B -ntp test`、`integrationTests`=`mvn -B -ntp verify -Pintegration -DskipUnitTests=true`、`functionalTests`=`bash .ai-delivery/features/002-v1/runtime/developer-functional-run.sh`；`developerFunctionalTestScript` 指向同一脚本（`.ai-delivery/features/002-v1/runtime/deployment.json`；来源：design「部署拓扑、迁移、Rollout 与 Rollback」、平台运行环境画像「候选验证」；依赖：无）
- [ ] T002 [P] 以 V1 脚本为基础编写开发者功能验证脚本的主体：校验 `AIDH_DEP_TICKET_DB_*`；检查 8080/8081/8082 未占用；生成运行前缀、种子管理员随机密码、升级演练账号随机密码与 32 字符合成设置口令（只在进程环境，不输出）；派生 `e2e_setup_<suffix>` 与 `e2e_upgrade_<suffix>`；构建 jar；启动 8080（`public`）与 8081（`e2e_setup_<suffix>` + 合成口令），health UP 后运行 `chromium` 与 `initial-admin` projects；无论成败运行 `cleanup`、`initial-admin-cleanup` 并停止全部实例，以首个失败的退出码退出；兼容 `bash -euo pipefail` 与 stdin 为 `/dev/null`（升级演练步骤由 T053 补入）（`.ai-delivery/features/002-v1/runtime/developer-functional-run.sh`；来源：design「快速验证指南」步骤 1～3、5，「验证环境责任矩阵」功能测试行；依赖：无）

## Phase 2：Foundational

### 测试

- [ ] T003 [P] 扩展迁移静态策略测试：所有迁移文件不得包含 `DROP`、`TRUNCATE`、`DELETE`、`UPDATE`、`INSERT`、`ALTER … TYPE`、`RENAME`，文件名匹配 `V\d+__[A-Za-z0-9_]+\.sql`；断言 `V1__init.sql` 内容未被修改（与 V1 已提交内容的校验和一致）（`src/test/java/com/ithelpdesk/MigrationPolicyTest.java`；来源：TD-029、FR-023、NFR-001、INV-006；依赖：无）
- [ ] T004 [P] 扩展迁移集成测试（真实 `dev` 实例）：`flyway_schema_history` 当前版本为 2 且版本 1、2 均成功；`app_user.active` 存在、`BOOLEAN NOT NULL DEFAULT TRUE`；再次 migrate 执行 0 个迁移；V1 其余表结构不变（`src/test/java/com/ithelpdesk/MigrationIT.java`；来源：TD-022、TD-029、FR-021、FR-023、AC-015；依赖：无）
- [ ] T005 [P] 编写认证方式启动校验的单元测试：`AIDH_DEP_TICKET_DB_AUTH_MODE` 为空 / 缺失 / `platform-identity` 时通过；为 `credential` 或其他值时抛出启动失败并输出“ticket-db 认证方式 <值> 未在本版本实现”，且不读取 `AIDH_DEP_TICKET_DB_PASSWORD`；日志不含任何凭据（`src/test/java/com/ithelpdesk/config/DatabaseAuthModeCheckTest.java`；来源：TD-031、平台运行环境画像「认证方式与机密」；依赖：无）
- [ ] T006 [P] 调整 Playwright 项目划分：`chromium` project 运行 V1 回归用例与 `tests/v2-iqa-00{1,2,3,4,5,7,8}-*.spec.ts`，忽略 `iqa-016-initial-admin.spec.ts`、`v2-iqa-006-last-admin.spec.ts` 与 `tests/upgrade/**`；`initial-admin` project 按文件顺序先运行 `iqa-016-initial-admin.spec.ts` 再运行 `v2-iqa-006-last-admin.spec.ts`（`fullyParallel` 关闭、单 worker），teardown 仍为 `initial-admin-cleanup`（升级演练 projects 由 T052 补入）（`e2e/playwright.config.ts`；来源：design「Component 边界与项目结构」e2e、「验证环境责任矩阵」功能测试行；依赖：无）
- [ ] T007 [P] 扩展 e2e 数据与页面辅助：`e2e/support/db.ts` 新增按运行前缀插入回溯工单（只写 `submitted_at`、`urgency`、`status`、`first_resolved_at`、`first_resolved_assignee_id` 与对应 `STATUS` 事件，P3 类“上周首次解决、本周再次解决”只追加事件不改 `first_resolved_at`）、按 `submitted_at + 4/24/72 小时` 独立计算期望解决时间、按 TD-028 口径独立计算报表第五项的工具，清理不匹配 `q15-72f2b9041e74-` 前缀；`e2e/support/fixtures.ts` 新增经账号管理页面停用 / 启用账号、读取 `user-row-status` 与 `data-active`、读取 `row-expected` / `ticket-expected` 的 `data-expected-at` 与 `data-overdue` 的辅助函数（`e2e/support/db.ts`、`e2e/support/fixtures.ts`；来源：design「数据库数据契约」V2 补充、「页面自动化定位约定」、TD-033；依赖：无）

### 实现

- [ ] T008 新增迁移，只含一条语句 `ALTER TABLE app_user ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT TRUE;` 与说明注释，不含任何数据写入，不修改 `V1__init.sql`（`src/main/resources/db/migration/V2__account_active.sql`；来源：TD-022、TD-029、FR-008、FR-019、FR-021、FR-023、NFR-001；依赖：T003、T004）
- [ ] T009 实现认证方式启动校验 `DatabaseAuthModeCheck`（`EnvironmentPostProcessor` 或 `ApplicationContextInitializer`，在数据源创建前执行），并在 `META-INF/spring.factories` 中注册；失败时以 ERROR 日志与非零退出终止启动（`src/main/java/com/ithelpdesk/config/DatabaseAuthModeCheck.java`、`src/main/resources/META-INF/spring.factories`；来源：TD-031；依赖：T005）
- [ ] T010 `AppUser` 记录增加 `boolean active`；`UserRepository` 的列清单 `COLUMNS` 与行映射增加 `active`，`insert` 不写 `active` 列（依赖默认值）；`UserAdminService` 不触碰 `active`（`src/main/java/com/ithelpdesk/user/AppUser.java`、`src/main/java/com/ithelpdesk/user/UserRepository.java`；来源：TD-022、FR-008、design「领域与数据模型」「Component 边界与项目结构」组件边界规则；依赖：T004、T008）

## Phase 3：US-001 - 查看工单期望解决时间

### 测试

- [ ] T011 [P] [US1] 编写期望解决时间单元测试：`Urgency.resolutionHours()` 为 HIGH=4、MEDIUM=24、LOW=72，`resolutionDuration()` 一致；跨夜、跨周末、跨月提交时期望值为自然时间相加、不顺延；`ExpectedResolution.sql(alias)` 生成的 SQL 与枚举时长逐项一致且别名正确（`src/test/java/com/ithelpdesk/ticket/ExpectedResolutionTest.java`；来源：TD-020、FR-001、INV-001、AC-001、BQ-001 人工答案 A；依赖：T010）
- [ ] T012 [P] [US1] 编写期望解决时间集成测试（真实 `dev` 实例，`it-<runId>-` 前缀）：三种紧急程度工单在员工“我的工单”、IT 同事全部列表、关键词搜索、按状态筛选与详情查询中返回的 `expectedResolveAt` = `submitted_at + 4/24/72 小时`；认领、分派、状态修改（含已解决 / 已关闭）、回复、补充说明、类别改名与移除前后期望值不变；员工仍只能读取本人工单（`src/test/java/com/ithelpdesk/ticket/TicketExpectedTimeIT.java`；来源：FR-001、FR-002、FR-003、INV-001、AC-001、AC-002、TD-020；依赖：T010）
- [ ] T013 [P] [US1] 编写 IQA-001 开发者功能测试：员工以高 / 中 / 低各提交一张工单并记录提交时间；员工与 IT 同事在列表、搜索 / 筛选结果与详情中核对 `data-expected-at` = 提交时间 + 4/24/72 小时、显示文本精确到日期与时刻；经认领、分派、处理中、已解决、已关闭、回复、补充说明后期望值不变且已解决 / 已关闭仍显示；页面不存在设置或修改期望解决时间的输入（`e2e/tests/v2-iqa-001-expected-time.spec.ts`；来源：IQA-001、FR-001～FR-003、AC-001、AC-002；依赖：T006、T007）

### 实现

- [ ] T014 [US1] `Urgency` 新增 `resolutionHours()` 与 `resolutionDuration()`；新增 `ExpectedResolution.sql(String alias)`，依据枚举生成 `(<a>.submitted_at + CASE <a>.urgency WHEN 'HIGH' THEN INTERVAL '4 hours' … END)`，是全部 SQL 获得时长表达式的唯一来源（`src/main/java/com/ithelpdesk/ticket/Urgency.java`、`src/main/java/com/ithelpdesk/ticket/ExpectedResolution.java`；来源：TD-020、FR-001、BQ-001 人工答案 A；依赖：T011）
- [ ] T015 [US1] `TicketRepository` 的列表 / 搜索 / 筛选 / 详情 `SELECT` 以别名 `expected_resolve_at` 选出 `ExpectedResolution.sql` 表达式；`Ticket`、`TicketDetail`、`TicketPage` 的读取模型增加 `Instant expectedResolveAt`；可见范围、查询条件与分页不变（`src/main/java/com/ithelpdesk/ticket/TicketRepository.java`、`src/main/java/com/ithelpdesk/ticket/Ticket.java`、`src/main/java/com/ithelpdesk/ticket/TicketDetail.java`、`src/main/java/com/ithelpdesk/ticket/TicketPage.java`；来源：TD-020、FR-002、FR-003、FR-020、design「数据流」流程 1～2；依赖：T012、T014）
- [ ] T016 [US1] `TicketController` 在每个请求开始时取一次 `clock.instant()` 作为本页 `now` 并传入视图；列表新增“期望解决时间”列（`row-expected`，属性 `data-expected-at` 为 ISO-8601 UTC 瞬时，文本按公司时区 `yyyy-MM-dd HH:mm`），详情新增“期望解决时间”项（`ticket-expected`）；对任何状态都显示，不提供任何输入入口（`src/main/java/com/ithelpdesk/ticket/TicketController.java`、`src/main/java/com/ithelpdesk/web/TimeFormat.java`、`src/main/resources/templates/tickets/list.html`、`src/main/resources/templates/tickets/detail.html`；来源：FR-003、AC-001、AC-002、TD-021、TD-033、design「统一行为契约（V2 补充）」；依赖：T013、T015）

## Phase 4：US-002 - 识别超时未解决的工单

### 测试

- [ ] T017 [P] [US2] 编写超时判定单元测试：`Overdue.isOverdue` 对四种状态 × 早于 / 恰好等于 / 晚于期望解决时间的组合，只有待处理 / 处理中且严格晚于时为 true；状态改回未解决后按当时时刻判定（`src/test/java/com/ithelpdesk/ticket/OverdueTest.java`；来源：TD-021、FR-004、FR-006、INV-002、AC-003、AC-004；依赖：T010）
- [ ] T018 [P] [US2] 编写 IQA-002 开发者功能测试：按数据契约插入 A（高、5 小时前、待处理）、B（中、25 小时前、处理中）、C（低、10 小时前、待处理）、D（高、6 小时前、已解决）、E（中、30 小时前、已关闭）；员工与 IT 同事的列表与详情中只有 A、B 有 `row-overdue` / `ticket-overdue`（文本“已超时”）且 `data-overdue="true"`；IT 同事把 A 改为已解决、D 改回待处理后再次查看，A 标识消失、D 出现标识（`e2e/tests/v2-iqa-002-overdue.spec.ts`；来源：IQA-002、FR-004～FR-006、AC-003、AC-004；依赖：T006、T007）

### 实现

- [ ] T019 [US2] 新增 `Overdue.isOverdue(TicketStatus status, Instant expectedAt, Instant now)` = `status ∈ {OPEN, IN_PROGRESS} && now.isAfter(expectedAt)`；不在 SQL 中使用 `now()`（`src/main/java/com/ithelpdesk/ticket/Overdue.java`；来源：TD-021、FR-004、INV-002；依赖：T017）
- [ ] T020 [US2] 列表行与详情视图模型增加 `boolean overdue`，由控制器用本请求同一 `now` 调用 `Overdue` 计算；模板只渲染超时元素，不写时间比较：`ticket-row` 与详情根容器新增 `data-overdue="true|false"`，超时时渲染 `row-overdue` / `ticket-overdue`（文本“已超时”，样式类 `overdue`）；`app.css` 新增 `.overdue` 红底白字粗体标签样式（`src/main/java/com/ithelpdesk/ticket/TicketController.java`、`src/main/java/com/ithelpdesk/ticket/TicketPage.java`、`src/main/java/com/ithelpdesk/ticket/TicketDetail.java`、`src/main/resources/templates/tickets/list.html`、`src/main/resources/templates/tickets/detail.html`、`src/main/resources/static/css/app.css`；来源：FR-005、FR-006、AC-003、AC-004、TD-021、TD-033、design「统一行为契约（V2 补充）」；依赖：T016、T018、T019）

## Phase 5：US-003 - 管理员停用和重新启用账号

### 测试

- [ ] T021 [P] [US3] 编写 `AccountStatusService` 单元测试：非管理员操作者被拒；目标不存在 →“账号不存在”；目标已是期望状态 → 返回“账号已是停用状态”/“账号已是启用状态”且不写库；停用最后一个可用管理员（含停用自己）→“系统必须至少保留一个可用的管理员”且不写库；NULL 哈希 ADMIN 行与已停用 ADMIN 不计入可用管理员；存在其他可用管理员时停用自己成功；成功路径的日志只含目标与操作者用户 id（`src/test/java/com/ithelpdesk/user/AccountStatusServiceTest.java`；来源：TD-025、FR-007、FR-014、FR-015、INV-004、AC-009；依赖：T010）
- [ ] T022 [P] [US3] 编写停用 / 启用集成测试（真实 `dev` 实例）：停用与启用只改变 `active`，`login_name`、`password_hash`、`display_name`、`role` 与全部工单、沟通、处理记录不变，不写 `ticket_event`；新账号默认启用；使用已停用账号的登录名创建账号按 V1 登录名唯一规则被拒；在一次性 schema `it_admin_<runId>` 中只有一个可用管理员时停用被拒，两个管理员并发互相停用只成功一个、始终至少保留一个可用管理员，结束时 `DROP SCHEMA … CASCADE`（`src/test/java/com/ithelpdesk/user/AccountStatusIT.java`；来源：TD-022、TD-025、FR-007、FR-008、FR-014、FR-016、INV-003、INV-004、INV-005、AC-005、AC-009；依赖：T010）
- [ ] T023 [P] [US3] 扩展认证单元测试：`active=false` 的账号 `CurrentUser.isEnabled()` 为 false，`AppUserDetailsService` 返回禁用用户；NULL 哈希行行为保持 V1（`src/test/java/com/ithelpdesk/auth/AppUserDetailsServiceTest.java`；来源：TD-023、FR-009；依赖：T010）
- [ ] T024 [P] [US3] 扩展每请求身份刷新过滤器测试：账号不存在或 `active=false` 时，对 GET 与 POST 均清空 SecurityContext、使 HttpSession 失效并 302 `/login`，且不进入后续处理；启用账号的身份刷新行为不变（`src/test/java/com/ithelpdesk/auth/RoleRefreshFilterTest.java`；来源：TD-024、FR-009、FR-014、AC-006；依赖：T010）
- [ ] T025 [P] [US3] 编写账号管理 Web 切片测试：员工与 IT 同事请求 `POST /admin/users/{id}/deactivate|activate` 得 403 且服务未被调用；缺少 `_csrf` 被拒；非正整数 `{id}` 得 400；成功与“已是该状态”为 302 `/admin/users` + flash，校验失败为 302 + flashError；管理员停用自己成功时 200 直接渲染 `admin/users`；账号列表每行渲染 `user-row-status`（“启用”/“停用”，`data-active`）及对应的 `user-deactivate` 或 `user-activate` 按钮；页面不回显凭据（`src/test/java/com/ithelpdesk/user/UserAdminControllerTest.java`；来源：TD-027、FR-007、FR-008、FR-015、AC-005、AC-010、design「HTTP 路由」；依赖：T010）
- [ ] T026 [P] [US3] 编写停用账号登录集成测试（真实 `dev` 实例，localhost 随机端口 + MockMvc 或 HTTP 客户端）：停用后正确凭据与错误凭据登录都得到同一 `/login?error` 与“登录名或密码错误”；停用前已建立的会话下一次请求被 302 `/login` 且会话失效；重新启用后原凭据登录成功并按原身份访问（`src/test/java/com/ithelpdesk/auth/DeactivatedLoginIT.java`；来源：TD-023、TD-024、FR-009、FR-010、AC-006；依赖：T010）
- [ ] T027 [P] [US3] 扩展分派集成测试：分派候选只含启用状态的其他 IT 同事（按姓名、id 排序）；向已停用的 IT 同事分派被拒并提示“该 IT 同事已停用，不能分派，请刷新后重新选择”，工单与 `ticket_event` 不变；非 IT 同事或本人沿用 V1 提示；停用与分派并发时按提交顺序生效（分派先提交则工单已分派且随后停用不改工单）；重新启用后重新成为候选（`src/test/java/com/ithelpdesk/ticket/TicketAssignmentIT.java`；来源：TD-026、FR-011、FR-012、AC-007；依赖：T010）
- [ ] T028 [P] [US3] 编写 IQA-003 开发者功能测试：管理员创建员工 E2 → 列表显示“启用”→ E2 在独立浏览器上下文中保持登录 → 管理员停用 E2，列表显示“停用”且姓名、身份不变 → E2 的下一次操作被引导至登录入口 → E2 正确凭据与错误凭据登录提示一致且未进入系统 → 以 E2 登录名创建新账号被拒 → 重新启用 E2，E2 用原凭据登录并提交、查看工单；证据中不出现明文凭据（`e2e/tests/v2-iqa-003-deactivate-activate.spec.ts`；来源：IQA-003、FR-007～FR-010、FR-016、AC-005、AC-006；依赖：T006、T007）
- [ ] T029 [P] [US3] 编写 IQA-004 开发者功能测试：创建 IT 同事 T1、T2、T3 与员工 E1，E1 提交 W1、W2，T1 认领并回复 W1、改为处理中，认领 W2 并改为已解决；T2 打开 W1 分派下拉不刷新 → 管理员停用 T1 → T2 提交分派给 T1 被拒、W1 不变 → 刷新后下拉不含 T1 → E1、T2 看到 W1、W2 处理人仍为 T1、T1 回复与处理记录照常、按处理人 T1 筛选能筛出 W1、W2 → 周报表 T1 处理量计入 W2 → T3 认领 W1，处理记录显示前值 T1、后值 T3 → 重新启用 T1 后下拉重新出现 T1（`e2e/tests/v2-iqa-004-assign-deactivated.spec.ts`；来源：IQA-004、FR-011、FR-012、FR-013、AC-007、AC-008、BQ-003 人工答案 A；依赖：T006、T007）
- [ ] T030 [P] [US3] 编写 IQA-006 开发者功能测试（`initial-admin` project，localhost:8081 一次性 schema，在 V1 IQA-016 设置出唯一管理员 A1 后执行）：A1 停用自己被拒并提示“系统必须至少保留一个可用的管理员”、仍为启用 → A1 创建管理员 A2 → A1 停用自己成功且列表显示“停用” → A1 下一次操作被引导至登录入口、无法再登录 → A2 登录后停用自己被拒、仍为启用 → A2 重新启用 A1，A1 可再次登录（`e2e/tests/v2-iqa-006-last-admin.spec.ts`；来源：IQA-006、FR-014、FR-009、AC-009、人工审批意见 HT-DLV-20261117-88E4DAE97F6A-0002；依赖：T006、T007）
- [ ] T031 [P] [US3] 编写 IQA-007 开发者功能测试：准备启用的员工 E4 与已停用的员工 E5；员工 E1 与 IT 同事 T1 页面上不存在停用 / 启用入口；二者直接以带 CSRF 的请求提交停用 E4、启用 E5 均被拒（403）；管理员查看 E4 仍为启用、E5 仍为停用（`e2e/tests/v2-iqa-007-access-control.spec.ts`；来源：IQA-007、FR-015、AC-010；依赖：T006、T007）

### 实现

- [ ] T032 [US3] `UserRepository` 新增 `acquireAccountStatusLock()`（`pg_advisory_xact_lock(7243012)`）、`lockForStatusChange(id)`（`FOR UPDATE`）、`countOtherUsableAdmins(id)`（`role='ADMIN' AND active AND password_hash IS NOT NULL`，排除目标）、`updateActive(id, newValue, oldValue)`（`WHERE id=:id AND active=:old`）、`findAssignableItStaff()`（`role='IT_STAFF' AND active`，按姓名、id 排序）、`findAssigneeCandidateForShare(id)`（`SELECT role, active … FOR SHARE`）；全部参数化（`src/main/java/com/ithelpdesk/user/UserRepository.java`；来源：TD-022、TD-025、TD-026、design「派生值与不变量映射」；依赖：T022、T027）
- [ ] T033 [US3] 新增 `AccountStatusService`：单个事务内依次 advisory lock → 目标行 `FOR UPDATE` → 不存在 / 已是期望状态处理 → 停用可用管理员时计数其余可用管理员、为 0 则拒绝 → `updateActive` → 提交；服务层 `requireAdmin`；INFO 日志“账号已停用 / 已启用（用户 id=<目标>，操作者 id=<操作者>）”，不记录登录名、姓名或凭据；不写 `ticket_event`、不改任何工单（`src/main/java/com/ithelpdesk/user/AccountStatusService.java`；来源：TD-025、FR-007、FR-012、FR-014、FR-015、INV-004、INV-005、A-003；依赖：T021、T032）
- [ ] T034 [US3] `CurrentUser` 增加 `active`，`isEnabled()` = `passwordHash != null && active`；`AppUserDetailsService` 查询包含 `active`；登录失败沿用 `failureUrl("/login?error")` 与 V1 相同提示，不新增区分性提示或参数（`src/main/java/com/ithelpdesk/auth/CurrentUser.java`、`src/main/java/com/ithelpdesk/auth/AppUserDetailsService.java`；来源：TD-023、FR-009、FR-010、AC-006；依赖：T023、T026）
- [ ] T035 [US3] `RoleRefreshFilter` 在账号不存在或 `active=false` 时清空 SecurityContext、使 HttpSession 失效并 302 `/login`（GET 与 POST 一致，在授权与业务处理之前）；启用账号的身份刷新不变（`src/main/java/com/ithelpdesk/auth/RoleRefreshFilter.java`；来源：TD-024、FR-009、FR-014、AC-006、AC-009；依赖：T024、T034）
- [ ] T036 [US3] `UserAdminController` 新增 `POST /admin/users/{id}/deactivate` 与 `POST /admin/users/{id}/activate`（`{id}` 校验为正整数，调用 `AccountStatusService`；成功 / 已是该状态 PRG + flash，校验失败 PRG + flashError，管理员成功停用自己时 200 直接渲染并 flash“已停用该账号”）；`admin/users.html` 新增“状态”列（`user-row-status`、`data-active`）与每行 `user-deactivate` / `user-activate` 按钮（带 `_csrf`）（`src/main/java/com/ithelpdesk/user/UserAdminController.java`、`src/main/resources/templates/admin/users.html`；来源：TD-027、TD-033、FR-007、FR-008、FR-014、FR-015、AC-005、AC-009、AC-010、design「HTTP 路由」；依赖：T025、T028、T030、T031、T033、T035）
- [ ] T037 [US3] 分派改造：`TicketQueryService` 详情页分派候选改用 `findAssignableItStaff()`（排除本人）；`TicketCommandService.assign` 在工单行锁之后以 `findAssigneeCandidateForShare` 校验目标，非 IT 同事或本人沿用 V1 提示，`active=false` 提示“该 IT 同事已停用，不能分派，请刷新后重新选择”，均回滚且工单不变，并记录 INFO“分派被拒绝：目标账号已停用（工单 id=<id>，目标 id=<id>）”；认领与筛选“处理人”选项不按 `active` 过滤（`src/main/java/com/ithelpdesk/ticket/TicketQueryService.java`、`src/main/java/com/ithelpdesk/ticket/TicketCommandService.java`；来源：TD-026、FR-011、FR-012、AC-007、design「数据流」流程 5；依赖：T027、T029、T032）

## Phase 6：US-004 - 停用账号的历史记录照常保留

### 测试

- [ ] T038 [P] [US4] 在停用 / 启用集成测试中补充历史保留断言：停用提交过工单并写过补充说明的员工、以及处理过并回复过工单的 IT 同事后，列表、关键词搜索、按处理人筛选（处理人为已停用账号的工单仍被筛出）、详情中的提交人 / 处理人 / 作者姓名与身份照常；筛选“处理人”选项仍包含已停用同事；周报表“每位 IT 同事处理量”照常计入；停用 IT 同事名下未解决工单不被改动（`src/test/java/com/ithelpdesk/user/AccountStatusIT.java`；来源：FR-012、FR-013、AC-008、TD-026、BQ-003 人工答案 A；依赖：T022）
- [ ] T039 [P] [US4] 编写 IQA-005 开发者功能测试：员工 E3 提交 W3 并补充说明，IT 同事 T1 回复并认领 → 管理员停用 E3 → T1 在全部列表、按 W3 标题片段搜索与详情中看到 W3、提交人 E3 姓名与补充说明照常，回复并修改状态成功且产生处理记录 → 管理员重新启用 E3，E3 登录后看到 W3 及停用期间的回复与状态变化（`e2e/tests/v2-iqa-005-deactivated-history.spec.ts`；来源：IQA-005、FR-013、AC-008；依赖：T006、T007）

### 实现

- [ ] T040 [US4] 核对并保持历史显示查询与启用状态无关：工单列表 / 搜索 / 详情中的姓名关联、筛选“处理人”选项（V1：IT 同事 ∪ 曾任处理人）、认领与报表人员行均不加 `active` 条件；如 T037 改动波及这些查询则恢复为 V1 语义，使 T038、T039 通过（`src/main/java/com/ithelpdesk/ticket/TicketQueryService.java`、`src/main/java/com/ithelpdesk/user/UserRepository.java`；来源：FR-012、FR-013、AC-008、TD-026、A-004；依赖：T037、T038、T039）

## Phase 7：US-005 - 周报表查看本周超时解决数

### 测试

- [ ] T041 [P] [US5] 扩展周报表单元测试：`WeeklyReport` 含 `overdueResolved`，无可计入工单时为 0 并在视图中显示 `0`；V1 四项字段与格式不变（`src/test/java/com/ithelpdesk/report/WeeklyReportTest.java`；来源：TD-028、FR-017、FR-018、AC-011、AC-012；依赖：T010）
- [ ] T042 [P] [US5] 扩展周报表集成测试（真实 `dev` 实例，可变测试时钟设到随机历史周）：第五项只计首次已解决时刻在本周且严格晚于期望解决时间的工单；恰好等于、上周首次解决本周再次解决、只关闭从未解决、首次解决后改回未解决（仍计入）、无数据为 0 各自正确；V1 四项结果与改动前一致（`src/test/java/com/ithelpdesk/report/WeeklyReportIT.java`；来源：TD-028、FR-017、FR-018、FR-022、AC-011、AC-012、BQ-002 人工答案 A；依赖：T010）
- [ ] T043 [P] [US5] 编写 IQA-008 开发者功能测试：按数据契约插入 P1～P6（P3 首次解决在上周、本周再次解决只追加事件），IT 同事把 P5 改为已关闭、P6 标记为已解决；管理员打开周报表，`report-overdue-resolved` 等于以独立 SQL 按 FR-017 口径在同一时刻计算的值（含 P1、P4、P6，不含 P2、P3、P5）；V1 四项等于按 V1 口径独立计算的期望值；员工与 IT 同事访问报表被拒（`e2e/tests/v2-iqa-008-overdue-resolved-report.spec.ts`；来源：IQA-008、FR-017、FR-018、AC-011、AC-012；依赖：T006、T007）

### 实现

- [ ] T044 [US5] `WeeklyReport` 增加 `long overdueResolved`；`WeeklyReportRepository.overdueResolvedCount(WeekRange)` = `COUNT(*) WHERE first_resolved_at >= :weekStart AND first_resolved_at < :weekEnd AND first_resolved_at > <ExpectedResolution.sql("t")>`；`WeeklyReportService` 组装第五项；V1 四项 SQL 与行集合不改（`src/main/java/com/ithelpdesk/report/WeeklyReport.java`、`src/main/java/com/ithelpdesk/report/WeeklyReportRepository.java`、`src/main/java/com/ithelpdesk/report/WeeklyReportService.java`；来源：TD-028、FR-017、FR-018、FR-020；依赖：T014、T041、T042）
- [ ] T045 [US5] 周报表页面在 V1 四项之后新增一节“本周超时解决的工单数量”（`report-overdue-resolved`，0 时显示 `0`）；报表访问权限不变（`src/main/resources/templates/admin/report.html`；来源：TD-028、TD-033、FR-017、FR-018、AC-011、AC-012；依赖：T043、T044）

## Phase 8：US-006 - 从 V1 平滑升级

### 测试

- [ ] T046 [US6] 编写升级测试共用工具 `UpgradeFixtures`：在一次性 schema 上以 Flyway `target=1` 应用已提交的 `V1__init.sql`；按 V1 数据契约以 SQL 写入覆盖 IQA-009 原前置条件的 V1 合成数据集（员工 / IT 同事 / 管理员各至少一个且带随机密码的 BCrypt 哈希、被取消 IT 同事身份的账号、有效 / 已移除 / 改名类别、三种紧急程度 × 四种状态 × 有无处理人、已超期与未超期未解决工单、含补充说明 / 回复 / 认领 / 分派 / 状态事件的工单、本周首次已解决工单）；对 V1 全部表全部 V1 列按主键排序生成快照并比对，差异输出只含 id 与列名（`src/test/java/com/ithelpdesk/upgrade/UpgradeFixtures.java`；来源：TD-030、FR-019、NFR-001、IQA-009、spec 测试策略「开发者验证」；依赖：T008）
- [ ] T047 [P] [US6] 编写升级演练集成测试（真实 `dev` 实例，一次性 schema `it_upgrade_<runId>`）：`target=1` → 写入 V1 数据集 → 快照 → migrate → 快照逐行逐列相等、`active` 全为 TRUE、history 有且仅有版本 1、2 且均成功 → 每张既有工单 `expected_resolve_at` = 提交时间 + 4/24/72 小时 → 再次 migrate 执行 0 个且快照仍相等 → `DROP SCHEMA … CASCADE`（`src/test/java/com/ithelpdesk/upgrade/UpgradeRehearsalIT.java`；来源：TD-029、TD-030、FR-019、FR-020、FR-021、FR-023、NFR-001、AC-013、AC-014、AC-015；依赖：T046）
- [ ] T048 [P] [US6] 编写升级中断集成测试（真实 `dev` 实例，一次性 schema）：V1 数据集就绪后测试连接对 `app_user` 持 `ACCESS EXCLUSIVE` 锁使 V2 迁移阻塞，以 `pg_terminate_backend` 终止迁移连接（无权时改为关闭迁移所用 DataSource 的物理连接）→ 断言迁移失败、history 最高版本仍为 1、`active` 列不存在、快照不变 → 释放锁重跑 migrate → 结构与全部数据与另一 schema 中一次成功升级的结果相同 → 删除两个 schema（`src/test/java/com/ithelpdesk/upgrade/UpgradeInterruptionIT.java`；来源：TD-029、TD-030、FR-023、NFR-001、AC-016；依赖：T046）
- [ ] T049 [P] [US6] 编写 e2e 升级演练支持：`upgrade-seed.ts` 向 `e2e_upgrade_<suffix>` 以 SQL 写入与 T046 覆盖面相同的 V1 数据集（账号密码取自进程环境中的随机值），并把 V1 全列快照与按 V1 口径计算的报表四项期望值保存到临时目录；`upgrade-snapshot.ts` 提供快照采集与逐行比对（差异只输出 id 与列名）；`upgrade.teardown.ts` 执行 `DROP SCHEMA e2e_upgrade_… CASCADE`（`e2e/support/upgrade-seed.ts`、`e2e/support/upgrade-snapshot.ts`、`e2e/support/upgrade.teardown.ts`；来源：TD-030、design「验证环境责任矩阵」升级演练行、IQA-009；依赖：T007）
- [ ] T050 [US6] 编写 IQA-009 开发者升级核对（`upgrade-verify` project，localhost:8082）：各身份用原凭据登录；账号列表全部“启用”；各身份可见的工单列表与详情（含沟通与处理记录）与快照一致、员工访问他人工单 403；每张既有工单 `data-expected-at` = 提交时间 + 4/24/72 小时，超时标识只出现在超期未解决工单；报表四项等于期望值且第五项符合 FR-017；`/setup` 不可用；SQL 快照 V1 列逐行相等，history 版本 2 只成功一次（`e2e/tests/upgrade/v2-iqa-009-upgrade.spec.ts`；来源：IQA-009、FR-019～FR-022、NFR-001、AC-013、AC-014、TD-030、TD-032；依赖：T049）
- [ ] T051 [US6] 编写 IQA-010 开发者重复升级核对：`upgrade-verify` 末尾在 V2 中新增数据（新工单与回复、停用一个账号、把一张既有工单改为已解决）并保存快照；`upgrade-repeat` project 在重启后逐项比对：无重复行、期望解决时间不变、停用账号仍停用且不能登录、新增数据与 V1 数据完整、history 行数与内容不变（`e2e/tests/upgrade/v2-iqa-010-repeat-upgrade.spec.ts`；来源：IQA-010、FR-023、NFR-001、AC-015、TD-030；依赖：T049、T050）
- [ ] T052 [US6] 在 Playwright 配置中新增 `upgrade-seed`、`upgrade-verify`、`upgrade-repeat`、`upgrade-cleanup` projects（baseURL 为 localhost:8082，`tests/upgrade/**` 只在这些 projects 中运行，单 worker 顺序执行）（`e2e/playwright.config.ts`；来源：design「Component 边界与项目结构」e2e、TD-030；依赖：T006、T049、T050、T051）

### 实现

- [ ] T053 [US6] 在开发者功能验证脚本中补入升级演练：8082 以 `--app.db.schema=e2e_upgrade_<suffix> --spring.flyway.target=1 --server.port=8082` 启动至 UP 后停止 → `upgrade-seed` → 以默认配置启动同一 schema 至 UP 并断言日志含迁移到版本 2 → `upgrade-verify` → 停止并以相同参数重启至 UP 并断言日志含 up to date → `upgrade-repeat`；无论成败运行 `upgrade-cleanup` 删除 schema；`spring.flyway.target` 只以命令行参数传入，不写入 `application.yml`（`.ai-delivery/features/002-v1/runtime/developer-functional-run.sh`；来源：TD-030、design「快速验证指南」步骤 4～5、「配置」；依赖：T002、T008、T052）

## Phase 9：Polish

- [ ] T054 运行 V1 既有单元、集成与 e2e 回归测试，只在 V2 行为确有变化处调整断言（例如账号列表新增状态列与按钮、分派下拉只含启用的 IT 同事、`MigrationIT` 版本号），不删除或跳过 V1 场景，V1 `data-testid` 保持不变；`SecretUsagePolicyTest` 保留并继续通过（`src/test/java/com/ithelpdesk/`、`e2e/tests/`；来源：design「Component 边界与项目结构」e2e 说明、spec A-001、INV-007、TD-033；依赖：T009、T020、T036、T037、T040、T045）
- [ ] T055 在 Agent Pod 中按 `deployment.json` 顺序完整执行 `developerSetupCommands` 与 `staticAnalysis` → `build` → `developerTests` → `integrationTests` → `functionalTests`，并对 `developer-functional-run.sh` 执行 `bash -n`；修复失败项后，在实施证据账本追加本 Session 段落：各命令结果摘要、使用真实 `ticket-db` `dev` 实例的验证清单（运行前缀、一次性 schema 的创建与删除记录、清理记录）、升级演练（含重复升级与中断重跑）的执行记录与快照比对结果、“使用模拟服务的验证”小节（本交付无）、单元测试替身的范围与原因、`src/main/**` 中唯一的 `AIDH_SECRET_*` 引用为 `AIDH_SECRET_INITIAL_SETUP_TOKEN` 的检查结果、以及延后的平台证据；不写入任何密码、令牌或客户内容（`.ai-delivery/features/002-v1/reports/implementation.md`；来源：宪章「测试与质量」「交付治理」、design「验证环境责任矩阵」、TD-030、TD-031；依赖：T001、T053、T054）

## 完成证据

各阶段在 Coding（Agent Pod）中可获得的证据：

| 阶段 | Coding 可获得的证据 |
|---|---|
| Phase 1 Setup | `deployment.json` 为合法 JSON 且命令与 design 一致；`bash -n .ai-delivery/features/002-v1/runtime/developer-functional-run.sh` 通过（辅助证据） |
| Phase 2 Foundational | `MigrationPolicyTest`、`DatabaseAuthModeCheckTest` 通过；`MigrationIT` 在 `dev` 实例上通过（版本 2、`active` 默认 TRUE、再次 migrate 0 个）；localhost 启动日志含 Flyway 迁移到版本 2 或 up to date，`/actuator/health` 为 UP（辅助证据） |
| US-001 | `ExpectedResolutionTest` 通过；`TicketExpectedTimeIT` 在 `dev` 实例上通过；`v2-iqa-001-expected-time.spec.ts` 在 localhost:8080 上通过 |
| US-002 | `OverdueTest` 通过；`v2-iqa-002-overdue.spec.ts` 在 localhost:8080 上通过（T016、T020 完成后） |
| US-003 | `AccountStatusServiceTest`、`AppUserDetailsServiceTest`、`RoleRefreshFilterTest`、`UserAdminControllerTest` 通过；`AccountStatusIT`（含一次性 schema `it_admin_<runId>` 已删除）、`DeactivatedLoginIT`、`TicketAssignmentIT` 在 `dev` 实例上通过；`v2-iqa-003`、`v2-iqa-007` 在 localhost:8080 上通过，`v2-iqa-006` 在 localhost:8081 一次性 schema 上紧随 V1 IQA-016 通过且 schema 已删除，`v2-iqa-004` 在 T040 完成后通过 |
| US-004 | `AccountStatusIT` 历史保留断言通过；`v2-iqa-004`、`v2-iqa-005` 在 localhost:8080 上通过 |
| US-005 | `WeeklyReportTest` 通过；`WeeklyReportIT` 在 `dev` 实例上通过；`v2-iqa-008-overdue-resolved-report.spec.ts` 在 localhost:8080 上通过 |
| US-006 | `UpgradeRehearsalIT`、`UpgradeInterruptionIT` 在 `dev` 实例一次性 schema 上通过且 schema 已删除；`developer-functional-run.sh` 中 localhost:8082 升级演练（`upgrade-seed` → 升级 → `upgrade-verify` → 重启 → `upgrade-repeat` → `upgrade-cleanup`）通过，日志含迁移到版本 2 与 up to date；该记录是 IQA-010 未执行重新部署时 Independent QA 引用的证据（TD-030、TD-034） |
| Polish | V1 回归测试通过；`reports/implementation.md` 记录完整验证序列结果、真实依赖使用清单、升级演练记录、“使用模拟服务的验证”（无）、机密引用检查与替身说明 |

延后的平台证据（不作为任务，由平台或下游阶段产生）：

- 候选验证：Runner 在不可变候选提交上执行 `deployment.json` 中的命令（含每个候选的升级演练）与 `developer-functional-run.sh` 语法检查。
- 镜像构建与候选部署：ACR Tasks 以根目录 `Dockerfile` 构建（UID 10001）；部署到 `cand-{deliveryShortId}`，`healthPath` 与 `candidateIdentityPath` 校验；候选首次启动即对 `test` 库（含 V1 IQA-015 持久基线）执行真实 V1→V2 升级。
- Independent QA：IQA-001～IQA-010 在已部署候选上的验收结果；IQA-009 以前缀 `q15-72f2b9041e74-` 的 V1 持久基线逐项核对，IQA-010 在平台受控重新部署能力可用时重新部署同一候选比对，否则只读核对并引用开发者升级演练记录（TD-034、人工答案 HT-DLV-20261117-88E4DAE97F6A-0005）。`qa-run.sh` 与 `qa-result.json` 由 Independent QA 编写与执行，Coding 不编写或预置。
- 机密：`INITIAL_SETUP_TOKEN` 与 `INITIAL_ADMIN_*` 由人在平台依赖与机密任务中提供；Coding 不使用、不预置。
- V1 线上数据的真实升级结果、UAT、人工审批、发布与合并。
