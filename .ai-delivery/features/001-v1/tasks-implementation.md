# 内部 IT 工单系统实施任务

## 任务规则

- 每项任务使用格式：`- [ ] T### [P?] [US#?] 描述（路径；来源：…；依赖：…）`
- 路径必须位于 design 的 "Component 边界与项目结构" 路径清单内。
- 每项任务引用来源：Specification 中的 US / FR / AC / IQA、design 中的 Contract 或技术决策。
- 测试任务位于对应生产实现任务之前；测试素材优先自行合成，并提交到 design 声明的测试路径。
- 候选验证、部署、Independent QA、UAT、发布和合并结果属于下游平台证据，不作为任务。

补充约定（来自 design，不新增决定）：

- Java 包根为 `com.ithelpdesk`，生产代码位于 `src/main/java/com/ithelpdesk/`，测试位于 `src/test/java/com/ithelpdesk/`；`*Test.java` 为单元 / 切片测试（Surefire），`*IT.java` 为连接 `ticket-db` `dev` 实例的集成测试（Failsafe，`-Pintegration`）。
- 集成测试与功能测试一律连接真实 `ticket-db` `dev` 实例（`AIDH_DEP_TICKET_DB_HOST/DATABASE/USER` + `DefaultAzureCredential`），不使用 H2、嵌入式库、Testcontainers 或其他数据库替身；单元测试中的替身只用于纯逻辑，不计入 functional / integration 证据。
- 全部测试数据为合成数据：集成测试使用 `it-<runId>-` 前缀，功能测试使用 `e2e-<时间戳>-<随机>` 前缀；断言只针对本次运行数据或在同一时刻以独立 SQL 计算的期望值；结束时按"数据库数据契约"的清理顺序删除本次前缀数据。不得修改或依赖共享库中遗留的 NULL 哈希 ADMIN 行或其他交付、候选、业务方创建的管理员账号。
- "设置初始管理员"（FR-031）的集成与功能测试只在一次性 schema（`it_setup_<runId>`、`e2e_setup_<运行前缀>`，TD-016）中进行，结束时 `DROP SCHEMA ... CASCADE`；其余测试与应用默认使用 `public` schema（TD-017）。
- 应用代码（`src/main/**`）不读取 `AIDH_SECRET_*`；开发者测试不使用 `INITIAL_ADMIN_*` 机密，自行生成合成管理员（TD-018）。
- 本版计划随 design 修订（TD-011 改为登录入口的一次性 `/setup` 流程，新增 TD-016、TD-017、TD-018，数据库端口改从 `AIDH_DEP_TICKET_DB_PORT` 读取）在首轮实现基础上增量调整：已完成且仍与 design 一致的任务保持勾选；受影响任务按新 design 改写并重置为未完成；新增任务 T061～T068。
- 测试密码只以运行时随机值存在于进程内存或环境中，不写入仓库、日志或报告。
- 页面元素定位一律使用 design「页面自动化定位约定」中的 `data-testid`。

## 依赖关系

- Phase 1（Setup）→ Phase 2（Foundational）→ 用户故事阶段。Phase 2 完成后各用户故事的测试任务可并行编写。
- 用户故事实现链：US-001（T026）→ US-002（T029）；US-003（T033）→ US-004（T037）→ US-005（T041）→ US-006（T044）→ US-007（T048）→ US-008（T052）→ US-009（T056）；US-010（T057～T058）依赖 Foundational 迁移与 US-007 的写操作。
- 跨故事功能测试：以下 e2e 在测试先行阶段编写，但其部分步骤依赖后续故事的实现，只能在所列前置实现任务全部完成后才能整体通过；它们在前置实现任务完成后重新执行，作为所属故事的完成证据：
  - IQA-009（T028，US-002）→ 需要 T044（US-006 认领 / 分派）。
  - IQA-008（T032，US-003）→ 需要 T037（员工用该类别提交工单、详情基本信息）、T041（工单详情页路由与"改名后详情显示新名"）、T052（IT 同事按该类别筛选找到历史工单）。
  - IQA-002（T036，US-004）的详情与列表步骤 → 需要 T041（`GET /tickets/{id}`、员工"我的工单"与 IT 同事全部列表）。
  - IQA-003（T040，US-005）的搜索 / 筛选步骤 → 需要 T052（US-008）。
  - IQA-004（T040，US-005）的 IT 同事回复与空回复步骤 → 需要 T048（US-007 IT 同事回复路径）。
- 在前置实现任务完成之前，上述故事以其单元 / 切片测试与 `*IT` 集成测试作为阶段内完成证据；对应 e2e 的整体通过记录在前置实现任务完成后补齐，不得以跳过或删除步骤代替。
- 本版增量链：T004 → T009、T010、T011、T062 → T014；T009、T010 → T018；T006 → T061；T020、T061 → T021。US-001 增补（FR-031）：T063～T066（测试先行）→ T067（依赖 T018、T026）→ T068（依赖 T017）。IQA-016 功能测试（T066）在 T021、T067、T068 完成后整体通过；T058 依赖 T018。
- Phase 13（Polish）依赖全部用户故事阶段与本版增量任务。

## Phase 1：Setup

- [x] T001 创建 Maven 单模块构建：`spring-boot-starter-parent` 3.5.x；依赖 web、thymeleaf、security、jdbc、validation、actuator、`flyway-core` + `flyway-database-postgresql`、PostgreSQL JDBC 驱动、`com.azure:azure-identity-extensions`；测试依赖 `spring-boot-starter-test`、`spring-security-test`；`finalName` 为 `helpdesk`；`maven-checkstyle-plugin` 引用 `config/checkstyle/checkstyle.xml`；Surefire `skipTests` 绑定属性 `skipUnitTests`（默认 false）；`integration` profile 启用 Failsafe 运行 `*IT.java`（`pom.xml`；来源：TD-001、TD-002、TD-003、TD-004、design「部署拓扑、迁移、Rollout 与 Rollback」；依赖：无）
- [x] T002 [P] 编写忽略规则：`target/`、`e2e/node_modules/`、`e2e/test-results/`、`e2e/playwright-report/`（`.gitignore`；来源：design「Component 边界与项目结构」；依赖：无）
- [x] T003 [P] 编写精简 Checkstyle 规则：UnusedImports、AvoidStarImport、以 RegexpSinglelineJava 禁止 `System.out`/`System.err` 与 `printStackTrace`（`config/checkstyle/checkstyle.xml`；来源：design「技术上下文」静态分析、宪章「安全与隐私」不记录客户内容；依赖：无）
- [ ] T004 修订应用入口与 `application.yml`：按 design「配置」表写入 `server.port`、`forward-headers-strategy=native`、Session 超时与 Cookie、`spring.datasource.url`（只引用 `AIDH_DEP_TICKET_DB_HOST`、`AIDH_DEP_TICKET_DB_PORT`、`AIDH_DEP_TICKET_DB_DATABASE`，不写死端口；`sslmode=require`、`currentSchema=${app.db.schema}` + `AzurePostgresqlAuthenticationPlugin`）、`username`（`AIDH_DEP_TICKET_DB_USER`）、Hikari 10/30m/30s、Flyway `baseline-on-migrate=true`/`baseline-version=0`、`spring.flyway.schemas` 与 `default-schema` 均为 `${app.db.schema}`（保留默认 `*:future` 忽略）、`app.db.schema` 默认 `public`、actuator 仅暴露 health 且 `show-details=never`、`app.time-zone=Asia/Shanghai`、Thymeleaf 缓存；删除 `app.bootstrap.*`；不读取 `AIDELIVERY_*` 决定数据位置，不引用 `AIDH_SECRET_*`；日志级别 INFO，禁止 JDBC 参数 DEBUG（`src/main/java/com/ithelpdesk/HelpdeskApplication.java`、`src/main/resources/application.yml`；来源：TD-003、TD-004、TD-009、TD-011、TD-016、TD-017、TD-018、design「配置、可观测性与运维」；依赖：T001）
- [x] T005 [P] 编写多阶段镜像：构建阶段 `maven:3.9-eclipse-temurin-21` 执行 `mvn -B -ntp -DskipTests package`；运行阶段 `eclipse-temurin:21-jre`，复制 `target/helpdesk.jar` 到 `/app/app.jar`，`USER 10001`，`EXPOSE 8080`，`ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app/app.jar"]`；`.dockerignore` 排除 `.git`、`target`、`e2e/node_modules`、`.ai-delivery`（`Dockerfile`、`.dockerignore`；来源：design「部署拓扑、迁移、Rollout 与 Rollback」、平台：数字非 root UID；依赖：T001）
- [x] T006 [P] 初始化功能测试工程：`@playwright/test`（与 Pod 预装版本一致）、`pg`、`@azure/identity`、`bcryptjs` 及锁文件；`playwright.config.ts` 设 `baseURL=http://localhost:8080`、Chromium、headless、失败时保留截图与 trace（`e2e/package.json`、`e2e/package-lock.json`、`e2e/playwright.config.ts`；来源：TD-015；依赖：无）
- [ ] T061 [P] 在 Playwright 配置中新增独立 project `initial-admin`：只匹配 `tests/iqa-016-initial-admin.spec.ts`，`baseURL=http://localhost:8081`，不依赖种子管理员 `setup` project；原 `chromium` project 排除该文件（`e2e/playwright.config.ts`；来源：TD-016、design「Component 边界与项目结构」目录树、design「验证环境责任矩阵」功能测试；依赖：T006）

