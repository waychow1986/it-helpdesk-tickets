# Coding 后 Convergence 报告

## 评审范围

- 模式：initial；nodeRunId `NR-DLV-20261116-72F2B9041E74-0084`。被评审提交：`fbee570`（Coding `NR-DLV-20261116-72F2B9041E74-0083-b01`）。
- 收到 upstream-change 反馈：自上一次 Convergence（`NR-DLV-20261116-72F2B9041E74-0037`，基线 `6d26d08`）以来，`spec.md` 新增 FR-031、AC-021、IQA-016、SC-007，并修订了 IQA-015 / AC-020 的口径（V1 不验证升级后的数据保留）。`design.md` 改写 TD-011，新增 TD-016～TD-019。`tasks-implementation.md` 改写了 T004、T009、T010、T014、T018、T021、T058、T059，并新增 T061～T071。`docs/dependencies.json` 新增 `secrets`。代码改动见下方可追溯表。本轮以这些改动为重点，同时检查它们对未改动部分（IQA-001～IQA-014 业务实现、就绪制品）的一致性影响。
- 评审依据（只读）：`intent.md`、`spec.md`、`design.md`、`technical-baseline.md`、`tasks-implementation.md`、`reports/implementation.md`（Session `0082-b01`、`0083-b01`）、`runtime/deployment.json`、`runtime/developer-functional-run.sh`、`Dockerfile`、`src/**`、`e2e/**`、`docs/dependencies.json`。Convergence 不执行命令，只核对记录、结果与测试代码是否相符。

## 结论

`通过`。没有 critical / high 阻塞问题。结果文件 `findings` 为空，`outcome` 为 `passed`。

## 阻塞问题

无。

## 制品到实现与测试的可追溯关系