## Phase 2：Foundational

### 测试

- [x] T007 编写集成测试支撑：`application-it.yml` 启用可变测试时钟（替换生产 `Clock` Bean）；`support/` 下提供运行前缀生成（`it-<runId>-`）、合成账号 / 类别 / 工单构造工具、按"数据库数据契约"顺序（`ticket_event` → `ticket_comment` → `ticket` → `category` → `app_user`）按前缀清理的 `@AfterAll` 工具，以及随机历史周选择工具（1970～2000 年间）（`src/test/resources/application-it.yml`、`src/test/java/com/ithelpdesk/support/`；来源：TD-010、design「数据库数据契约」「快速验证指南」数据隔离做法；依赖：T004）
- [x] T008 [P] 编写迁移集成测试 `MigrationIT`：连接 `dev` 实例，验证 Flyway 迁移可重复执行（再次 `migrate` 无待执行脚本、已有数据不变）、五张表与索引存在、`ck_app_user_password` 拒绝 NULL 哈希的非 ADMIN 行、`ux_category_active_name` 拒绝有效同名类别、`role`/`status`/`urgency`/`event_type` CHECK 约束生效（`src/test/java/com/ithelpdesk/MigrationIT.java`；来源：TD-004、FR-029、FR-030、NFR-001、INV-006、INV-007；依赖：T007）
- [ ] T009 [P] 调整单元测试：删除 `InitialAdminBootstrapTest`（被测对象已按 TD-011 删除）；保留并确认 `AppUserDetailsServiceTest`（遗留 NULL 哈希账号返回 `enabled=false`、不存在账号抛出与凭据错误同类异常）（`src/test/java/com/ithelpdesk/user/InitialAdminBootstrapTest.java`、`src/test/java/com/ithelpdesk/auth/AppUserDetailsServiceTest.java`；来源：TD-011、FR-002、design「领域与数据模型」账号凭据；依赖：T004）
- [ ] T010 [P] 以 `LegacyAdminLoginIT` 取代 `InitialAdminBootstrapIT`（删除后者）：在 `public` schema 以本次运行前缀插入 `password_hash` 为 NULL 的 ADMIN 行，用任意密码登录均失败且失败表现与凭据错误相同；测试不修改或依赖库中其他 ADMIN 行，结束按前缀清理（`src/test/java/com/ithelpdesk/user/InitialAdminBootstrapIT.java`、`src/test/java/com/ithelpdesk/auth/LegacyAdminLoginIT.java`；来源：TD-011、FR-002、design「领域与数据模型」账号凭据、design「验证环境责任矩阵」集成测试；依赖：T007）
- [ ] T011 [P] 编写安全与平台端点切片测试：未登录访问 `/tickets`、`/admin/users` 302 到 `/login`；`/login`、`GET /setup`、`POST /setup`（带 CSRF）、`/actuator/health`、`/api/candidate-identity`、`/css/**` 匿名可达（不被重定向到登录）；缺失 CSRF 的 POST（含 `POST /setup`）返回 403；身份不符返回 403 且渲染"您无权访问该页面或执行该操作"；响应含 `X-Content-Type-Options`、`X-Frame-Options: DENY`、`Content-Security-Policy: default-src 'self'`；`RoleRefreshFilterTest`（库中角色变化时重建 `Authentication`、用户不存在时使会话失效并重定向登录）；`CandidateIdentityControllerTest`（只返回 `deliveryId`、`candidateSha` 两个字段，变量缺失时为空字符串）；`HomeController` 按身份重定向（`src/test/java/com/ithelpdesk/auth/SecurityConfigTest.java`、`src/test/java/com/ithelpdesk/auth/RoleRefreshFilterTest.java`、`src/test/java/com/ithelpdesk/platform/CandidateIdentityControllerTest.java`；来源：TD-005、TD-006、TD-011、TD-017、FR-002、FR-003、FR-004、FR-031、design「统一行为契约」「安全、隐私、身份与 Secret」、平台 candidateIdentityPath；依赖：T004）
- [x] T012 [P] 编写 `RequestLogFilterTest`：日志只含方法、路由模板、状态码、耗时、用户 id，不含查询参数关键词、请求体、标题 / 描述 / 沟通内容、密码或令牌；`GlobalExceptionHandlerTest`：`AccessDeniedException` → 403 页面、`BadRequestException` / 参数解析失败 → 400"请求参数无效"、未预期异常 → 500"操作失败，请重试"（`src/test/java/com/ithelpdesk/web/RequestLogFilterTest.java`、`src/test/java/com/ithelpdesk/web/GlobalExceptionHandlerTest.java`；来源：宪章「安全与隐私」、design「可观测性」「统一行为契约」、spec「错误与恢复行为」；依赖：T004）
- [ ] T062 [P] 编写配置与机密使用策略测试：`AppPropertiesTest`（`app.db.schema` 未设置时为 `public`，设置 `AIDELIVERY_DELIVERY_ID`/`AIDELIVERY_CANDIDATE_SHA` 不改变该值；非法值（如含大写、`-`、`;` 或超长）使上下文启动失败）；`SecretUsagePolicyTest`（扫描 `src/main/` 下全部源码与资源，不出现 `AIDH_SECRET_` 字样）（`src/test/java/com/ithelpdesk/config/AppPropertiesTest.java`、`src/test/java/com/ithelpdesk/SecretUsagePolicyTest.java`；来源：TD-016、TD-017、TD-018、design「验证环境责任矩阵」单元/切片测试、design「安全、隐私、身份与 Secret」；依赖：T004）

### 实现

- [x] T013 编写迁移脚本 `V1__init.sql`，内容与 design「表结构」一致（`app_user`、`category`、`ticket`、`ticket_comment`、`ticket_event` 及全部索引与约束），不含任何 `DROP`/`TRUNCATE`/`DELETE`（`src/main/resources/db/migration/V1__init.sql`；来源：TD-004、TD-008、design「领域与数据模型」；依赖：T008）
- [ ] T014 实现基础配置：`AppProperties`（`app.time-zone`；`app.db.schema` 默认 `public`，启动时校验匹配 `^[a-z][a-z0-9_]{0,62}$`，否则启动失败；不读取 `AIDELIVERY_*`）、`ClockConfig`（生产 `Clock.system(zone)`）、`WebMvcConfig`（`TimeFormat` 等视图辅助注册）（`src/main/java/com/ithelpdesk/config/`；来源：TD-009、TD-010、TD-016、TD-017；依赖：T004、T062）
- [x] T015 实现 Web 公共层：`AccessDeniedException`、`BadRequestException`、`GlobalExceptionHandler`、`RequestLogFilter`、`TimeFormat`（公司时区格式化）；错误页 `error/403.html`（含 `data-testid="forbidden"`，不含任何业务数据）、`error/400.html`、`error/500.html`（`src/main/java/com/ithelpdesk/web/`、`src/main/resources/templates/error/`；来源：TD-014、design「统一行为契约」「日志隐私」；依赖：T012、T014）
- [x] T016 实现账号数据层：`Role` 枚举（码值与中文显示）、`AppUser`、`UserRepository`（按 id / 登录名查询、插入、身份更新、列出当前 IT 同事），全部使用命名参数（`src/main/java/com/ithelpdesk/user/Role.java`、`src/main/java/com/ithelpdesk/user/AppUser.java`、`src/main/java/com/ithelpdesk/user/UserRepository.java`；来源：TD-002、TD-006、INV-006；依赖：T013）
- [x] T017 实现认证与布局：`AppUserDetailsService`（NULL 哈希返回禁用用户）、`CurrentUser`、`RoleRefreshFilter`（位于 `AuthorizationFilter` 之前）、`SecurityConfig`（表单登录、BCrypt strength 10、会话固定防护、CSRF、URL 授权规则、安全响应头与 CSP、`/login?error` 统一提示"登录名或密码错误"）、`LoginController`、`HomeController`；模板 `login.html`（`login-username`/`login-password`/`login-submit`/`login-error`，无注册入口）、`layout.html`（按身份渲染导航 `nav-*` 与 `logout`、`flash` 提示）；`static/css/app.css`（描述与沟通内容 `white-space: pre-wrap`，不使用内联脚本）（`src/main/java/com/ithelpdesk/auth/`、`src/main/java/com/ithelpdesk/config/SecurityConfig.java`、`src/main/resources/templates/login.html`、`src/main/resources/templates/layout.html`、`src/main/resources/static/css/app.css`；来源：TD-005、TD-006、TD-013、FR-001、FR-002、FR-003、FR-004、design「HTTP 路由」「安全、隐私、身份与 Secret」；依赖：T009、T011、T015、T016）
- [ ] T018 删除启动引导：移除 `InitialAdminBootstrap`、`BootstrapProperties` 及其在应用入口中的注册引用，启动阶段不写入任何账号或业务数据（`src/main/java/com/ithelpdesk/user/InitialAdminBootstrap.java`、`src/main/java/com/ithelpdesk/user/BootstrapProperties.java`、`src/main/java/com/ithelpdesk/HelpdeskApplication.java`；来源：TD-011、design「数据流、状态、并发与生命周期」启动顺序；依赖：T004、T009、T010）
- [x] T019 实现候选身份端点 `GET /api/candidate-identity`，从 `AIDELIVERY_DELIVERY_ID`、`AIDELIVERY_CANDIDATE_SHA` 读取并返回 JSON（`src/main/java/com/ithelpdesk/platform/CandidateIdentityController.java`；来源：design「HTTP 路由」、平台 candidateIdentityPath；依赖：T011、T017）
- [ ] T020 修订功能测试支撑：`db.ts` 以 `@azure/identity` 获取受众 `https://ossrdbms-aad.database.windows.net/.default` 的令牌、TLS 连接 `dev` 库，主机、端口、库名、用户分别取自 `AIDH_DEP_TICKET_DB_HOST`、`AIDH_DEP_TICKET_DB_PORT`、`AIDH_DEP_TICKET_DB_DATABASE`、`AIDH_DEP_TICKET_DB_USER`（不写死端口），提供以运行前缀在 `public` schema 插入管理员种子（`bcryptjs` 生成哈希，密码取自进程环境中的随机值）、插入回溯时间工单 / `STATUS` 事件、按前缀清理；`schema.ts` 对一次性 schema 名做格式校验（只接受 `e2e_setup_` 前缀的小写字母数字下划线名）后执行 `DROP SCHEMA IF EXISTS <name> CASCADE`；`fixtures.ts` 提供按身份登录、创建合成账号 / 类别 / 工单的页面操作与运行前缀（`e2e/support/db.ts`、`e2e/support/schema.ts`、`e2e/support/fixtures.ts`；来源：TD-015、TD-016、design「配置」、design「数据库数据契约」；依赖：T006、T013）
- [ ] T021 修订开发者功能验证脚本：生成运行前缀与随机密码（只存在于进程环境，不输出）→ `mvn -B -ntp -DskipTests package` → 后台启动两个实例：8080（默认 `public` schema）与 8081（`--server.port=8081 --app.db.schema=e2e_setup_<运行前缀转小写下划线>`）→ 分别轮询 `/actuator/health` 至 UP（各超时 120 秒）→ `cd e2e && npx playwright test`（含 `initial-admin` project）→ 无论成败都按前缀清理并以 `e2e/support/schema.ts` 删除一次性 schema → 停止两个实例 → 以 Playwright 退出码退出；脚本兼容 `bash -euo pipefail` 与 stdin 为 `/dev/null`（`.ai-delivery/features/001-v1/runtime/developer-functional-run.sh`；来源：design「快速验证指南」、design「验证环境责任矩阵」功能测试、TD-016；依赖：T004、T020、T061）
- [x] T022 编写候选部署契约：`containerPort` 8080、`healthPath` `/actuator/health`、`candidateIdentityPath` `/api/candidate-identity`、`requiresPrivateNetwork` false；`developerSetupCommands` 为 `mvn -B -ntp -q dependency:go-offline`、`cd e2e && npm ci`；`developerValidationCommands` 按 `staticAnalysis` → `build` → `developerTests` → `integrationTests` → `functionalTests` 填写 design 给定命令（`.ai-delivery/features/001-v1/runtime/deployment.json`；来源：design「部署拓扑、迁移、Rollout 与 Rollback」；依赖：T001、T021）

## Phase 3：US-001 - 管理员创建账号并区分身份

### 测试