| 来源 | 实现路径 | 测试/证据路径 | 结论 |
|---|---|---|---|
| FR-031 / AC-021 / SC-007 / IQA-016 / TD-011 / T063、T064、T065、T066、T067、T068 | `src/main/java/com/ithelpdesk/user/InitialAdminSetupService.java`（`isSetupOpen()` 每次实时查询“带凭据 ADMIN”；事务内 `pg_advisory_xact_lock(7243011)` → 再次判定 → 校验 → 唯一检查 → INSERT `ADMIN`、`created_by=NULL`）、`InitialAdminSetupController.java`、`SetupForm.java`、`SetupClosedException.java`、`UserRepository.java`（`acquireSetupLock`、`existsCredentialedAdmin`、`insertInitialAdmin`）、`templates/setup/form.html`、`templates/setup/closed.html`、`auth/LoginController.java`、`templates/login.html`、`config/SecurityConfig.java`（`/setup` 匿名，CSRF 保持开启） | `InitialAdminSetupServiceTest`、`InitialAdminSetupControllerTest`、`SecurityConfigTest`、`InitialAdminSetupIT`（8 例，真实 `dev` 实例一次性 schema）、`e2e/tests/iqa-016-initial-admin.spec.ts`（8081 一次性 schema 实例） | 一致：关闭时 GET/POST 均返回 403 + `setup-closed`，不读写账号也不校验口令；只能新建 ADMIN，不修改既有行；成功后 302 `/login?setup`，不自动登录；登录页只在开放时显示 `setup-link`；表单没有身份选择项和身份导航 |
| FR-031 设置口令 / AC-021 / TD-019 / T069、T070、T071 | `user/SetupTokenVerifier.java`（去首尾空白后长度 ≥ 16 才算已配置，只保存 SHA-256 摘要，用 `MessageDigest.isEqual` 比较，未配置时 WARN 一次）、`user/SetupAttemptLimiter.java`（按 `getRemoteAddr()` 统计，15 分钟内 5 次失败，最多保存 10000 个地址）、控制器判定顺序：关闭 → 403、限流 → 429、口令未配置 / 为空 / 错误 → 200 并计一次失败、口令通过后才进入写事务 | `SetupTokenVerifierTest`、`SetupAttemptLimiterTest`、`InitialAdminSetupControllerTest`、`InitialAdminSetupIT`（口令为空或错误时不创建；第 6 次即使口令正确也返回 429；未配置口令的上下文失败即关闭）、e2e（口令缺失、口令错误各一次被拒） | 一致：口令正确后的字段校验失败不计入失败次数；所有重渲染都清空密码与口令；日志不含口令、登录名、姓名或来源地址 |
| TD-016、TD-017 / T004、T014、T062 | `application.yml`（JDBC URL 端口读 `AIDH_DEP_TICKET_DB_PORT`，`currentSchema=${app.db.schema}`，Flyway `schemas` / `default-schema`，`app.db.schema: public`）、`config/AppProperties.java`（schema 正则校验，`toString` 不输出口令） | `AppPropertiesTest`、`InitialAdminSetupIT`（断言 `current_schema()`） | 一致：候选、QA、生产都使用 `public`；`AIDELIVERY_*` 只用于候选身份端点，不影响数据位置 |
| TD-018、TD-019 机密使用 / `docs/dependencies.json` / T062 | `application.yml` 中 `app.setup.token: ${AIDH_SECRET_INITIAL_SETUP_TOKEN:}`；`docs/dependencies.json` 声明 `INITIAL_ADMIN_*` 与 `INITIAL_SETUP_TOKEN`，均为 `test` 环境且 `required` | `SecretUsagePolicyTest`（`src/main` 中 `AIDH_SECRET_*` 只出现一次） | 一致：应用不读取 `AIDH_SECRET_INITIAL_ADMIN_*`；仓库检索确认 `src/main` 只有 `application.yml:59` 一处引用 |
| TD-011 删除启动引导 / T009、T010、T018 | 删除 `InitialAdminBootstrap.java`、`BootstrapProperties.java` 与 `app.bootstrap.*`；`AppUserDetailsService` 对 NULL 哈希返回禁用用户（未改动） | 删除 `InitialAdminBootstrapTest` / `InitialAdminBootstrapIT`；新增 `auth/LegacyAdminLoginIT`（2 例）；`AppUserDetailsServiceTest` | 一致 |
| FR-029、FR-030、NFR-001 / AC-020 / IQA-015（开发者侧辅助证据）/ T013、T057、T058 | `db/migration/V1__init.sql`、Flyway 配置；启动时不写入任何账号 | `MigrationPolicyTest`、`MigrationIT`、`DataRetentionIT`（重启前后 `app_user` 行数不变） | 一致：IQA-015 按修订后的 spec 不在 V1 验收范围 |
| FR-001～FR-004 / AC-001～AC-004 / IQA-001、IQA-009 / T023～T029 | `user/UserAdminService.java`、`auth/AppUserDetailsService.java`、`auth/RoleRefreshFilter.java`、`config/SecurityConfig.java`、对应模板 | `UserAdminServiceTest`、`SecurityConfigTest`、`UserAdminIT`、`UserRoleChangeIT`、`e2e/tests/iqa-001-*`、`iqa-009-*` | 一致（本轮未改动业务实现，回归通过） |
| FR-005～FR-009、PQ-001 / AC-005、AC-006 / IQA-002、IQA-008 / T030～T037、T060 | `category/`、`ticket/TicketForm.java`、`TicketCommandService.submit` | `CategoryIT`、`TicketSubmitIT`、`e2e/tests/iqa-002-*`、`iqa-008-*` | 一致（未改动） |
| FR-010～FR-021 / AC-007～AC-014 / IQA-003～IQA-007 / T038～T048 | `ticket/TicketQueryService.java`、`TicketCommandService.java`、`TicketRepository.java` | `TicketAccessIT`、`TicketAssignmentIT`、`TicketStatusIT`、`e2e/tests/iqa-003`～`iqa-007` | 一致（未改动） |
| FR-022～FR-024 / AC-015、AC-016 / IQA-011、IQA-012 / T049～T052 | `TicketSearchCriteria`、`TicketRepository.search` | `TicketSearchIT`、`e2e/tests/iqa-011-*`、`iqa-012-*` | 一致（未改动） |
| FR-025～FR-028 / AC-017～AC-019 / IQA-010、IQA-013、IQA-014 / T053～T056 | `report/` | `WeeklyReportIT`、`e2e/tests/iqa-010-*`、`iqa-013-*`、`iqa-014-*` | 一致（未改动） |
| 宪章「安全与隐私」日志隐私 / T012、T015 | `web/RequestLogFilter.java`、`GlobalExceptionHandler` | `RequestLogFilterTest`、`GlobalExceptionHandlerTest` | 一致：`SetupForm.toString()` 输出掩码；设置流程日志只记录用户 id |
| 平台就绪制品 / T005、T019、T021、T022、T061 | `Dockerfile`、`platform/CandidateIdentityController.java`、`runtime/deployment.json`、`runtime/developer-functional-run.sh`（双实例）、`e2e/playwright.config.ts`（`initial-admin`、`initial-admin-cleanup` project）、`e2e/support/schema.ts`、`schema.teardown.ts` | `CandidateIdentityControllerTest`；实施报告中的 `bash -n` 与完整命令序列记录 | 一致 |

## 开发者验证证据复核