- [x] T023 [P] [US1] 编写 `UserAdminServiceTest`：登录名 / 密码 / 姓名为空或仅含空白被拒并逐项提示、超长（64 / 72 字节 / 100）被拒、身份只接受三种码值、非管理员调用被拒、失败回填时密码不回显（`src/test/java/com/ithelpdesk/user/UserAdminServiceTest.java`；来源：FR-001、FR-003、TD-013、design「字段规则与不变量映射」；依赖：T016）
- [x] T024 [P] [US1] 编写 `UserAdminIT`：在 `dev` 库以前缀创建员工 / IT 同事 / 管理员各一，密码以 BCrypt 存储、`created_by` 为操作管理员；重复登录名被拒且不产生账号；新账号可用该凭据认证、错误凭据失败（`src/test/java/com/ithelpdesk/user/UserAdminIT.java`；来源：FR-001、FR-002、INV-006、IQA-001；依赖：T007、T016）
- [x] T025 [P] [US1] 编写功能测试：未登录访问登录页无注册入口、直接访问 `/tickets` 被引导至登录；种子管理员创建三种身份合成账号并在账号列表（`user-row`）中可见；重复登录名被拒；三个新账号逐一登录后导航只含与身份相符的入口、可退出；错误凭据显示 `login-error`（`e2e/tests/iqa-001-accounts.spec.ts`；来源：IQA-001、AC-001、AC-002、FR-001、FR-002、FR-003；依赖：T020）

### 实现

- [x] T026 [US1] 实现账号创建：`UserAdminService`（管理员授权、去空白与长度校验、登录名唯一冲突转为"登录名已存在"、BCrypt 哈希）、`UserAdminController`（`GET /admin/users`、`POST /admin/users`，PRG + flash）、`admin/users.html`（账号列表 `user-row` 与创建表单，`novalidate`，密码框失败时清空）（`src/main/java/com/ithelpdesk/user/UserAdminService.java`、`src/main/java/com/ithelpdesk/user/UserAdminController.java`、`src/main/resources/templates/admin/users.html`；来源：FR-001、FR-002、FR-003、TD-005、TD-013、design「HTTP 路由」；依赖：T017、T023、T024、T025）

### 测试（设置初始管理员，FR-031）

- [ ] T063 [P] [US1] 编写 `InitialAdminSetupServiceTest`（对数据访问使用替身，只验证分支逻辑）：`isSetupOpen()` 按"不存在 `role='ADMIN' AND password_hash IS NOT NULL`"判定且每次调用都查询、不缓存；关闭时 `setup` 拒绝且不写入；登录名 / 密码 / 姓名为空或仅含空白时逐项给出"请填写登录名""请填写密码""请填写姓名"且不写入；超长（登录名 64、密码 72 字节、姓名 100）被拒；登录名已存在（含唯一冲突异常兜底）转为"登录名已存在"且不写入；成功时以 BCrypt 哈希插入 `role='ADMIN'`、`created_by` 为 NULL；日志只含用户 id，不含登录名、姓名、密码或哈希（`src/test/java/com/ithelpdesk/user/InitialAdminSetupServiceTest.java`；来源：FR-031、AC-021、TD-011、TD-013、design「关键流程」0、宪章「安全与隐私」；依赖：T016）
- [ ] T064 [P] [US1] 编写 `InitialAdminSetupControllerTest`（`@WebMvcTest`，对设置服务使用替身）：开放时 `GET /setup` 渲染 `setup-login-name`、`setup-password`、`setup-display-name`、`setup-submit`，无身份选择项、无身份导航；关闭时 `GET /setup` 与带 CSRF 的 `POST /setup` 均返回 403、渲染 `setup-closed`"系统已完成初始管理员设置，此入口不可用"及返回登录链接，且未调用写入；`POST /setup` 校验失败返回 200、显示 `field-error-<字段名>`、回填登录名与姓名、密码框为空且响应不含所提交密码；成功 302 到 `/login?setup`（不自动登录）；`GET /login` 开放时含 `setup-link`、关闭时不含，`/login?setup` 显示 `setup-success`，登录页无注册入口（`src/test/java/com/ithelpdesk/user/InitialAdminSetupControllerTest.java`；来源：FR-031、AC-021、TD-011、design「HTTP 路由」「统一行为契约」「页面自动化定位约定」；依赖：T011）
- [ ] T065 [P] [US1] 编写 `InitialAdminSetupIT`：先在 `dev` 实例确认当前身份可创建并删除一次性 schema（若被拒绝，停止本任务并在结果文件以 `decisionsNeeded`（`dependency.name=ticket-db`）提出，不改用替身）；以独立 Spring 上下文 `app.db.schema=it_setup_<runId>` 启动（Flyway 自动创建 schema），验证：迁移后开放、启动未写入任何账号、必填项为空不创建且仍开放、成功创建 BCrypt 哈希的 ADMIN（`created_by` 为 NULL）后关闭且新管理员可用所设凭据认证、关闭后再提交（新登录名与已设置登录名各一次）不新增或修改任何行、两个并发提交只成功一个、预先插入的遗留 NULL 哈希 ADMIN 行不影响开放判定且不被修改（以其登录名提交被拒"登录名已存在"）；`@AfterAll` 执行 `DROP SCHEMA it_setup_<runId> CASCADE`（`src/test/java/com/ithelpdesk/user/InitialAdminSetupIT.java`；来源：FR-031、AC-021、IQA-016、TD-011、TD-016、TD-017、design「验证环境责任矩阵」集成测试；依赖：T004、T007、T013）
- [ ] T066 [P] [US1] 编写功能测试（`initial-admin` project，localhost:8081 一次性 schema 实例，不插入任何数据）：登录页显示 `setup-link` 且无注册入口；留空登录名提交被拒（`field-error-*`）且入口仍可用；以运行时随机生成的合成登录名 / 密码 / 姓名完成设置，出现 `setup-success`，页面任何位置不回显密码；用该凭据登录，创建一个合成员工账号、新增一个合成类别、打开周报表；退出后登录页不显示 `setup-link`；直接访问 `/setup` 显示 `setup-closed`；以带有效 CSRF 令牌的直接 POST 用另一组合成信息（含与初始管理员相同登录名的一次）提交均被拒；该组凭据登录显示 `login-error`；原管理员仍可登录（`e2e/tests/iqa-016-initial-admin.spec.ts`；来源：IQA-016、AC-021、FR-031、SC-007、TD-016；依赖：T020、T061）

### 实现（设置初始管理员，FR-031）

- [ ] T067 [US1] 实现设置初始管理员流程：`SetupForm`；`InitialAdminSetupService`（`isSetupOpen()` 每次实时查询；`setup` 在单个事务内 `pg_advisory_xact_lock(7243011)` → 再次判定 → 与 `POST /admin/users` 同一校验规则 → 登录名唯一检查 → INSERT `role='ADMIN'`、BCrypt strength 10、`created_by=NULL`，唯一冲突转"登录名已存在"；按 design 记录 INFO 日志，不记录登录名、姓名、密码或哈希）；`UserRepository` 增加判定查询与初始管理员插入；`InitialAdminSetupController`（`GET/POST /setup`：关闭时 403 + `setup/closed.html` 且不读写账号，校验失败 200 重渲染并清空密码，成功 302 `/login?setup`）；`setup/form.html`（`novalidate`、无身份导航、无身份选择项）、`setup/closed.html`（`setup-closed`、返回登录链接）（`src/main/java/com/ithelpdesk/user/SetupForm.java`、`src/main/java/com/ithelpdesk/user/InitialAdminSetupService.java`、`src/main/java/com/ithelpdesk/user/InitialAdminSetupController.java`、`src/main/java/com/ithelpdesk/user/UserRepository.java`、`src/main/resources/templates/setup/form.html`、`src/main/resources/templates/setup/closed.html`；来源：FR-031、AC-021、SC-007、TD-011、TD-013、TD-017、design「关键流程」0、design「HTTP 路由」；依赖：T018、T026、T063、T064、T065、T066）
- [ ] T068 [US1] 接入登录入口与授权：`SecurityConfig` 将 `GET/POST /setup` 设为匿名可达（CSRF 保持启用）；`LoginController` 每次 `GET /login` 调用 `isSetupOpen()`；`login.html` 开放时显示 `setup-link`（指向 `/setup`），`?setup` 时显示 `setup-success`"初始管理员已设置，请使用该账号登录"，仍无注册入口（`src/main/java/com/ithelpdesk/config/SecurityConfig.java`、`src/main/java/com/ithelpdesk/auth/LoginController.java`、`src/main/resources/templates/login.html`；来源：FR-031、AC-021、TD-011、design「HTTP 路由」「安全、隐私、身份与 Secret」「页面自动化定位约定」；依赖：T017、T067）

## Phase 4：US-002 - 管理员设置 IT 同事

### 测试

- [x] T027 [P] [US2] 编写 `UserRoleChangeIT`：管理员可在 `EMPLOYEE ↔ IT_STAFF` 间切换、管理员账号与 `ADMIN` 目标值被拒；身份变更后目标会话下一次请求即按新身份授权（结合 `RoleRefreshFilter`）；取消 IT 同事身份后不再出现在当前 IT 同事列表，其已作为处理人的工单 `assignee_id` 不被清空（`src/test/java/com/ithelpdesk/user/UserRoleChangeIT.java`；来源：FR-004、TD-006、INV-002、IQA-009；依赖：T007、T016）
- [x] T028 [P] [US2] 编写功能测试：管理员把员工 U1 设为 IT 同事 → U1 看到全部工单并认领一张；取消身份 → U1 只具备员工能力（导航与直接访问 IT 操作被拒）、T1 的分派对象列表中不含 U1、U1 认领过的工单仍显示 U1 为处理人（认领 / 分派步骤在 T044 完成后通过）（`e2e/tests/iqa-009-it-staff-role.spec.ts`；来源：IQA-009、AC-004、FR-004；依赖：T020）

### 实现

- [x] T029 [US2] 实现身份切换：`UserAdminService.changeRole`（仅 `UPDATE ... WHERE role IN ('EMPLOYEE','IT_STAFF')`）、`POST /admin/users/{id}/role`、`admin/users.html` 每行的身份切换表单（管理员行不显示）（`src/main/java/com/ithelpdesk/user/UserAdminService.java`、`src/main/java/com/ithelpdesk/user/UserAdminController.java`、`src/main/resources/templates/admin/users.html`；来源：FR-004、design「关键流程」6；依赖：T026、T027、T028）

## Phase 5：US-003 - 管理员维护工单类别

### 测试

- [x] T030 [P] [US3] 编写 `CategoryServiceTest`：名称去首尾空白后为空被拒（"类别名称不能为空"类提示）、超过 50 字符被拒、非管理员调用被拒（`src/test/java/com/ithelpdesk/category/CategoryServiceTest.java`；来源：FR-005、TD-013；依赖：T016）
- [x] T031 [P] [US3] 编写 `CategoryIT`：新增、改名、移除（`active=false`、`removed_at` 写入）；与有效类别同名（新增或改名）被拒并提示"类别名称已存在"，与已移除类别同名允许；移除后不在有效类别查询中、历史工单外键保留且显示新名称（`src/test/java/com/ithelpdesk/category/CategoryIT.java`；来源：FR-005、FR-006、INV-007、IQA-008；依赖：T007、T013）
- [x] T032 [P] [US3] 编写功能测试：管理员新增前缀类别"测试类别甲"→ 员工用其提交工单；新增空名与重名被拒；改名为"测试类别乙"后工单详情显示新名；移除后员工提交页无该选项、IT 同事按该类别筛选仍能找到历史工单（提交、详情与筛选步骤分别在 T037、T041、T052 完成后通过）（`e2e/tests/iqa-008-categories.spec.ts`；来源：IQA-008、AC-005、FR-005、FR-006；依赖：T020）

### 实现

- [x] T033 [US3] 实现类别维护：`Category`、`CategoryRepository`（全部类别、有效类别、插入、改名、软删除）、`CategoryService`（事务内检查有效同名，唯一冲突转为"类别名称已存在"）、`CategoryAdminController`（`GET/POST /admin/categories`、`/{id}/rename`、`/{id}/remove`）、`admin/categories.html`（`category-row`，已移除标注"（已移除）"）（`src/main/java/com/ithelpdesk/category/`、`src/main/resources/templates/admin/categories.html`；来源：FR-005、FR-006、design「关键流程」7；依赖：T017、T030、T031、T032）

## Phase 6：US-004 - 员工提交工单

### 测试

- [x] T034 [P] [US4] 编写 `TicketFormValidationTest`：标题、描述、类别、紧急程度任一为空或仅含空白时逐项给出"请填写标题""请填写问题描述""请选择类别""请选择紧急程度"类提示、超长被拒、紧急程度只接受 `LOW`/`MEDIUM`/`HIGH`、失败时保留已填内容（`src/test/java/com/ithelpdesk/ticket/TicketFormValidationTest.java`；来源：FR-007、TD-013、PQ-001；依赖：T016）
- [x] T035 [P] [US4] 编写 `TicketSubmitIT`：员工提交后生成唯一 `id`、`status=OPEN`、`assignee_id` 为空、`submitted_at` 等于测试时钟、提交人正确；已移除类别被拒"所选类别已不可用"；非员工提交被拒；仓库层不存在修改标题 / 描述 / 类别 / 紧急程度 / 提交人的语句路径（`src/test/java/com/ithelpdesk/ticket/TicketSubmitIT.java`；来源：FR-007、FR-008、FR-009、INV-004、IQA-002；依赖：T007、T013）
- [x] T036 [P] [US4] 编写功能测试：提交页紧急程度选项恰为"低、中、高"；分别留空四项各提交一次，`field-error-<字段名>` 指出缺失项且已填内容保留；以三种紧急程度各提交一张，详情显示编号、紧急程度、待处理、未分配、提交人与提交时间；详情无修改基本信息入口；工单出现在员工"我的工单"与 IT 同事全部列表（详情与列表步骤在 T041 完成后通过）；"无有效类别"分支不属于本 e2e（共享 `dev` 库中类别为全局数据，无法在不改动他人数据的前提下进入该状态），由 T060 验证（`e2e/tests/iqa-002-submit-ticket.spec.ts`；来源：IQA-002、AC-006、FR-007、FR-008、FR-009；依赖：T020）
- [x] T060 [P] [US4] 编写 `TicketNewPageTest`（`@WebMvcTest(TicketController.class)`，对类别服务与工单命令服务使用替身，只验证视图条件渲染与请求处理）：以员工身份 `GET /tickets/new`，有效类别为空时页面显示"暂无可选类别，请联系管理员"且不渲染 `ticket-submit` 与 `ticket-category` 选项；有效类别非空时渲染 `ticket-category` 选项与 `ticket-submit`、`ticket-urgency` 选项恰为"低、中、高"；`POST /tickets` 在命令服务以"所选类别已不可用"拒绝时重新渲染表单并显示该提示、保留已填内容（`src/test/java/com/ithelpdesk/ticket/TicketNewPageTest.java`；来源：spec「前置条件」提交工单前置、FR-007、design「HTTP 路由」`GET /tickets/new`；依赖：T011）

### 实现