- 命令与契约一致：实施报告 `0083-b01` 按 `deployment.json` 的顺序执行了 `developerSetupCommands` 和 `staticAnalysis` → `build` → `developerTests` → `integrationTests` → `functionalTests`。这些命令与 design「部署拓扑」逐字一致，`deployment.json` 本轮没有改动。
- 测试数量与仓库相符：
  - `mvn -B -ntp test` 记录为 108 个：仓库中 `*Test.java` 的 `@Test` 注解共 108 个。
  - `mvn -B -ntp verify -Pintegration -DskipUnitTests=true` 记录为 47 个：13 个 `*IT` 类的用例数依次为 `DataRetentionIT` 1、`MigrationIT` 5、`WeeklyReportIT` 3、`TicketAssignmentIT` 4、`TicketAccessIT` 3、`TicketStatusIT` 4、`TicketSubmitIT` 4、`TicketSearchIT` 7、`CategoryIT` 2、`InitialAdminSetupIT` 8、`UserRoleChangeIT` 2、`UserAdminIT` 2、`LegacyAdminLoginIT` 2，合计 47。
  - 功能测试记录为“18 passed”：seed、IQA-016、IQA-001～IQA-014 共 14 个、删除一次性 schema、按前缀清理，合计 18，与 `playwright.config.ts` 中 `setup` / `chromium` / `initial-admin` / 两个 teardown project 的组成一致。
- 测试类命令确实运行测试，且没有跳过：`e2e/` 与 `src/test/` 中没有 `.skip`、`.fixme`、`.only`、`@Disabled` 或 `assumeTrue`。`InitialAdminSetupIT` 的 `@BeforeAll` 会先实际创建并删除一个探测 schema，失败时抛错而不是跳过。`0082-b01` 的记录也证实，权限不足时它确实以错误结束。
- 真实依赖与替身边界：`InitialAdminSetupIT` 用 `SpringApplicationBuilder` 在真实 `dev` 实例上启动两个独立上下文，一个配置随机口令，一个不配置口令。它断言 `current_schema()`，覆盖了开放判定、口令、429、必填项、遗留 NULL 哈希行、并发只成功一个、关闭后行集合不变、失败即关闭，结束时 `DROP SCHEMA` 并断言没有残留。e2e 的 IQA-016 流程在 8081 一次性 schema 实例上走完 spec 情形① 的全部步骤：入口与无注册、留空登录名、口令缺失 / 错误、成功、登录后创建员工 / 新增类别 / 打开周报表、入口关闭、带 CSRF 的直接 POST 及同名接管均返回 403 且行集合不变、合成凭据登录失败、原管理员仍可登录，同时校验页面不回显密码与口令。Mockito 替身只出现在单元 / 切片测试中，报告已注明这些测试不作为 functional 证据。
- 受阻与解除记录如实：`0082-b01` 在 `CREATE SCHEMA` 权限被拒时没有勾选 T021、T065～T068、T059，并以 `needs_decision`（DEC-CODING-001）提交。`0083-b01` 依据人工澄清答案 HT-DLV-20261116-72F2B9041E74-0023（选 A）重跑，没有改动代码，只勾选这 6 项。当前 `tasks-implementation.md` 中 71 项任务全部勾选。
- 实施报告没有写入密码、口令或客户内容；合成口令由脚本用 `/dev/urandom` 生成（32 个十六进制字符，满足至少 16 个字符），只通过环境变量传递。

## 用户可见行为比较

- 设置初始管理员（FR-031）：登录页只在所连数据库中没有带凭据 ADMIN 时显示“设置初始管理员”。表单包含设置口令（密码型、`autocomplete=off`、不回填）、登录名、密码、姓名；未配置口令时显示“此部署未提供设置口令，暂不能完成设置”。口令缺失或错误时拒绝提交、不创建账号、流程仍可用；字段校验沿用账号创建的规则。设置成功后跳回登录页并提示“初始管理员已设置，请使用该账号登录”。之后入口不再显示，直接访问返回 403，且不能新增或接管任何账号。整个过程不需要改库或改配置，口令由部署时的平台机密提供。以上与 spec FR-031、AC-021、SC-007、IQA-016 和 design「关键流程」0 一致。
- 不构成自助注册（INT-021）：表单只能创建 ADMIN，没有身份选择项；登录页仍然没有注册入口。
- 凭据不回显、不记日志：重渲染时密码与口令都被清空；日志只记录用户 id 和固定文本。
- 其他用户可见行为（工单生命周期、搜索筛选、周报表、身份与访问控制）本轮没有改动，IQA-001～IQA-014 的开发者功能回归在真实 `dev` 实例上通过，此前的比较结论仍然有效。Intent 中的体验性要求（网页提交、随时看到进度、长期保存）没有被削弱。
- 实现中没有超出 spec 的用户可见能力。