- [x] T037 [US4] 实现工单提交：`TicketStatus`、`Urgency`、`EventType` 枚举（码值与中文显示）、`Ticket`、`TicketRepository`（插入、按 id 查询，无更新基本信息语句）、`TicketForm`、`TicketCommandService.submit`（事务内确认类别有效、`clock.instant()`）、`TicketController`（`GET /tickets/new`、`POST /tickets`，PRG + "工单已提交"）、`tickets/new.html`（`ticket-title`/`ticket-description`/`ticket-category`/`ticket-urgency`/`ticket-submit`、`novalidate`、无有效类别提示）、`tickets/detail.html` 基本信息区（`ticket-id`、`ticket-status`、`ticket-assignee`）（`src/main/java/com/ithelpdesk/ticket/`、`src/main/resources/templates/tickets/new.html`、`src/main/resources/templates/tickets/detail.html`；来源：FR-007、FR-008、FR-009、TD-013、design「关键流程」3；依赖：T033、T034、T035、T036、T060）

## Phase 7：US-005 - 员工跟踪自己的工单并补充说明

### 测试

- [x] T038 [P] [US5] 编写 `TicketAccessIT`：员工列表只含本人工单（不限状态）；员工读取他人或不存在的工单均抛出同一拒绝；员工可在本人任意状态（含已关闭）工单下添加补充说明，空 / 仅空白被拒；补充说明不改变 `status`、`assignee_id`；沟通记录按 `created_at, id` 排序并记录 `author_role`；员工对他人工单添加补充说明被拒（`src/test/java/com/ithelpdesk/ticket/TicketAccessIT.java`；来源：FR-010、FR-011、FR-012、FR-013、FR-019、INV-003、INV-005、TD-014、IQA-003、IQA-004；依赖：T007、T013）
- [x] T039 [P] [US5] 编写 `TicketControllerTest`（`@WebMvcTest`）：员工访问他人 / 不存在工单返回 403 且响应不含标题、描述或任何工单字段；员工详情页不含 `claim-button`、`assign-select`、`status-select`；管理员访问 `/tickets` 与详情被拒（`src/test/java/com/ithelpdesk/ticket/TicketControllerTest.java`；来源：FR-013、FR-021、TD-014、A-004；依赖：T011）
- [x] T040 [P] [US5] 编写功能测试：E1、E2 各提交 2 张含相同关键词、状态不同的合成工单；E1"我的工单"恰含其全部工单并显示正确状态与处理人；E1 直接访问 E2 工单显示 `forbidden` 且无内容泄露；E1 关键词搜索与各条件筛选不出现 E2 工单（`e2e/tests/iqa-003-employee-isolation.spec.ts`，搜索 / 筛选步骤在 T052 完成后通过）；E1 添加补充说明与空补充说明、T1 回复与空回复（回复步骤在 T048 完成后通过）、双方看到一致的沟通记录（作者、身份、时间、内容）、状态与处理人不变、已关闭工单仍可补充说明（`e2e/tests/iqa-004-comments.spec.ts`）（`e2e/tests/`；来源：IQA-003、IQA-004、AC-007、AC-008、AC-009、AC-013、FR-010～FR-013、FR-018、FR-019、FR-024；依赖：T020）

### 实现

- [x] T041 [US5] 实现员工查看与补充说明：`TicketComment`、`TicketEvent`、`TicketCommentRepository`（插入、按工单查询）、`TicketEventRepository`（按工单查询）、`CommentForm`、`TicketQueryService`（员工可见范围 `submitter_id = 本人`、IT 同事可见全部工单、详情授权与 TD-014 统一 403）、`TicketCommandService.addComment`（员工须为提交人，记录 `author_role`）、`TicketController`（`GET /tickets` 基础列表与分页、`GET /tickets/{id}`、`POST /tickets/{id}/comments`）、`tickets/list.html`（"我的工单"/"全部工单"标题、`ticket-row` 含 `data-ticket-id`、编号 / 标题 / 类别 / 紧急程度 / 状态 / 处理人或"未分配" / 提交时间）、`tickets/detail.html` 沟通记录（`comment-item`、`comment-body`、`comment-submit`）与处理记录（`event-item`）区（`src/main/java/com/ithelpdesk/ticket/`、`src/main/resources/templates/tickets/list.html`、`src/main/resources/templates/tickets/detail.html`；来源：FR-010、FR-011、FR-012、FR-013、FR-019、FR-020、TD-012、TD-014、design「关键流程」4；依赖：T037、T038、T039、T040）

## Phase 8：US-006 - IT 同事查看、认领与分派工单

### 测试

- [x] T042 [P] [US6] 编写 `TicketAssignmentIT`：IT 同事列表含全部提交人的工单；认领未分配与已有处理人的工单后 `assignee_id` 为本人并写入 `CLAIM` 事件（操作人、时间、前后值）；已是本人时提示"您已是该工单处理人"且不写事件；分派给另一位当前 IT 同事写入 `ASSIGN` 事件；分派给员工、管理员、已取消身份账号或本人被拒"分派对象必须是其他 IT 同事"且工单不变；分派给当前处理人提示无变化；事务失败整体回滚（`src/test/java/com/ithelpdesk/ticket/TicketAssignmentIT.java`；来源：FR-014、FR-015、FR-016、FR-020、INV-002、INV-005、design「关键流程」5、IQA-005；依赖：T007、T013）
- [x] T043 [P] [US6] 编写功能测试：T1 查看全部工单含 E1、E2 工单；T1 认领 E1 工单；T1 把 E2 工单分派给 T2，`assign-select` 只含当前 IT 同事（不含员工、管理员、本人）；T2 认领 T1 处理的工单，处理记录显示操作人、时间、前后值；E1 详情看到最新处理人与处理记录（`e2e/tests/iqa-005-claim-assign.spec.ts`；来源：IQA-005、AC-010、AC-011、FR-014、FR-015、FR-016、FR-020；依赖：T020）

### 实现

- [x] T044 [US6] 实现认领与分派：`TicketRepository` 增加 `SELECT ... FOR UPDATE` 与处理人更新、`TicketEventRepository` 增加插入、`TicketCommandService.claim/assign`（行锁、目标账号 `FOR SHARE` 校验 `IT_STAFF`）、`TicketController`（`POST /tickets/{id}/claim`、`/assign`）、`tickets/detail.html` IT 同事操作区（`claim-button`、`assign-select`、`assign-submit`，分派选项为当前 IT 同事去除本人）、处理记录显示操作人 / 时间 / 前后值（`src/main/java/com/ithelpdesk/ticket/`、`src/main/resources/templates/tickets/detail.html`；来源：FR-014、FR-015、FR-016、FR-020、TD-002、TD-008、design「关键流程」5；依赖：T041、T042、T043）

## Phase 9：US-007 - IT 同事推进状态并回复

### 测试

- [x] T045 [P] [US7] 编写 `TicketStatusIT`：四种状态间任意修改、每次写入 `STATUS` 事件（前后值）；与当前相同提示"状态未变化"且不写事件；首次改为 `RESOLVED` 时写入 `first_resolved_at`（测试时钟）与当时 `assignee_id`（可为空），之后改回再解决不改变两列；IT 同事回复记录 `author_role=IT_STAFF`、不改变状态与处理人、空回复被拒；员工调用认领 / 分派 / 改状态服务被拒且工单与事件不变（`src/test/java/com/ithelpdesk/ticket/TicketStatusIT.java`；来源：FR-017、FR-018、FR-020、FR-021、INV-001、INV-005、TD-008、IQA-006、IQA-007；依赖：T007、T013）
- [x] T046 [P] [US7] 编写 `TicketActionAuthorizationTest`（`@WebMvcTest`）：员工 `POST /tickets/{id}/claim|assign|status` 返回 403；非法 `status` 码值返回 400（`src/test/java/com/ithelpdesk/ticket/TicketActionAuthorizationTest.java`；来源：FR-021、FR-017、design「统一行为契约」；依赖：T011）
- [x] T047 [P] [US7] 编写功能测试：T1 依次改为处理中 → 已解决 → 处理中 → 已解决 → 已关闭 → 待处理，每步列表与详情立即显示新状态、产生处理记录、`status-select` 只含四种状态，提交人可见（`e2e/tests/iqa-006-status.spec.ts`）；E1 本人工单详情无认领 / 分派 / 状态入口，以带有效 CSRF 令牌的直接 POST 发起三类操作均显示 `forbidden`，操作前后状态、处理人与处理记录一致（`e2e/tests/iqa-007-employee-no-actions.spec.ts`）（`e2e/tests/`；来源：IQA-006、IQA-007、AC-012、AC-014、FR-017、FR-020、FR-021；依赖：T020）

### 实现

- [x] T048 [US7] 实现状态修改与回复：`TicketCommandService.changeStatus`（行锁、相同值不写、首次已解决冗余列仅在为 NULL 时写入、`STATUS` 事件）与 IT 同事回复路径（复用 `POST /tickets/{id}/comments`，IT 同事不限工单）、`TicketController`（`POST /tickets/{id}/status`）、`tickets/detail.html`（`status-select`、`status-submit`）（`src/main/java/com/ithelpdesk/ticket/`、`src/main/resources/templates/tickets/detail.html`；来源：FR-017、FR-018、FR-020、FR-021、TD-008、design「关键流程」4、5；依赖：T044、T045、T046、T047）

## Phase 10：US-008 - 搜索与筛选工单

### 测试

- [x] T049 [P] [US8] 编写 `TicketSearchCriteriaTest`：关键词转义 `\`、`%`、`_`；`from`/`to` 按公司时区转换为 `[from 00:00, to+1 00:00)`；`from > to` 给出"开始日期不能晚于结束日期"；`assignee=unassigned` 解析；非法日期 / 数字 / 状态码值抛出 `BadRequestException`；页码从 1 开始（`src/test/java/com/ithelpdesk/ticket/TicketSearchCriteriaTest.java`；来源：FR-022、FR-023、TD-007、TD-009、TD-012；依赖：T014）
- [x] T050 [P] [US8] 编写 `TicketSearchIT`：合成工单覆盖不同标题 / 描述片段、状态、类别（含已移除）、处理人（含未分配）与提交日期；验证中文片段与英文大小写不敏感部分匹配、含 `%`/`_` 的关键词按字面匹配、各单项筛选与"关键词 + 状态 + 类别"组合结果与独立计算的预期集合完全一致（限定本次前缀数据）、员工检索不含他人工单、分页每页 50 条且按提交时间与 id 倒序（`src/test/java/com/ithelpdesk/ticket/TicketSearchIT.java`；来源：FR-022、FR-023、FR-024、INV-003、TD-007、TD-012、IQA-011、IQA-012；依赖：T007、T013）
- [x] T051 [P] [US8] 编写功能测试：合成"打印机卡纸"（标题）、"VPN 连接断开"（描述）与对照工单，T1 搜索"卡纸""VPN 连接""打印""不存在的文字XYZ"并核对结果与 `ticket-empty`（`e2e/tests/iqa-011-keyword-search.spec.ts`）；T1 按状态、类别、处理人、"未分配"、提交时间范围（回溯提交时间由 `db.ts` 合成）筛选及组合，结果与预期集合一致（以 `data-ticket-id` 比较并限定本次前缀数据），`filter-clear` 后恢复完整列表（`e2e/tests/iqa-012-filters.spec.ts`）（`e2e/tests/`；来源：IQA-011、IQA-012、AC-015、AC-016、FR-022、FR-023、FR-024；依赖：T020）

### 实现

- [x] T052 [US8] 实现搜索与筛选：`TicketSearchCriteria`、`TicketRepository.search`（`WHERE 1=1` + 可见范围 + 条件，全部命名参数，`ILIKE ... ESCAPE '\'`，`LIMIT 51 OFFSET`）、`TicketQueryService` 筛选选项（状态四项；全部类别含"（已移除）"；处理人 = "未分配" + 当前 IT 同事 + 曾为处理人的账号，按姓名去重排序）、`TicketController` 列表参数绑定、`tickets/list.html` 筛选表单（`filter-*`）、`ticket-empty`"无匹配工单"、保留查询条件的分页（`page-next`、`page-prev`）（`src/main/java/com/ithelpdesk/ticket/`、`src/main/resources/templates/tickets/list.html`；来源：FR-022、FR-023、FR-024、TD-007、TD-012、design「搜索与筛选」；依赖：T041、T049、T050、T051）

## Phase 11：US-009 - IT 主管查看周报表

### 测试

- [x] T053 [P] [US9] 编写单元测试：`WeekRangeTest`（任意时刻在 `Asia/Shanghai` 下回退到周一 00:00、周日 24:00 左闭右开、跨年 ISO 周、显示"YYYY-MM-DD（周一）至 YYYY-MM-DD（周日）"）；`WeeklyReportTest`（平均时长四舍五入到分钟显示"`H` 小时 `M` 分钟"且 `H` 可超过 24、`data-avg-seconds` 四舍五入到整秒、无工单显示"无数据"）（`src/test/java/com/ithelpdesk/report/WeekRangeTest.java`、`src/test/java/com/ithelpdesk/report/WeeklyReportTest.java`；来源：FR-026、FR-027、TD-009、A-002；依赖：T014）
- [x] T054 [P] [US9] 编写 `WeeklyReportIT`：测试时钟设到随机历史周，合成本周与上周提交、本周与上周首次解决、解决后改回再解决、多个类别（含本周被移除类别，标注"（已移除）"）、多个处理人（含已取消 IT 同事身份者）、首次解决时无处理人的工单；校验类别数量只计本周提交、处理量只计本周首次解决并归属当时处理人、"未分配"行、平均处理时长与独立计算期望值一致；无数据周数量为 0 且平均"无数据"；积压按 72 小时严格大于（恰好 72 小时不计入、已解决 / 已关闭不计入、本周之前提交的未解决计入），改为已解决后立即减一（`src/test/java/com/ithelpdesk/report/WeeklyReportIT.java`；来源：FR-026、FR-027、FR-028、PQ-002、TD-008、TD-010、IQA-013、IQA-014；依赖：T007、T013）
- [x] T055 [P] [US9] 编写功能测试：管理员打开周报表，`report-week` 显示本周起止日期，本次前缀类别与 IT 同事的 `report-category-row`/`report-staff-row` 数值与合成数据期望一致，`report-unassigned`、`report-avg`（`data-avg-seconds`）与在同一时刻以独立 SQL 按 FR-027 口径计算的期望一致（`e2e/tests/iqa-013-weekly-report.spec.ts`）；`report-backlog` 等于同一时刻独立 SQL 计算的积压数，并在把一张本次合成的超期待处理工单改为已解决后刷新减一（`e2e/tests/iqa-014-backlog.spec.ts`）；员工与 IT 同事导航无管理与报表入口，直接访问 `/admin/users`、`/admin/categories`、`/admin/users/{id}/role`、`/admin/reports/weekly` 均显示 `forbidden` 且无管理或报表数据（`e2e/tests/iqa-010-admin-access-control.spec.ts`）（`e2e/tests/`；来源：IQA-010、IQA-013、IQA-014、AC-003、AC-017、AC-018、AC-019、FR-003、FR-025～FR-028；依赖：T020）