## 架构、Contract、运维与任务覆盖

- 架构与依赖：仍是 Spring Boot 单体。新增内容只有应用内组件和平台 `secrets` 声明（`INITIAL_ADMIN_*` 给 QA 使用，`INITIAL_SETUP_TOKEN` 由应用读取），都有 TD-018、TD-019 和人工审批意见 HT-0016、HT-0018、HT-0019 作为来源。没有引入新的外部服务、遥测后端、身份产品或数据库；限流在内存中实现，没有引入缓存服务。
- HTTP 契约：`GET/POST /setup` 的状态码（403 / 429 / 200 / 302）、字段与 `data-testid`（`setup-link`、`setup-token`、`setup-token-missing`、`setup-throttled`、`setup-login-name`、`setup-password`、`setup-display-name`、`setup-submit`、`setup-closed`、`setup-success`、`field-error-setupToken`）都与 design「HTTP 路由」和「页面自动化定位约定」一致，Independent QA 可以直接使用。
- 候选身份端点：只返回 `deliveryId` 和 `candidateSha`，没有 `dataSchema` 字段，与 TD-017 一致。
- 下游就绪制品：
  - `Dockerfile`：未改动，`USER 10001`，`EXPOSE 8080`。
  - `runtime/deployment.json`：未改动；`containerPort`、`healthPath`、`candidateIdentityPath`、`requiresPrivateNetwork` 和五类命令与 design 一致，`developerFunctionalTestScript` 指向现有脚本。
  - `developer-functional-run.sh`：先检查 `AIDH_DEP_TICKET_DB_*` 和端口占用；启动双实例并分别轮询 health；执行 `chromium` 与 `initial-admin` 两个 project；无论成败都执行保底清理与 `DROP SCHEMA`；以 Playwright 退出码退出，清理失败时也以非零状态退出。脚本兼容 `set -euo pipefail` 与 stdin 为 `/dev/null`，不输出口令或密码。
- 任务覆盖：T001～T071 全部勾选，新增和改写的任务都能在仓库中找到对应实现或测试。已批准行为都有可执行任务；`runtime/qa-run.sh` 没有被预置。

## 延后平台证据

- 候选验证：Runner 在不可变候选提交上执行 `deployment.json` 命令，并对 `developer-functional-run.sh` 做语法检查。Runner 身份需要具备与 Agent 身份相同的建 schema 权限，人工答案 HT-DLV-20261116-72F2B9041E74-0023 已确认平台允许。
- 镜像构建：ACR Tasks 以根目录 `Dockerfile` 构建，镜像用户为 UID 10001。
- 候选部署：在 `cand-{deliveryShortId}` 中部署并连接 `ticket-db` `test` 实例；`healthPath` / `candidateIdentityPath` 校验；平台注入 `AIDH_SECRET_INITIAL_SETUP_TOKEN`。
- Independent QA：IQA-001～IQA-014、IQA-016（按情形① / ② 执行，使用 `AIDH_SECRET_INITIAL_ADMIN_*` 与 `AIDH_SECRET_INITIAL_SETUP_TOKEN`）。IQA-015 按 spec 不在 V1 验收范围，改到后续迭代通过真实升级验证。
- 业务方提供 `test` 环境机密值、UAT、人工审批、发布与合并。

## 非阻塞观察与剩余风险

- 失败限流按 `getRemoteAddr()` 计数。`server.forward-headers-strategy=native` 只信任 Tomcat 默认的内网代理地址段。如果平台网关的出口地址不在这些地址段内，所有客户端会被视为同一个来源：任何人连续 5 次口令错误，就会让所有人在 15 分钟内收到 429。这不影响防抢占，Runbook 已给出“等待 15 分钟或重新部署”的处置，属于 TD-019 已接受的设计取舍。
- 共享 `test` 库的 `public` schema 中是否已有可用管理员，决定了 Independent QA 按情形① 还是情形② 执行。若库中可用管理员不是业务方机密对应的那一个，按 design「支持 Runbook」由人处理，不属于实现缺陷。
- 开发者功能测试会在共享 `dev` 库 `public` schema 中临时插入种子管理员。运行期间，`dev` 库的 `/setup` 判定为关闭，运行结束按前缀清理后恢复。这只影响开发环境，并与 design「数据库数据契约」一致。
- 限流状态保存在单个实例内存中，多副本时各自计数；V1 为单副本，与 design 一致。
- 此前各轮记录的观察仍然成立：`/actuator/health` 返回体含 `groups` 字段；报表全库指标在共享库上存在很小的竞态窗口；HttpSession 存在内存中，重新部署后需要重新登录。