### 实现

- [x] T056 [US9] 实现周报表：`WeekRange`、`WeeklyReport`、`WeeklyReportRepository`（类别计数、处理人计数、未分配、平均秒数、积压 SQL，时刻参数取自 `Clock`，不使用 SQL `now()`）、`WeeklyReportService`（只读，行集合规则按 design「报表计算」）、`ReportController`（`GET /admin/reports/weekly`）、`admin/report.html`（`report-*` 定位属性）（`src/main/java/com/ithelpdesk/report/`、`src/main/resources/templates/admin/report.html`；来源：FR-025、FR-026、FR-027、FR-028、TD-008、TD-009、TD-010、design「报表计算」；依赖：T048、T053、T054、T055）

## Phase 12：US-010 - 数据在升级或重新部署后保留

### 测试

- [x] T057 [P] [US10] 编写 `MigrationPolicyTest`：扫描 `db/migration` 下全部脚本，命名符合 `V<n>__<desc>.sql`，且不含 `DROP`、`TRUNCATE`、`DELETE` 语句（`src/test/java/com/ithelpdesk/MigrationPolicyTest.java`；来源：NFR-001、FR-030、TD-004；依赖：T013）
- [ ] T058 [US10] 修订 `DataRetentionIT`：以前缀合成账号、类别、多张不同状态工单及沟通记录与处理记录，在测试时钟下记录各工单详情快照、报表结果与 `app_user` 行数；关闭并重新启动一个新的 Spring 应用上下文（再次执行 Flyway，不再有启动引导）后，快照与报表结果完全一致、`app_user` 行数不变（启动不写入任何账号）、账号仍可按原身份认证；作为 FR-029、FR-030 的开发者侧辅助证据（V1 Independent QA 不执行 IQA-015）（`src/test/java/com/ithelpdesk/DataRetentionIT.java`；来源：FR-029、FR-030、NFR-001、INV-008、IQA-015、AC-020、TD-011、TD-017；依赖：T007、T018、T048、T056）

## Phase 13：Polish

- [ ] T059 在 Agent Pod 中按 `deployment.json` 顺序完整执行 `developerSetupCommands` 与 `staticAnalysis` → `build` → `developerTests` → `integrationTests` → `functionalTests`，并对 `developer-functional-run.sh` 执行 `bash -n`；修复失败项后，把各命令结果、使用真实 `ticket-db` `dev` 实例的验证清单（运行前缀、一次性 schema 的创建与删除记录、清理记录）、`src/main/**` 不引用 `AIDH_SECRET_*` 的检查结果、单元测试中替身的范围与原因、以及延后的平台证据追加到实施证据账本，不写入任何密码、令牌或客户内容（`.ai-delivery/features/001-v1/reports/implementation.md`；来源：宪章「测试与质量」「交付治理」、design「验证环境责任矩阵」、TD-016、TD-018；依赖：T002、T003、T005、T014、T018、T019、T021、T022、T026、T029、T033、T037、T041、T044、T048、T052、T056、T057、T058、T062、T067、T068）

## 完成证据

各阶段在 Coding（Agent Pod）中可获得的证据：

| 阶段 | Coding 可获得的证据 |
|---|---|
| Phase 1 Setup | `mvn -B -ntp checkstyle:check`、`mvn -B -ntp -DskipTests package` 成功；`target/helpdesk.jar` 生成；`cd e2e && npm ci` 成功（仅为辅助证据） |
| Phase 2 Foundational | `mvn test` 中安全 / 端点 / 日志单元与切片测试及 `AppPropertiesTest`、`SecretUsagePolicyTest` 通过；`mvn verify -Pintegration` 中 `MigrationIT`、`LegacyAdminLoginIT` 对 `dev` 实例通过；启动后 `app_user` 无新增行（启动不写入账号）；localhost 启动后 `/actuator/health` 为 UP、`/api/candidate-identity` 返回 JSON（辅助证据）；`bash -n` 检查 `developer-functional-run.sh` |
| US-001～US-009 | 对应 `*Test`（单元 / 切片）、`*IT`（真实 `dev` 实例）与 `e2e/tests/iqa-001`～`iqa-014` 的 Playwright 功能测试在 localhost:8080 + `dev` 实例上通过。"无有效类别"提示与不可提交行为由 `TicketNewPageTest`（T060，切片测试）验证。含跨故事步骤的 e2e 在前置实现任务完成后重新执行，作为所属故事的完成证据：IQA-009（US-002）在 T044 后；IQA-008（US-003）在 T037、T041、T052 后；IQA-002（US-004）在 T041 后；IQA-003（US-005）在 T052 后；IQA-004（US-005）在 T048 后。在此之前，这些故事的阶段内证据为其 `*Test` 与 `*IT` 通过。US-001 的设置初始管理员（FR-031）证据：`InitialAdminSetupServiceTest`、`InitialAdminSetupControllerTest` 通过，`InitialAdminSetupIT` 在 `dev` 实例一次性 schema `it_setup_<runId>` 上通过且 schema 已删除，`e2e/tests/iqa-016-initial-admin.spec.ts` 在 localhost:8081（一次性 schema `e2e_setup_<运行前缀>`）上通过且 schema 已删除，作为 IQA-016 首次设置完整流程的开发者侧证据 |
| US-010 | `MigrationPolicyTest` 与 `DataRetentionIT`（真实 `dev` 实例、重新启动应用上下文前后快照一致）通过，重启前后 `app_user` 行数不变；作为 FR-029、FR-030 的开发者侧辅助证据（IQA-015 不在 V1 Independent QA 范围） |
| Polish | `reports/implementation.md` 中记录完整验证序列输出摘要、真实依赖使用清单（含一次性 schema 创建与删除记录）、`src/main/**` 不引用 `AIDH_SECRET_*` 的检查结果与替身说明 |

延后的平台证据（不作为任务，由平台或下游阶段产生）：

- 候选验证：Runner 在不可变候选提交上执行 `deployment.json` 中的命令与 `developer-functional-run.sh` 语法检查。
- 镜像构建：ACR Tasks 以根目录 `Dockerfile` 构建，镜像用户解析为 UID 10001。
- 候选部署：`cand-{deliveryShortId}` 命名空间部署、`healthPath` 与 `candidateIdentityPath` 校验（使用 `ticket-db` `test` 实例）。
- 测试环境初始管理员机密：`INITIAL_ADMIN_LOGIN_NAME`、`INITIAL_ADMIN_PASSWORD`、`INITIAL_ADMIN_DISPLAY_NAME` 由业务方在平台依赖与机密任务中提供，平台以 `AIDH_SECRET_*` 注入 Independent QA（TD-018）；Coding 不使用、不预置这些值。
- Independent QA：IQA-016（最先执行，按情形 ①/②）与 IQA-001～IQA-014 在已部署候选上的验收结果（`qa-run.sh` 与 `qa-result.json` 由 Independent QA 编写与执行，Coding 不编写或预置）。
- IQA-015：V1 不验证升级或重新部署后的数据保留，改到后续迭代通过真实升级来验证（spec IQA-015、AC-020）。
- UAT、人工审批、发布与合并。
