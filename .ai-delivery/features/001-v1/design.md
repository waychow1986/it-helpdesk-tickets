# 内部 IT 工单系统技术方案

## 技术上下文（Technical Context）

- **交付**：DLV-20261116-72F2B9041E74 V1 · 内部 IT 工单系统；权威输入为 `intent.md`（INT-001～INT-024）、`spec.md`（FR-001～FR-031、NFR-001～NFR-002、IQA-001～IQA-016）与已批准的 `technical-baseline.md`（方案 A）。
- **仓库现状**：已按本方案上一版完成首轮实现（`pom.xml`、`src/`、`e2e/`、根目录 `Dockerfile`），见 `reports/implementation.md`。本版只因 spec 新增 FR-031 / IQA-016 / AC-021（一次性“设置初始管理员”流程，人工审批意见 HT-DLV-20261116-72F2B9041E74-0011、PQ-003 人工答案 A）及 IQA-015 执行方式改为以 QD-001 人工答案为准（PQ-004 人工答案 C）而修订：TD-011 由“启动引导 + 运维写入首个凭据”改为登录入口的一次性设置流程，新增 TD-016（开发者测试的独立 schema），并同步路由、配置、验证矩阵与 Runbook；并按人工澄清答案 HT-DLV-20261116-72F2B9041E74-0014（DEC-TS-001 选 D）新增 TD-017：数据作用域就是应用配置所指向的数据库（由平台注入的 `AIDH_DEP_TICKET_DB_*` 决定），不按候选或交付派生 schema，`/setup` 开放判定以该数据库中是否已有可用管理员为准；IQA-015 与 IQA-016 的验收口径已随该答案在 specification 中修订；并按人工审批意见 HT-DLV-20261116-72F2B9041E74-0016 新增 TD-018：测试环境唯一初始管理员的登录名、凭据和姓名以 `test` 环境应用机密（`docs/dependencies.json` 的 `secrets`）交给 Independent QA，QA 不再插入管理员种子；并按人工审批意见 HT-DLV-20261116-72F2B9041E74-0018（拒绝上一版：设置入口对公网匿名开放且无防抢占措施）新增 TD-019：`/setup` 只接受持有部署时由人通过平台机密提供的一次性设置口令（`INITIAL_SETUP_TOKEN`）的提交，口令错误或缺失时不创建账号，并对失败尝试限流，从而只有被授权的人能完成首次设置，同时仍无需任何人手工改库或改配置；TD-011、TD-018 与路由、配置、安全、验证矩阵、Runbook 随之修订；spec 随后已按人工审批意见 HT-DLV-20261116-72F2B9041E74-0018、HT-DLV-20261116-72F2B9041E74-0019 修订 FR-031、SC-007、IQA-016、AC-021 与测试策略，写明首次设置须提供部署时由人提供的一次性设置口令、口令缺失或错误时拒绝且不创建账号，本版据此同步追踪编号与 IQA-016 步骤顺序；其余设计不变。
- **技术栈（定稿）**：
  - 语言与运行时：Java 21（Eclipse Temurin 21）。
  - 框架：Spring Boot 3.5.x（取当时最新补丁版本，由 `spring-boot-starter-parent` 统一管理版本）：Spring MVC + Thymeleaf 服务端渲染、Spring Security（表单登录 + HttpSession + CSRF）、Spring JDBC（`NamedParameterJdbcTemplate`）、Flyway、Spring Boot Actuator、Bean Validation。
  - 数据库：平台 `postgres` 依赖 `ticket-db`（PostgreSQL 项目库），PostgreSQL JDBC 驱动 + `com.azure:azure-identity-extensions`（`AzurePostgresqlAuthenticationPlugin`，底层 `DefaultAzureCredential`）获取 Entra 令牌作为密码。
  - 构建：Maven 3（Pod 预装），单模块 jar；静态分析 Checkstyle（`maven-checkstyle-plugin`）。
  - 测试：JUnit 5 + Spring Boot Test + AssertJ（单元与 Web 切片测试）、Maven Failsafe（连接 `dev` 实例的集成测试）、Node 22 + `@playwright/test`（Chromium，localhost 功能测试）。
- **运行形态**：单进程、单容器、单副本；监听 HTTP `8080`；TLS 由平台网关终止。
- **外部依赖**：仅 `ticket-db` 一个（见 `docs/dependencies.json`）；另在同一文件 `secrets` 中声明 `test` 环境的初始管理员凭据（TD-018，只供 Independent QA，应用不读取）与一次性设置口令 `INITIAL_SETUP_TOKEN`（TD-019，应用读取、QA 用于首次设置）；不使用任何其他云服务、消息、缓存、遥测后端或身份产品。

## 治理与技术约束

| 约束来源 | 约束内容 | 设计响应 |
|---|---|---|
| 宪章「产品与架构」 | 最简单架构、保持已批准范围 | 单体服务端渲染（TD-001）；不实现 spec 范围外能力（通知、附件、删除、自助注册、改密码等） |
| 宪章「安全与隐私」 | 凭据留在服务端、managed identity、验证输入、least privilege、不记录 secret 或客户内容 | 密码 BCrypt 哈希仅存服务端；数据库用 Entra 工作负载身份（无连接字符串密码）；服务端统一校验；URL + 服务层双重授权；日志只记录方法、路径模板、状态码、用户 id，不记录工单标题/描述/沟通内容/密码 |
| 宪章「测试与质量」 | 外部 integration 用真实测试服务验证；Mock 不能替代功能验收 | 集成测试与功能测试直连 `ticket-db` 的 `dev` 实例；不使用 H2/嵌入式库/Testcontainers 替身 |
| 宪章「交付治理」 | 可追踪 | 每个 TD、接口与测试均标注 FR/IQA 编号 |
| 技术基线：网页应用、无 App | NFR-002 | 纯浏览器访问的服务端渲染页面，无客户端安装 |
| 技术基线：Java 偏好 | 后端用 Java | Java 21 + Spring Boot |
| 技术基线：正规数据库、不存文件 | INT-020、NFR-001 | 全部业务数据存 PostgreSQL；容器文件系统不存任何业务数据 |
| 技术基线：测试阶段不对接公司登录系统 | INT-021、INT-022 | 系统内自建账号，单一角色字段区分三种身份 |
| 平台：根目录 Dockerfile、数字非 root UID | 候选构建与部署 | 多阶段 Dockerfile，运行阶段 `USER 10001` |
| 平台：healthPath 与 candidateIdentityPath | 候选部署校验 | `/actuator/health`、`/api/candidate-identity` |
| 平台：依赖只从 `AIDH_DEP_*` 读取 | 不硬编码终结点 | 数据源配置只引用 `AIDH_DEP_TICKET_DB_HOST/DATABASE/USER` |
| 平台：postgres 必须 `sslmode=require`、Entra 令牌约 1 小时过期 | 连接安全与令牌刷新 | JDBC URL 固定 `sslmode=require`；认证插件在每次新建物理连接时取令牌；Hikari `maxLifetime` 30 分钟轮换连接 |
| 平台：平台不执行迁移；库被多交付与候选共享；不得假设库为空 | 迁移与测试数据 | 应用启动时 Flyway 迁移（PostgreSQL advisory lock 串行、只增不删、忽略来自更新候选的 future 迁移）；测试以唯一运行前缀准备并清理数据 |
| 平台：Agent Pod 无 Docker 守护进程 | 测试方式 | 不用 Testcontainers；集成测试直连 `dev` 实例 |
| 平台：`requiresPrivateNetwork` 只能为 false | 部署 | 设为 false |
| 人工审批意见 HT-DLV-20261116-72F2B9041E74-0011、PQ-003 人工答案 A（FR-031、SC-007、AC-021） | 业务方无需任何人手工修改数据或配置即可获得初始管理员凭据；一次性流程，完成后永久不可用，不构成自助注册 | 登录入口的 `/setup` 流程（TD-011）；可用性只由应用所连数据库（TD-017）中是否已有可用管理员（“带凭据的 ADMIN”）决定，事务内 advisory lock 串行；无运维 Runbook、无配置项参与。此决定取代已批准技术基线中“初始管理员由启动时幂等引导逻辑创建”的实现方式（基线该条目的前提是 spec 未定义设置页面，现已由 FR-031 定义） |
| 平台：`test` 库由多个交付与候选共享，不得假设库为空；不得人工改库改配置，不为产品增加测试后门（FR-031、IQA-016、SC-007）；人工澄清答案 HT-DLV-20261116-72F2B9041E74-0014（DEC-TS-001 选 D） | 数据作用域就是应用配置所指向的数据库，生产与测试行为一致，作用域不依赖部署身份；首次设置在空库上的完整行为由开发阶段自动化测试覆盖；在已有管理员的环境中，候选验证“入口不可见、直接访问被拒、不能新增或接管账号” | TD-017：候选、QA、生产都使用依赖注入的数据库的默认 `public` schema，不读取 `AIDELIVERY_*` 决定数据位置；开放判定 = 该数据库中不存在可用管理员；首次设置完整流程由开发者集成/功能测试在一次性 schema（TD-016，真实 `dev` 实例）上验证；Independent QA 按 spec 修订后的 IQA-016 情形 ①/② 执行；无任何测试后门 |
| 人工审批意见 HT-DLV-20261116-72F2B9041E74-0018、HT-DLV-20261116-72F2B9041E74-0019（FR-031、SC-007、AC-021、IQA-016） | 设置初始管理员入口不得对公网匿名开放而无防抢占措施：从部署完成到首次设置之间，只有被授权的人能完成首次设置，生产环境同样适用；仍须满足“无需任何人手工改库或改配置” | TD-019：部署时由人在平台依赖与机密任务中提供一次性设置口令（应用机密 `INITIAL_SETUP_TOKEN`，注入为 `AIDH_SECRET_INITIAL_SETUP_TOKEN`），`POST /setup` 必须携带正确口令才会创建账号；口令未提供时流程不能完成（失败即关闭）；失败尝试按来源地址限流；口令只在内存中以 SHA-256 摘要保存并常量时间比较，不回显、不记录日志。提供机密属于平台受支持的部署流程，不是流程外的改库或改配置（spec FR-031、SC-007、AC-021 已写明“部署时提供设置口令属于部署流程，不算手工修改数据或配置”）。设置口令缺失或错误时拒绝提交、不创建账号且流程仍可用，与修订后的 FR-031、AC-021、IQA-016 一致 |
| 人工审批意见 HT-DLV-20261116-72F2B9041E74-0016（IQA-001、IQA-016、AC-001、AC-021） | 测试环境只有一个初始管理员，凭据由业务方人工提供，QA 与 UAT 共用；QA 不自备或插入管理员账号，员工与 IT 同事账号由该管理员通过产品功能创建；凭据交付方式由技术方案决定；凭据不得出现在证据明文中 | TD-018：在 `docs/dependencies.json` 声明 `test` 环境机密 `INITIAL_ADMIN_LOGIN_NAME`、`INITIAL_ADMIN_PASSWORD`、`INITIAL_ADMIN_DISPLAY_NAME`，由人在依赖与机密任务中提供，平台以 `AIDH_SECRET_*` 注入 QA；应用代码不读取这些变量，初始管理员只能经 `/setup`（情形 ①，由 QA 用这份凭据填写）进入系统；删除“QA 插入管理员种子”的数据准备方式 |
| QD-001 人工答案原文（经 HT-DLV-20261116-72F2B9041E74-0014 转达）：“V1 不验证升级或重新部署后的数据保留，改到后续迭代通过真实升级来验证”（IQA-015 / AC-020） | V1 Independent QA 不执行升级或重新部署后的数据保留验证（spec 已在 IQA-015、AC-020 与测试策略中写明） | 设计仍按 FR-029、FR-030、NFR-001 保证数据保留能力：迁移只增不删、可重复执行、启动不写入任何数据、`/setup` 在已有可用管理员时保持关闭；开发者集成测试提供迁移可重复执行与不删数据的证据；V1 验证矩阵不安排 IQA-015 的候选环境升级验证 |

## 架构概览（Architecture）

```
浏览器（员工 / IT 同事 / 管理员）
      │ HTTPS
      ▼
平台网关（TLS 终止，Gateway API）── HTTP ──► 容器 :8080（USER 10001）
                                             │
                          ┌──────────────────┴───────────────────┐
                          │ Spring Boot 单体（helpdesk.jar）        │
                          │  Security 过滤链：表单登录、CSRF、       │
                          │    RoleRefreshFilter（每请求按库刷新身份）│
                          │  Web 层（Thymeleaf 控制器）              │
                          │  服务层（授权 + 校验 + 事务）            │
                          │  数据访问层（NamedParameterJdbcTemplate） │
                          │  Flyway（启动迁移）  Actuator（health）   │
                          └──────────────────┬───────────────────┘
                                             │ JDBC + TLS(sslmode=require)
                                             │ 密码 = Entra 令牌（DefaultAzureCredential）
                                             ▼
                                  PostgreSQL 项目库（依赖 ticket-db）
```

- 所有页面由服务端渲染并以 Post/Redirect/Get 方式提交表单，筛选与搜索使用 GET 查询参数（可收藏、可直接访问）。
- 授权双层：Spring Security 按 URL 前缀与角色拦截；服务层对每个操作再次校验角色与数据归属（员工只能访问本人工单）。
- 时间计算统一使用注入的 `java.time.Clock` 与配置的公司时区，数据库存 `timestamptz`（UTC 瞬时）。

## 技术决策与替代方案

| 决策 ID | 选择 | 理由 | 已考虑替代方案 | 来源 |
|---|---|---|---|---|
| TD-001 | Java 21 + Spring Boot 3.5.x 单体，Spring MVC + Thymeleaf 服务端渲染 | 已批准基线方案 A；满足 Java 偏好与最简单架构；无前端工具链 | React SPA + REST（方案 B，复杂度高）；Quarkus（方案 C，无官方无密码插件） | NFR-002、技术基线 |
| TD-002 | 数据访问用 `NamedParameterJdbcTemplate` + 手写 SQL，不用 JPA | 搜索/筛选是动态 SQL，报表是聚合 SQL，行锁需显式 `FOR UPDATE`；手写 SQL 更直接可审查 | Spring Data JPA（动态查询与锁语义更隐式）；jOOQ（额外代码生成） | FR-015～FR-017、FR-022～FR-028 |
| TD-003 | PostgreSQL（平台 `ticket-db`），`azure-identity-extensions` 认证插件 + HikariCP（`maxLifetime=30m`） | 平台唯一可用数据库类型；插件在建立连接时用 `DefaultAzureCredential` 取令牌，连接轮换保证不使用过期令牌 | Spring Cloud Azure JDBC starter（引入更多自动配置，收益相同）；自写令牌刷新 DataSource | FR-029、FR-030、NFR-001 |
| TD-004 | Flyway 启动迁移；只增不删；`baselineOnMigrate=true`、`baselineVersion=0`；保留默认 `ignoreMigrationPatterns=*:future` | 平台不执行迁移；共享库上多个候选并存时，旧候选能容忍新候选已应用的迁移；advisory lock 避免并发迁移 | Liquibase（等价但更重）；手工 SQL（违背平台约束） | FR-030、NFR-001 |
| TD-005 | Spring Security 表单登录 + 服务端 HttpSession；BCrypt（strength 10）存储密码；CSRF 全局开启 | 凭据留在服务端；Session Cookie `HttpOnly`、`SameSite=Lax` | JWT（需客户端持有令牌，违背最简单与凭据留服务端原则） | FR-002、宪章安全条款 |
| TD-006 | 单一 `role` 字段（`EMPLOYEE`/`IT_STAFF`/`ADMIN`）；`RoleRefreshFilter` 每个已认证请求按用户 id 读库重建权限 | FR-004 要求身份变更"下一次操作即生效"；Session 中缓存的权限会过期，每请求一次主键查询成本可忽略 | 身份变更时强制注销目标会话（需会话注册表，复杂）；仅登录时读取（不满足 FR-004） | FR-003、FR-004、INV-006 |
| TD-007 | 关键词搜索 `title ILIKE :p OR description ILIKE :p`，`:p = '%' + escape(q) + '%'`，`ESCAPE '\'`；不使用数据库扩展 | 满足任意连续片段的部分匹配、英文不区分大小写（A-005）；V1 无性能数值要求 | `pg_trgm` 索引（需声明扩展，V1 不需要）；全文检索（分词导致中文片段匹配不可靠） | FR-022、FR-024 |
| TD-008 | 处理记录表 `ticket_event` 只追加；工单表冗余 `first_resolved_at`、`first_resolved_assignee_id`，仅在为 NULL 时写入 | 报表口径依赖"首次已解决时刻"与"该时刻处理人"（PQ-002 A），冗余列使报表查询简单且不可被后续变更改写 | 每次报表从事件表回放（查询复杂） | FR-020、FR-027、INV-005 |
| TD-009 | 公司时区配置项 `app.time-zone`，默认 `Asia/Shanghai`；自然周为 ISO 周（周一 00:00 至下周一 00:00，左闭右开） | 已批准技术基线给出默认时区；A-002 规定周一至周日 | 使用服务器默认时区（容器为 UTC，不符合公司本地时间） | FR-026～FR-028、A-002 |
| TD-010 | 注入 `java.time.Clock` Bean（生产为 `Clock.system(zone)`），所有"当前时刻"均取自该 Clock，不在 SQL 中用 `now()` | 报表与 72 小时边界可在测试中精确构造；测试可把时钟设到独立的历史周以隔离共享库中的其他数据 | SQL `now()`（不可测试） | FR-027、FR-028 |
| TD-011 | 一次性“设置初始管理员”流程（FR-031）：`InitialAdminSetupService.isSetupOpen()` = `NOT EXISTS(SELECT 1 FROM app_user WHERE role='ADMIN' AND password_hash IS NOT NULL)`，每次请求实时查询、不缓存；`GET /login` 在开放时显示“设置初始管理员”链接；`GET/POST /setup` 匿名可达（URL 层），关闭时一律 HTTP 403 渲染“初始管理员已设置”页且不读写任何账号；开放时 `POST /setup` 先按 TD-019 校验一次性设置口令与失败限流，口令不通过则不进入写事务；口令通过后在单个事务中先取 `pg_advisory_xact_lock(7243011)`、再次判定开放、校验输入（与 `POST /admin/users` 同一校验规则）、登录名唯一检查，最后 INSERT 一个 `role='ADMIN'`、BCrypt 哈希、`created_by=NULL` 的新账号；成功后 302 到 `/login?setup` 提示用该账号登录（不自动登录）。删除上一版的 `InitialAdminBootstrap`、`BootstrapProperties` 与 `app.bootstrap.*` 配置，启动时不再写入任何账号 | 满足“无需人工改数据或配置”（HT-0011）与 PQ-003 A；防抢占由 TD-019 的设置口令保证（HT-0018）；产品中不存在删除账号或修改管理员身份的功能（FR-004 只允许 EMPLOYEE↔IT_STAFF），因此一旦存在带凭据 ADMIN，判定永久为关闭，满足“永久不可用”；只能新建 ADMIN，不能修改任何既有行，满足“不能新增或接管任何账号”；判定范围就是应用所连数据库（TD-017），与生产行为一致；以“带凭据 ADMIN”而非“任何 ADMIN”判定，使上一版在共享 dev/test 库中留下的 `password_hash IS NULL` 引导账号（不可登录）不会把新环境永久锁住；不缓存判定，判定始终反映数据库当前状态 | 启动引导 + 运维 `UPDATE` 写入哈希（上一版，违背 HT-0011）；单独的 `system_setup` 完成标记表（需要额外迁移且共享库中 QA 无法在不改数据的前提下复验）；设置后自动登录（spec 要求“设置成功后即可用该凭据登录”，保留显式登录步骤更直接对应 IQA-016 第 2 步）；启动时生成随机密码打印日志（违反宪章“不记录 secret”） | FR-031、SC-007、AC-021、INT-021、HT-0011、HT-0018、PQ-003 A、宪章「安全与隐私」 |
| TD-012 | 列表分页：每页 50 条，按提交时间倒序、id 倒序；页码导航保留查询条件 | 工单持续增长，整表渲染不可控；分页不减少可见集合 | 无分页（数据增长后页面不可用） | FR-010、FR-014、FR-023 |
| TD-013 | 表单校验全部在服务端完成，表单加 `novalidate`，不使用 HTML `required` | 必须拒绝"仅含空白"的输入并逐项指出缺失项、保留已填内容；浏览器原生校验无法覆盖空白场景 | 仅浏览器校验（不可信、不满足 FR-007） | FR-007、FR-012、FR-018 |
| TD-014 | 员工访问他人工单或不存在的工单统一返回 HTTP 403 与同一"无权限访问该工单"页面 | 不泄露他人工单是否存在及任何内容 | 他人工单 403、不存在 404（可被用来探测编号） | FR-013、INV-003 |
| TD-015 | 功能测试用 Node `@playwright/test` 驱动 Pod 预装 Chromium；测试数据准备用 Node `pg` + `@azure/identity` 直连 `dev` 库 | Pod 预装 Node Playwright 与 Chromium；Java Playwright 需额外下载驱动 | Java Playwright；Selenium | IQA-001～IQA-016 的开发者对应验证 |
| TD-016 | 数据库 schema 可配置：应用属性 `app.db.schema`（默认 `public`，启动时校验匹配 `^[a-z][a-z0-9_]{0,62}$`，否则启动失败），同时作用于 JDBC `currentSchema` 与 Flyway `schemas`/`default-schema`。候选、QA、生产从不设置该属性，始终使用 `public`（TD-017）；只有开发者的“设置初始管理员”集成测试与功能测试以命令行/测试属性设为一次性 schema（`it_setup_<runId>`、`e2e_setup_<runId>`，由 Flyway 自动创建），测试结束以 `DROP SCHEMA <name> CASCADE` 清理。上一版 Flyway 已能在 `dev`/`test` 项目库中建表，说明使用方角色对项目库有建对象权限；Coding 在 `dev` 实例上先验证能创建并删除一次性 schema，若被拒绝，按平台规则以 `decisionsNeeded`（`dependency.name=ticket-db`）提出，不改用替身 | FR-031 的验证需要“尚无带凭据 ADMIN”的起始状态，而共享 `dev` 库的 `public` schema 中存在其他测试的种子管理员；一次性 schema 给出真实 PostgreSQL 上的干净状态，不删除他人数据、不使用替身；不改变产品行为 | 测试前删除库中全部 ADMIN（破坏共享数据）；只做单元测试（不满足真实集成验证）；为 `/setup` 增加测试专用开关（引入产品之外的行为） | FR-031、IQA-016、宪章「测试与质量」 |
| TD-017 | 数据作用域 = 应用配置所指向的数据库（人工澄清答案 HT-DLV-20261116-72F2B9041E74-0014，DEC-TS-001 选 D）：候选、QA、生产都连接平台注入的 `AIDH_DEP_TICKET_DB_*` 数据库，使用默认 `public` schema；不读取 `AIDELIVERY_DELIVERY_ID`、`AIDELIVERY_CANDIDATE_SHA` 或任何部署身份来决定 schema；`/setup` 开放判定 = 该数据库中是否已有可用管理员（TD-011 判定 SQL）。删除上一版设计中按部署身份派生 schema 的规则与 `/api/candidate-identity` 的 `dataSchema` 字段（未实现的不实现，已实现的删除）。`app.db.schema` 只保留给开发者测试（TD-016）。在共享 `test` 库中已有可用管理员（来自其他交付、候选或业务方 UAT）是预期状态：此时候选按修订后的 IQA-016 验证“入口不可见、直接访问被拒、不能新增或接管账号”；首次设置在空库上的完整行为由开发者集成与功能测试在真实 `dev` 实例的一次性 schema 中覆盖 | 人选定：生产与测试行为必须一致，作用域不能依赖部署身份；FR-031“设置成功后永久不可用”按数据库语义完全保持；无测试后门；架构最简单 | 按候选版本或交付派生 schema（DEC-TS-001 A/B，人未选）；每候选独立数据库实例（C，人未选，平台不提供）；QA 删除他人管理员以重新开放（破坏共享数据、违反平台规则） | FR-031、FR-030、SC-007、IQA-015、IQA-016、HT-0014 |
| TD-018 | 测试环境初始管理员凭据交付（人工审批意见 HT-DLV-20261116-72F2B9041E74-0016）：在 `docs/dependencies.json` 顶层 `secrets` 声明 `INITIAL_ADMIN_LOGIN_NAME`、`INITIAL_ADMIN_PASSWORD`、`INITIAL_ADMIN_DISPLAY_NAME`，`environments=["test"]`、`required=true`；值由业务方在平台依赖与机密任务中提供，平台注入为 `AIDH_SECRET_INITIAL_ADMIN_LOGIN_NAME` 等，供 Independent QA 使用（业务方 UAT 使用自己提供的同一份凭据）。应用（`src/main/**`）不读取 `AIDH_SECRET_INITIAL_ADMIN_*`（唯一读取的机密是 TD-019 的 `AIDH_SECRET_INITIAL_SETUP_TOKEN`），即使候选容器中存在这些变量也不据此创建或修改账号：初始管理员只能经 `/setup` 进入系统（TD-011）。QA 情形 ① 用这份凭据与 `AIDH_SECRET_INITIAL_SETUP_TOKEN` 填写 `/setup`，情形 ② 用它登录；其他场景的员工与 IT 同事账号由该管理员经账号管理页面创建与设置（FR-001、FR-004）；QA 不以 SQL 写入任何 `app_user` 行。开发者测试（`dev`）不使用这些机密，仍自行生成合成管理员 | 人要求凭据由业务方提供并由 QA 与 UAT 共用，交付方式由技术方案决定；平台 `secrets` 是人向 QA 提供值的唯一受支持通道，无需流程外传递，不落入仓库、日志或证据；应用不读取，保证“无需改数据或配置”与 `/setup` 一次性语义不变 | 应用启动时用机密自动创建管理员（违反 FR-031 一次性设置流程与 HT-0011，且使情形 ① 不可验证）；把凭据写入仓库或 QA 计划（泄露机密）；QA 插入管理员种子（HT-0016 禁止） | FR-001、FR-004、FR-031、AC-001、AC-021、IQA-001、IQA-016、HT-0016、宪章「安全与隐私」 |
| TD-019 | 一次性设置口令防抢占（人工审批意见 HT-DLV-20261116-72F2B9041E74-0018、HT-DLV-20261116-72F2B9041E74-0019；spec FR-031、SC-007、AC-021）：在 `docs/dependencies.json` 顶层 `secrets` 声明 `INITIAL_SETUP_TOKEN`（`environments=["test"]`、`required=true`），值由人在平台依赖与机密任务中提供并注入为 `AIDH_SECRET_INITIAL_SETUP_TOKEN`；生产部署以同一机密名提供各自的值。应用属性 `app.setup.token`（默认 `${AIDH_SECRET_INITIAL_SETUP_TOKEN:}`）在启动时由 `SetupTokenVerifier` 读取：去首尾空白后长度 ≥ 16 视为“已配置”，只保存其 SHA-256 摘要，原值不保留在字段中；为空或过短视为“未配置”，记录一次 WARN“未提供有效的设置口令，设置初始管理员流程无法完成”（不输出值或长度）。`/setup` 表单增加必填密码型字段“设置口令”（`setupToken`，`autocomplete=off`，任何情况下不回填）。`POST /setup` 判定顺序：① 关闭 → 403（与 TD-011 相同，不校验口令）；② 来源地址失败限流已触发 → HTTP 429 重渲染表单并提示“尝试次数过多，请 15 分钟后再试”，不写库；③ 未配置口令 → 200 重渲染，`setupToken` 字段错误“此部署未提供设置口令，暂不能完成设置”，不写库；④ 口令为空 → “请填写设置口令”；与已配置摘要不等（对提交值做 SHA-256 后 `MessageDigest.isEqual` 常量时间比较）→ “设置口令不正确”；③④ 均计一次失败、不写库、回填登录名与姓名、清空密码与口令；⑤ 口令正确后才进入 TD-011 的事务（advisory lock、再次判定开放、字段校验、唯一检查、INSERT）。失败限流 `SetupAttemptLimiter`：内存中按 `HttpServletRequest.getRemoteAddr()`（经 `server.forward-headers-strategy=native` 处理网关转发头后的客户端地址）计数，15 分钟滑动窗口内失败 5 次后该地址在窗口剩余时间内一律 429；表最多 10000 个地址，超出时淘汰最早的条目；进程重启清零。口令正确后的字段校验失败不计入失败次数。日志：口令失败记录 WARN“设置初始管理员口令校验失败”，限流触发记录 WARN“设置初始管理员尝试次数超限”，均不记录口令、登录名、姓名、密码或来源地址。设置完成后流程永久关闭（TD-011），口令随之失去作用，无需轮换或删除；GET `/setup`、登录页链接的显示规则不变（仍只由 TD-011 的开放判定决定） | 人要求只有被授权的人能完成首次设置且不需要手工改库或改配置；平台 `secrets` 是部署时由人提供值的受支持通道，持有口令即被授权；与 FR-031 的“登录入口一次性流程”完全兼容，开放判定与“永久关闭”语义不变；失败即关闭（未提供口令时无人能完成设置），不会因配置遗漏退化为匿名开放；常量时间比较与限流使在线猜测不可行 | 只在部署后限定时间内开放（窗口内仍可被抢占，候选重启会重置窗口，业务方错过窗口后需重新部署）；只允许特定来源 IP（平台网关后来源地址不稳定，且需要人维护地址配置）；启动时生成口令打印到日志（违反宪章“不记录 secret”）；应用直接用 `INITIAL_ADMIN_*` 机密创建管理员（违反 FR-031 一次性流程，见 TD-018） | FR-031、SC-007、AC-021、IQA-016、HT-0018、HT-0019、宪章「安全与隐私」 |

## 领域与数据模型

### 枚举（数据库存英文码，页面显示中文）

| 枚举 | 码值 → 显示 |
|---|---|
| `Role` | `EMPLOYEE` → 员工；`IT_STAFF` → IT 同事；`ADMIN` → 管理员 |
| `TicketStatus` | `OPEN` → 待处理；`IN_PROGRESS` → 处理中；`RESOLVED` → 已解决；`CLOSED` → 已关闭 |
| `Urgency` | `LOW` → 低；`MEDIUM` → 中；`HIGH` → 高（PQ-001 A） |
| `EventType` | `CLAIM` → 认领；`ASSIGN` → 分派；`STATUS` → 状态修改 |

### 表结构（迁移 `V1__init.sql`）

```sql
CREATE TABLE app_user (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  login_name    VARCHAR(64)  NOT NULL UNIQUE,
  password_hash VARCHAR(100) NULL,      -- NULL 仅允许出现在启动引导创建、尚未设置首个凭据的 ADMIN 账号上；NULL 视为禁用
  display_name  VARCHAR(100) NOT NULL,
  role          VARCHAR(16)  NOT NULL CHECK (role IN ('EMPLOYEE','IT_STAFF','ADMIN')),
  created_at    TIMESTAMPTZ  NOT NULL,
  created_by    BIGINT NULL REFERENCES app_user(id),
  CONSTRAINT ck_app_user_password CHECK (password_hash IS NOT NULL OR role = 'ADMIN')
);

CREATE TABLE category (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  name        VARCHAR(50) NOT NULL,
  active      BOOLEAN     NOT NULL DEFAULT TRUE,
  created_at  TIMESTAMPTZ NOT NULL,
  removed_at  TIMESTAMPTZ NULL
);
CREATE UNIQUE INDEX ux_category_active_name ON category(name) WHERE active;

CREATE TABLE ticket (
  id                          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  title                       VARCHAR(200) NOT NULL,
  description                 TEXT         NOT NULL,
  category_id                 BIGINT       NOT NULL REFERENCES category(id),
  urgency                     VARCHAR(8)   NOT NULL CHECK (urgency IN ('LOW','MEDIUM','HIGH')),
  submitter_id                BIGINT       NOT NULL REFERENCES app_user(id),
  submitted_at                TIMESTAMPTZ  NOT NULL,
  status                      VARCHAR(16)  NOT NULL CHECK (status IN ('OPEN','IN_PROGRESS','RESOLVED','CLOSED')),
  assignee_id                 BIGINT       NULL REFERENCES app_user(id),
  first_resolved_at           TIMESTAMPTZ  NULL,
  first_resolved_assignee_id  BIGINT       NULL REFERENCES app_user(id),
  updated_at                  TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_ticket_submitter ON ticket(submitter_id, submitted_at DESC);
CREATE INDEX ix_ticket_submitted ON ticket(submitted_at DESC);
CREATE INDEX ix_ticket_status    ON ticket(status);
CREATE INDEX ix_ticket_category  ON ticket(category_id);
CREATE INDEX ix_ticket_assignee  ON ticket(assignee_id);
CREATE INDEX ix_ticket_first_resolved ON ticket(first_resolved_at);

CREATE TABLE ticket_comment (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  ticket_id   BIGINT      NOT NULL REFERENCES ticket(id),
  author_id   BIGINT      NOT NULL REFERENCES app_user(id),
  author_role VARCHAR(16) NOT NULL CHECK (author_role IN ('EMPLOYEE','IT_STAFF')),
  body        TEXT        NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_comment_ticket ON ticket_comment(ticket_id, created_at, id);

CREATE TABLE ticket_event (
  id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  ticket_id         BIGINT      NOT NULL REFERENCES ticket(id),
  actor_id          BIGINT      NOT NULL REFERENCES app_user(id),
  event_type        VARCHAR(16) NOT NULL CHECK (event_type IN ('CLAIM','ASSIGN','STATUS')),
  from_assignee_id  BIGINT NULL REFERENCES app_user(id),
  to_assignee_id    BIGINT NULL REFERENCES app_user(id),
  from_status       VARCHAR(16) NULL,
  to_status         VARCHAR(16) NULL,
  created_at        TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_event_ticket ON ticket_event(ticket_id, created_at, id);
```

### 字段规则与不变量映射

- 工单编号 = `ticket.id`，页面显示为 `#<id>`，URL 为 `/tickets/<id>`（FR-008、INV-004）。
- 提交后 `title`、`description`、`category_id`、`urgency`、`submitter_id`、`submitted_at` 无任何更新语句（FR-009、INV-004）；类别改名只改 `category.name`，工单通过外键显示新名称（FR-006）。
- 类别"移除"= `active=false, removed_at=<now>`（软删除），历史工单保留外键（FR-006）；有效类别名唯一由部分唯一索引保证（INV-007）。名称比较为去除首尾空白后的精确匹配。
- `ticket_comment`、`ticket_event` 在应用中只有 INSERT 与 SELECT（INV-005、FR-020）。`author_role` 记录写入时身份（FR-019）。
- `first_resolved_at` / `first_resolved_assignee_id` 只在状态改为 `RESOLVED` 且 `first_resolved_at IS NULL` 时写入，之后不再改变（INV-005、FR-027）；`first_resolved_assignee_id` 为该时刻的 `assignee_id`（可为 NULL，计入"未分配"）。
- 账号凭据：通过页面（`/admin/users` 或 `/setup`）创建的账号必须有 BCrypt 哈希；应用不再写入 `password_hash IS NULL` 的行。上一版启动引导在共享 `dev`/`test` 库中留下的 NULL 哈希 ADMIN 行保持原样（迁移只增不删，不新增迁移），`AppUserDetailsService` 对其继续返回 `enabled=false`，登录失败提示与凭据错误完全相同（FR-002）；这类行不计为“带凭据 ADMIN”，不影响 `/setup` 的开放判定，`/setup` 也不会修改它们（其登录名被占用时按“登录名已存在”拒绝）。
- 处理人：`assignee_id` 只能在认领/分派时写入，且写入时目标账号 `role='IT_STAFF'`（INV-002）；身份取消后不清空（FR-004）。
- 技术长度上限（数据库列宽要求）：登录名 1～64 字符、显示姓名 1～100、初始密码 1～72 字节（BCrypt 上限）、类别名 1～50、标题 1～200、描述/补充说明/回复 1～10000 字符；超限时拒绝并提示"<字段>不能超过 N 个字符"，保留已填内容。所有文本先去除首尾空白再判断是否为空。

## Component 边界与项目结构

Coding 可写的仓库路径（唯一授权来源）：

- `src/main/java/`
- `src/main/resources/`
- `src/test/java/`
- `src/test/resources/`
- `e2e/`
- `config/checkstyle/`
- `.gitignore`

说明：根目录 `pom.xml`、`Dockerfile`、`.dockerignore` 属平台固定授权的根目录包管理与构建文件；`.ai-delivery/features/001-v1/runtime/deployment.json` 与 `.ai-delivery/features/001-v1/runtime/developer-functional-run.sh` 由 Coding 按平台授权编写；`.ai-delivery/features/001-v1/runtime/qa-run.sh` 只由 Independent QA 编写，Coding 不编写、不预置其任何内容。

目录树（阅读用）：

```
pom.xml                                   # 平台授权；Spring Boot parent、checkstyle、surefire、failsafe(-Pintegration)
Dockerfile / .dockerignore                # 平台授权
.gitignore                                # target/、e2e/node_modules/、e2e/test-results/、e2e/playwright-report/
config/checkstyle/checkstyle.xml          # 精简规则：UnusedImports、AvoidStarImport、禁止 System.out/err 与 printStackTrace（RegexpSinglelineJava）
src/main/java/com/ithelpdesk/
  HelpdeskApplication.java
  config/      AppProperties(app.time-zone、app.db.schema、app.setup.token)、ClockConfig、SecurityConfig、WebMvcConfig
  auth/        AppUserDetailsService、CurrentUser(principal: id/loginName/displayName/role)、RoleRefreshFilter、LoginController、HomeController
  user/        Role、AppUser、UserRepository、UserAdminService、UserAdminController、InitialAdminSetupService、InitialAdminSetupController、SetupForm、SetupTokenVerifier、SetupAttemptLimiter（TD-019）（删除 InitialAdminBootstrap、BootstrapProperties）
  category/    Category、CategoryRepository、CategoryService、CategoryAdminController
  ticket/      TicketStatus、Urgency、EventType、Ticket、TicketComment、TicketEvent、TicketRepository、
               TicketCommentRepository、TicketEventRepository、TicketSearchCriteria、TicketQueryService、
               TicketCommandService、TicketController、TicketForm、CommentForm
  report/      WeekRange、WeeklyReport、WeeklyReportService、WeeklyReportRepository、ReportController
  platform/    CandidateIdentityController
  web/         AccessDeniedException、BadRequestException、GlobalExceptionHandler、RequestLogFilter、TimeFormat
src/main/resources/
  application.yml
  db/migration/V1__init.sql
  templates/   layout.html、login.html、error/403.html、error/400.html、error/500.html、
               tickets/list.html、tickets/new.html、tickets/detail.html、
               admin/users.html、admin/categories.html、admin/report.html、setup/form.html、setup/closed.html
  static/css/app.css
src/test/java/com/ithelpdesk/            # *Test.java 单元/切片测试；*IT.java 连接 dev 库的集成测试；support/ 测试数据工具
src/test/resources/application-it.yml    # 集成测试 profile（可变测试时钟等）
e2e/
  package.json / package-lock.json       # @playwright/test（与 Pod 预装版本一致）、pg、@azure/identity、bcryptjs
  playwright.config.ts                   # baseURL=http://localhost:8080，Chromium，headless
  support/db.ts                          # Entra 令牌连库（端口取 AIDH_DEP_TICKET_DB_PORT）；种子管理员；按运行前缀清理
  support/fixtures.ts
  tests/*.spec.ts                        # 对应 IQA-001～IQA-014 的开发者功能流程（baseURL localhost:8080，public schema）
  tests/iqa-016-initial-admin.spec.ts    # IQA-016 对应流程（含设置口令缺失/错误被拒、限流）；独立 Playwright project，baseURL=http://localhost:8081（一次性 schema 实例，合成设置口令）
  support/schema.ts                      # 一次性 schema 的 DROP SCHEMA ... CASCADE 清理
```

组件边界规则：

- 控制器只做参数绑定、调用服务、选择视图；不写 SQL、不做授权判断以外的业务逻辑。
- 服务层（`*Service`）承担授权（基于 `CurrentUser`）、校验与事务边界（`@Transactional`）；所有写操作只在服务层发生。
- 仓库层（`*Repository`）只含 SQL；动态筛选 SQL 只在 `TicketRepository.search` 中拼装，所有值使用命名参数绑定，禁止字符串拼接用户输入。
- `report` 包只读，不调用写操作。

## 数据流、状态、并发与生命周期

### 关键流程

0. **设置初始管理员**（`InitialAdminSetupController` + `InitialAdminSetupService`，FR-031）：
   - `GET /setup`：`isSetupOpen()` 为假 → 403 + `setup/closed.html`；为真 → 渲染空表单（设置口令、登录名、密码、显示姓名）；未配置口令时表单顶部另显示提示“此部署未提供设置口令，暂不能完成设置”（`setup-token-missing`）。
   - `POST /setup` 前置（TD-019，不开启写事务）：`isSetupOpen()` 为假 → 403 + `setup/closed.html`；`SetupAttemptLimiter` 已对来源地址触发 → 429 重渲染表单（提示“尝试次数过多，请 15 分钟后再试”）；口令未配置、为空或不正确 → 200 重渲染表单、`setupToken` 字段错误、记一次失败、不写库（回填登录名与姓名，清空密码与口令）。
   - `POST /setup`（口令通过后）：开启事务 → `SELECT pg_advisory_xact_lock(7243011)` → 再次 `isSetupOpen()`，为假则回滚并 403 + `setup/closed.html`（并发第二个提交者也落入此分支）→ 校验（去首尾空白后判空、长度上限同“字段规则”，错误信息“请填写登录名”“请填写密码”“请填写姓名”等）失败则 200 重渲染表单、回填登录名与姓名、清空密码，不写库 → `login_name` 已存在则字段错误“登录名已存在”，不写库 → `INSERT INTO app_user(login_name,password_hash,display_name,role,created_at,created_by) VALUES(:login,:bcrypt,:display,'ADMIN',:now,NULL)`（唯一冲突兜底同样转为“登录名已存在”）→ 提交后 302 `/login?setup`。
   - 日志：成功记录 INFO“初始管理员设置完成（用户 id=<id>）”；关闭状态下的请求记录 INFO“设置初始管理员入口已关闭，请求被拒绝”；口令失败与限流按 TD-019 记录 WARN；不记录设置口令、登录名、姓名、密码、哈希或来源地址。
1. **登录**：`GET /login` 同时调用 `isSetupOpen()` 决定是否显示“设置初始管理员”链接；`POST /login` → `AppUserDetailsService` 按 `login_name` 精确查询（`password_hash` 为 NULL 的遗留行返回禁用用户）→ BCrypt 校验 → 成功则会话固定攻击防护（新建 Session ID）并跳转 `/`；失败统一跳转 `/login?error`，显示"登录名或密码错误"（FR-002）。
2. **每个已认证请求**：`RoleRefreshFilter`（位于 Security 过滤链 `AuthorizationFilter` 之前）按 Session 中的用户 id 读取 `app_user`；用户不存在则使会话失效并重定向登录；`role` 与 Session 中不同则用新角色重建 `Authentication` 并写回 SecurityContext（FR-004 "下一次操作即生效"）。
3. **提交工单**（员工）：校验 → 在事务中确认 `category.active=true`（并发移除时拒绝"所选类别已不可用"）→ INSERT `ticket`（`status=OPEN`、`assignee_id=NULL`、`submitted_at=clock.instant()`）→ 重定向详情页并提示"工单已提交"（FR-007、FR-008）。
4. **补充说明 / 回复**：校验内容 → 员工须为提交人、IT 同事不限 → INSERT `ticket_comment`，不触碰 `ticket.status/assignee_id`（FR-012、FR-018）。
5. **认领 / 分派 / 改状态**（IT 同事）：事务内 `SELECT ... FROM ticket WHERE id=? FOR UPDATE` 锁定工单行；
   - 认领：若 `assignee_id` 已是本人则提示"您已是该工单处理人"，不写记录；否则更新 `assignee_id` 并 INSERT `CLAIM` 事件（前值、后值）。
   - 分派：`SELECT role FROM app_user WHERE id=? FOR SHARE`，非 `IT_STAFF` 或为本人则拒绝"分派对象必须是其他 IT 同事"，工单不变；目标与当前处理人相同则提示无变化；否则更新并 INSERT `ASSIGN` 事件。
   - 改状态：值与当前相同则提示"状态未变化"且不写记录（FR-017）；否则更新 `status`，若新值为 `RESOLVED` 且 `first_resolved_at IS NULL`，同时写入 `first_resolved_at=now`、`first_resolved_assignee_id=assignee_id`；INSERT `STATUS` 事件。
   - 全部写入在同一事务中提交；任一失败整体回滚并显示"操作失败，请重试"（spec 错误表）。
6. **身份变更**（管理员）：仅允许 `EMPLOYEE ↔ IT_STAFF`；`UPDATE app_user SET role=? WHERE id=? AND role IN ('EMPLOYEE','IT_STAFF')`；管理员账号身份不可在页面修改（spec 未授予）。
7. **类别维护**：新增/改名时去空白、判空、在事务中检查有效同名（并以部分唯一索引兜底，唯一冲突转为"类别名称已存在"）；移除为软删除。
8. **周报表**：见下文"报表计算"。

### 搜索与筛选

`TicketSearchCriteria`：`q`（关键词）、`status`、`categoryId`、`assignee`（用户 id 或字面值 `unassigned`）、`from`、`to`（`yyyy-MM-dd`，公司时区日期，含首尾两日：`submitted_at >= from 00:00` 且 `< (to+1) 00:00`）、`page`（从 1 开始）。

SQL 结构：`WHERE 1=1` + 可见范围（员工：`submitter_id = :currentUserId`；IT 同事：无限制）+ 各条件 `AND`；关键词转义 `\`、`%`、`_` 后 `ILIKE`；`ORDER BY submitted_at DESC, id DESC LIMIT 51 OFFSET :offset`（多取 1 条判断是否有下一页）。空结果显示"无匹配工单"（FR-024）；`from > to` 显示"开始日期不能晚于结束日期"且不返回结果；参数无法解析时返回 400 页面"请求参数无效"。

筛选下拉选项：状态四项；类别 = 全部类别（已移除的标注"（已移除）"）；处理人 = "未分配" + 当前 IT 同事 + 曾作为处理人出现过的账号（去重，按姓名排序）。

### 报表计算（`WeeklyReportService`）

- `now = clock.instant()`；`zone = app.time-zone`；`weekStart = now` 所在日期回退到周一 00:00（zone）；`weekEnd = weekStart + 7 天`（左闭右开），页面显示"YYYY-MM-DD（周一）至 YYYY-MM-DD（周日）"（FR-026）。
- **每个类别的工单数量**：`submitted_at ∈ [weekStart, weekEnd)` 按 `category_id` 计数；行集合 = 全部有效类别（无工单为 0）∪ 本周有工单的已移除类别（标注"（已移除）"），按名称排序（FR-027）。
- **每位 IT 同事处理量**：`first_resolved_at ∈ [weekStart, weekEnd)` 按 `first_resolved_assignee_id` 计数；行集合 = 当前全部 IT 同事（无则 0）∪ 本周有计数的其他账号（如已被取消 IT 同事身份）∪ 固定一行"未分配"（`first_resolved_assignee_id IS NULL`，无则 0）（FR-027、PQ-002 A）。
- **平均处理时长**：同一工单集合 `AVG(EXTRACT(EPOCH FROM first_resolved_at - submitted_at))`；无工单显示"无数据"；有值时四舍五入到分钟，显示为"`H` 小时 `M` 分钟"（`H` 为总小时数，可超过 24），元素同时带 `data-avg-seconds`（四舍五入到整秒）供自动化核对（FR-027）。
- **超过 3 天未解决**：`status IN ('OPEN','IN_PROGRESS') AND submitted_at < now - interval '72 hours'`（严格小于，恰好 72 小时不计入），不受本周限制（FR-028）。
- 每次打开报表实时计算，无缓存。

### 并发与生命周期

- 工单写操作以行锁串行化；同一工单的并发认领/分派以"后提交者生效"，每次变更都有完整前后值记录。
- 应用无状态于本地文件；HttpSession 在内存中，重新部署后用户需重新登录（数据不受影响）。
- 启动顺序：读取配置（校验 `app.db.schema`，未设置时为 `public`）→ 建连接池 → Flyway 迁移（advisory lock）→ 启动 Web；启动阶段不写入任何业务数据或账号；迁移失败则进程退出（非零），健康检查不通过，候选部署失败而非带病运行。

## Interface 与契约（Contract）

### HTTP 路由（全部为服务端渲染页面，除标注外）

| 方法与路径 | 允许身份 | 说明 | 追踪 |
|---|---|---|---|
| `GET /login` | 匿名 | 登录页（登录名、密码、登录按钮；无注册入口）；`isSetupOpen()` 为真时另显示“设置初始管理员”链接（指向 `/setup`），为假时不显示；`?setup` 时显示“初始管理员已设置，请使用该账号登录” | FR-001、FR-002、FR-031 |
| `GET /setup` | 匿名（仅开放时） | 开放：设置初始管理员表单（设置口令、登录名、密码、显示姓名）；关闭：403 + “系统已完成初始管理员设置，此入口不可用”及返回登录链接 | FR-031、AC-021 |
| `POST /setup` | 匿名（仅开放时）且须持有部署提供的设置口令 | `setupToken`、`loginName`、`password`、`displayName`、`_csrf`；成功 → `/login?setup`；关闭 → 403；来源地址失败超限 → 429；口令未配置/为空/不正确或字段校验失败 → 200 重渲染；除成功外均不创建或修改任何账号 | FR-031、AC-021、HT-0018（TD-019） |
| `POST /login` | 匿名 | 参数 `username`、`password`、`_csrf`；失败 → `/login?error` | FR-002 |
| `POST /logout` | 已登录 | 注销 → `/login?logout` | FR-002 |
| `GET /` | 已登录 | 员工/IT 同事 → `/tickets`；管理员 → `/admin/users` | FR-003 |
| `GET /tickets` | 员工、IT 同事 | 列表 + 搜索 + 筛选 + 分页；员工标题"我的工单"，IT 同事"全部工单" | FR-010、FR-014、FR-022～FR-024 |
| `GET /tickets/new` | 员工 | 提交表单；无有效类别时显示"暂无可选类别，请联系管理员"且无提交按钮 | FR-007、前置条件 |
| `POST /tickets` | 员工 | `title`、`description`、`categoryId`、`urgency` | FR-007、FR-008 |
| `GET /tickets/{id}` | 员工（仅本人）、IT 同事 | 详情：提交信息、状态、处理人、沟通记录、处理记录；IT 同事另见认领/分派/状态表单 | FR-011、FR-013、FR-019～FR-021 |
| `POST /tickets/{id}/comments` | 员工（仅本人）、IT 同事 | `body` | FR-012、FR-018 |
| `POST /tickets/{id}/claim` | IT 同事 | 认领 | FR-015 |
| `POST /tickets/{id}/assign` | IT 同事 | `assigneeId` | FR-016 |
| `POST /tickets/{id}/status` | IT 同事 | `status` ∈ 四种码值 | FR-017 |
| `GET /admin/users` | 管理员 | 账号列表 + 创建表单 + 身份切换 | FR-001、FR-004 |
| `POST /admin/users` | 管理员 | `loginName`、`password`、`displayName`、`role` | FR-001 |
| `POST /admin/users/{id}/role` | 管理员 | `role` ∈ `EMPLOYEE`/`IT_STAFF` | FR-004 |
| `GET /admin/categories` | 管理员 | 类别列表（有效与已移除）+ 新增表单 | FR-005 |
| `POST /admin/categories` | 管理员 | `name` | FR-005 |
| `POST /admin/categories/{id}/rename` | 管理员 | `name` | FR-005、FR-006 |
| `POST /admin/categories/{id}/remove` | 管理员 | 软删除 | FR-006 |
| `GET /admin/reports/weekly` | 管理员 | 周报表 | FR-025～FR-028 |
| `GET /actuator/health` | 匿名 | 平台 healthPath；返回 `{"status":"UP"}`，不暴露详情 | 平台 |
| `GET /api/candidate-identity` | 匿名 | JSON：`{"deliveryId": $AIDELIVERY_DELIVERY_ID, "candidateSha": $AIDELIVERY_CANDIDATE_SHA}`；变量缺失时为空字符串 | 平台 |

### 统一行为契约

- 未登录访问任何非匿名路径：页面请求 302 到 `/login`（FR-002）。
- 已登录但身份不符（URL 层或服务层）：HTTP 403，渲染 `error/403.html`，正文"您无权访问该页面或执行该操作"，不含任何业务数据（FR-013、FR-021、FR-025）；员工访问他人或不存在的工单同样 403（TD-014）。
- 缺少或无效 CSRF 令牌的 POST：403。
- 校验失败：HTTP 200 重新渲染原表单，逐字段错误信息（如"请填写标题""请选择类别""请选择紧急程度""内容不能为空""登录名已存在""类别名称已存在"），已填内容回填（密码字段除外）。
- 成功写操作：302 重定向（PRG）+ 一次性提示（flash）"已保存"类文本。
- 未预期错误：HTTP 500，`error/500.html`"操作失败，请重试"，事务回滚。
- `/setup` 页面不显示任何身份导航；表单只能创建管理员，没有身份选择项（不构成自助注册，INT-021）。
- 页面导航按身份渲染：员工"我的工单""提交工单""退出"；IT 同事"全部工单""退出"；管理员"账号管理""类别管理""周报表""退出"（FR-003、AC-003）。

### 页面自动化定位约定（`data-testid`，供开发者功能测试与 Independent QA 使用）

`nav-my-tickets`、`nav-all-tickets`、`nav-new-ticket`、`nav-admin-users`、`nav-admin-categories`、`nav-report`、`logout`；`login-username`、`login-password`、`login-submit`、`login-error`；`setup-link`（登录页上的入口链接）、`setup-token`（设置口令输入框）、`setup-token-missing`（未配置口令提示）、`setup-throttled`（限流提示）、`setup-login-name`、`setup-password`、`setup-display-name`、`setup-submit`、`setup-closed`（关闭页正文）、`setup-success`（登录页 `?setup` 提示）；设置表单的字段错误沿用 `field-error-<字段名>`（口令错误为 `field-error-setupToken`）；`ticket-row`（行上带 `data-ticket-id`）、`ticket-empty`（"无匹配工单"）、`filter-q`、`filter-status`、`filter-category`、`filter-assignee`、`filter-from`、`filter-to`、`filter-submit`、`filter-clear`、`page-next`、`page-prev`；`ticket-title`、`ticket-description`、`ticket-category`、`ticket-urgency`、`ticket-submit`、`field-error-<字段名>`；`ticket-id`、`ticket-status`、`ticket-assignee`、`comment-item`、`event-item`、`comment-body`、`comment-submit`、`claim-button`、`assign-select`、`assign-submit`、`status-select`、`status-submit`；`user-row`、`category-row`、`report-week`、`report-category-row`、`report-staff-row`、`report-unassigned`、`report-avg`、`report-backlog`；`forbidden`、`flash`。

### 数据库数据契约（供测试准备数据）

开发者测试与 Independent QA 可以使用各自环境身份直连 `ticket-db`，按上文表结构在应用所用的 `public` schema 中准备合成数据并按前缀清理（开发者的一次性 schema 只用于 TD-016 所列测试）；Independent QA 不向 `app_user` 写入任何行（HT-0016、TD-018）：

- 管理员种子（仅开发者测试在 `dev` 库 `public` schema 中使用）：`INSERT INTO app_user(login_name,password_hash,display_name,role,created_at) VALUES(<唯一前缀登录名>, <BCrypt 哈希，$2a$/$2b$/$2y$ 均可>, <姓名>, 'ADMIN', now())`。断言账号列表时只针对本次运行创建的账号；遗留的 NULL 哈希 ADMIN 行不得修改或依赖。
- “设置初始管理员”开放判定：应用所连数据库（`public` schema）中不存在 `role='ADMIN' AND password_hash IS NOT NULL` 的行（TD-011、TD-017）。共享 `dev`/`test` 库中可能已有其他交付、候选、开发者测试或业务方 UAT 创建的可用管理员，这是预期状态，测试不得删除或修改他人账号以改变判定。
- IQA-016 在候选环境中的前置状态与执行方式（spec IQA-016，HT-0014、HT-0016）：QA 最先执行 IQA-016，先读取登录页判断入口是否显示——① 尚无可用管理员（首次部署到空库）时，验证首次设置完整流程，正确设置口令一律取 `AIDH_SECRET_INITIAL_SETUP_TOKEN`（TD-019，即部署时由人提供、经平台机密交给 QA 的同一口令），按 spec IQA-016 顺序：先填写正确口令、留空登录名提交一次（字段校验被拒，不计入失败次数）；再以 `AIDH_SECRET_INITIAL_ADMIN_LOGIN_NAME`、`AIDH_SECRET_INITIAL_ADMIN_PASSWORD`、`AIDH_SECRET_INITIAL_ADMIN_DISPLAY_NAME` 分别在不填写口令、填写错误合成口令时各提交一次（均 200 重渲染、`setupToken` 字段错误、不创建账号、流程仍可用，共计 2 次失败，低于限流阈值 5 次，不会触发 429）；最后以同样信息与正确口令完成设置；覆盖尝试使用合成信息；② 已有可用管理员时，验证登录页不显示入口、`GET /setup` 与 `POST /setup`（一组合成凭据，以及以 `AIDH_SECRET_INITIAL_ADMIN_LOGIN_NAME` 为登录名的提交，可携带或不携带设置口令）均得 403、合成凭据登录失败、账号数据无新增或修改（QA 可直连 `test` 库只读核对 `app_user` 行数与该登录名行的 `password_hash`、`role`、`display_name` 未变）、用机密中的凭据仍可登录。QA 不得为获得任一状态改库或改配置。所设初始管理员是测试环境长期保留的唯一初始管理员，QA 与 UAT 共用，QA 不清理该账号。
- 其他场景的账号：员工与 IT 同事账号由 QA 以初始管理员身份在账号管理页面创建（登录名带本次运行前缀），身份变更同样经页面完成；IQA-001 中“创建管理员账号”一步同样经页面完成。产品不提供删除账号功能，这些前缀账号保留在 `test` 库中，后续运行以新前缀区分；QA 不以 SQL 删除或修改 `app_user` 行。
- 若情形 ② 中机密凭据无法登录（库中可用管理员不是业务方提供的那一个），属于执行环境问题：QA 按平台归类写诊断证据，`passed=false` 且 defects、testGaps 为空，不改库以绕过；处置见支持 Runbook。
- 回溯时间工单（引用经页面创建的前缀账号的 `app_user.id`）：直接写入 `ticket.submitted_at`；需要"首次已解决"时同时写入 `status`、`first_resolved_at`、`first_resolved_assignee_id` 与对应 `STATUS` 事件，保持与应用写入一致的语义。
- 清理顺序：`ticket_event` → `ticket_comment` → `ticket` → `category` → `app_user`（按本次运行前缀识别）；Independent QA 只按本次运行前缀清理它写入的工单、沟通、处理记录与类别，不删除或修改任何 `app_user` 行。
- 共享库中存在其他交付/候选的数据：断言必须限定在本次运行创建的数据上，或在同一时刻以独立 SQL 计算期望值进行比较。

## 安全、隐私、身份与 Secret

- **身份认证**：系统内自建账号；BCrypt（strength 10）哈希；密码明文只存在于请求处理内存中，不写日志、不回显（创建账号失败回填表单时清空密码框）。登录失败提示不区分"账号不存在"与"密码错误"。
- **会话**：Session Cookie `HttpOnly`、`SameSite=Lax`；`server.forward-headers-strategy=native` 使 Tomcat 识别网关的 `X-Forwarded-Proto: https` 并为 Cookie 加 `Secure`；登录成功更换 Session ID；空闲超时 30 分钟。
- **授权（least privilege）**：
  - URL 层：`/setup` 匿名可达，但开放判定在服务层每次请求执行，关闭时不可读写任何数据；`/admin/**` 需 `ADMIN`；`/tickets/new`、`POST /tickets` 需 `EMPLOYEE`；`/tickets/*/claim|assign|status` 需 `IT_STAFF`；`/tickets/**` 需 `EMPLOYEE` 或 `IT_STAFF`；`/login`、`/setup`、`/actuator/health`、`/api/candidate-identity`、`/css/**` 匿名；其余全部需认证。
  - 服务层：每个读写再次校验（员工只能读写 `submitter_id = 本人` 的工单；分派目标必须是当前 IT 同事）。管理员不能访问工单列表与详情（A-004）。
- **输入验证**：服务端 Bean Validation + 服务层规则；SQL 全部参数化；Thymeleaf 默认 HTML 转义（`th:text`），禁止 `th:utext` 渲染用户内容；描述与沟通内容以 `white-space: pre-wrap` 展示换行。
- **安全响应头**：Spring Security 默认头（`X-Content-Type-Options`、`X-Frame-Options: DENY`、`Cache-Control: no-store`）+ `Content-Security-Policy: default-src 'self'`（页面不使用内联脚本）。
- **数据库身份**：`DefaultAzureCredential`（候选/QA 为工作负载身份，Agent Pod 为 Pod 身份）；令牌受众 `https://ossrdbms-aad.database.windows.net/.default`；无密码、无连接字符串 secret；`docs/dependencies.json` 中 `secret=false`。
- **初始管理员**（TD-011、TD-017、TD-019）：由业务方在尚无可用管理员的数据库首次部署后，在登录入口通过一次性 `/setup` 流程自行设置，无需任何人改库或改配置，也无默认密码、启动生成密码或运维 Runbook。密码只在请求内存中做 BCrypt 哈希，不回显（校验失败重渲染时密码框清空）、不记录日志。防抢占（HT-0018）：只有持有部署时经平台机密提供的一次性设置口令的人才能完成设置；口令未提供时任何人都不能完成设置（失败即关闭）；口令只以 SHA-256 摘要保存在内存中、常量时间比较、不回显、不记录；失败按来源地址限流（15 分钟 5 次）。完成后流程永久关闭，口令随之失效。`POST /setup` 受全局 CSRF 保护。
- **测试环境初始管理员机密**（TD-018、TD-019）：`INITIAL_ADMIN_*` 只在 `test` 环境注入，只供 Independent QA 使用；`src/main/**` 中唯一出现的 `AIDH_SECRET_` 引用是 `application.yml` 中 `app.setup.token` 的默认值 `${AIDH_SECRET_INITIAL_SETUP_TOKEN:}`（Coding 以单元测试/静态检查确认没有其他 `AIDH_SECRET_*` 引用）；QA 截图、录屏、日志与结果文件中不得出现凭据或设置口令明文（输入框遮挡，结果只断言“已提供”）。开发者测试不读取任何 `AIDH_SECRET_*`，设置口令使用运行时随机生成的合成值。
- **日志隐私**：不记录请求体、查询参数中的关键词、工单标题/描述/沟通内容、密码、令牌；`RequestLogFilter` 只记录方法、匹配的路由模板、状态码、耗时、用户 id。

## 配置、可观测性与运维

### 配置（`application.yml`）

| 键 | 值 |
|---|---|
| `server.port` | `8080` |
| `server.forward-headers-strategy` | `native` |
| `server.servlet.session.timeout` | `30m` |
| `server.servlet.session.cookie.http-only` / `same-site` | `true` / `lax` |
| `spring.datasource.url` | `jdbc:postgresql://${AIDH_DEP_TICKET_DB_HOST}:${AIDH_DEP_TICKET_DB_PORT}/${AIDH_DEP_TICKET_DB_DATABASE}?sslmode=require&currentSchema=${app.db.schema}&authenticationPluginClassName=com.azure.identity.extensions.jdbc.postgresql.AzurePostgresqlAuthenticationPlugin`（端口从平台注入的 `AIDH_DEP_TICKET_DB_PORT` 读取，不写死 5432；`e2e/support/db.ts` 同样读取该变量） |
| `spring.datasource.username` | `${AIDH_DEP_TICKET_DB_USER}` |
| `spring.datasource.hikari.maximum-pool-size` / `max-lifetime` / `connection-timeout` | `10` / `30m` / `30s` |
| `spring.flyway.baseline-on-migrate` / `baseline-version` | `true` / `0` |
| `spring.flyway.schemas` / `default-schema` | `${app.db.schema}` / `${app.db.schema}` |
| `app.db.schema` | `public`（候选、QA、生产不覆盖；显式其他值只用于开发者测试，TD-016） |
| `app.setup.token` | `${AIDH_SECRET_INITIAL_SETUP_TOKEN:}`（TD-019；候选、QA、生产只由平台机密注入，不在配置文件中写值；开发者测试以命令行/测试属性传入运行时随机合成值） |
| `management.endpoints.web.exposure.include` | `health` |
| `management.endpoint.health.show-details` | `never` |
| `app.time-zone` | `Asia/Shanghai` |
| `spring.thymeleaf.cache` | `true` |

- 应用只从 `AIDH_DEP_TICKET_DB_*`（`HOST`、`PORT`、`DATABASE`、`USER`）读取数据库配置，`AIDELIVERY_DELIVERY_ID`、`AIDELIVERY_CANDIDATE_SHA` 只用于候选身份端点，不影响数据位置（TD-017）；不引入其他项目专用环境变量；机密只读取 `AIDH_SECRET_INITIAL_SETUP_TOKEN`（TD-019），不读取 `AIDH_SECRET_INITIAL_ADMIN_*`（TD-018）。缺少该机密时应用照常启动并记录一次 WARN，`/setup` 无法完成（失败即关闭），平台会在依赖与机密任务中请人提供。
- 容器 JVM 参数：`-XX:MaxRAMPercentage=75`。

### 可观测性

- 日志：Spring Boot 默认控制台格式输出到 stdout（平台收集），级别 INFO；`org.springframework.jdbc`、`org.flywaydb` 保持 INFO，禁止 DEBUG 参数日志。
- 健康：`/actuator/health`（含数据库连通性）。
- 不引入任何遥测后端或告警（宪章与本阶段规则）。

### 运维要点

- 尚无可用管理员的数据库首次投入使用（TD-017、TD-019）：部署时由人在平台依赖与机密任务中提供设置口令；被授权的业务方打开登录页，点击“设置初始管理员”，输入设置口令并完成设置后登录；无需运维改库或改配置。启动时不创建任何账号。
- 迁移在启动时自动执行，无需手动操作。

## SLO、限制、扩缩容、重试与恢复

- **SLO**：spec 无获批准的数值性能、可用率要求，本方案不设数值 SLO。
- **限制**：列表每页 50 条；字段长度上限见"领域与数据模型"；连接池 10。
- **扩缩容**：单副本（HttpSession 在内存）；V1 不做水平扩展。
- **重试**：应用不自动重试业务写操作（避免重复记录）；失败时整体回滚并提示用户重试。连接建立失败由 Hikari 在 `connection-timeout` 内重试获取。启动时数据库不可达则进程失败退出，由平台重启。
- **恢复**：所有业务数据在 PostgreSQL；容器无状态，重启/重新部署只丢失登录会话。数据库备份由平台托管的 PostgreSQL 服务承担，应用不实现备份。

## 部署拓扑、迁移、Rollout 与 Rollback

- **镜像**（根目录 `Dockerfile`，多阶段）：
  1. 构建阶段 `maven:3.9-eclipse-temurin-21`：`mvn -B -ntp -DskipTests package`。
  2. 运行阶段 `eclipse-temurin:21-jre`：复制 `target/helpdesk.jar` 到 `/app/app.jar`；`USER 10001`；`EXPOSE 8080`；`ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app/app.jar"]`。
  - `.dockerignore` 排除 `.git`、`target`、`e2e/node_modules`、`.ai-delivery`。
- **部署契约**（`.ai-delivery/features/001-v1/runtime/deployment.json`，由 Coding 编写）：`containerPort` 8080；`healthPath` `/actuator/health`；`candidateIdentityPath` `/api/candidate-identity`；`requiresPrivateNetwork` false；
  - `developerSetupCommands`：`mvn -B -ntp -q dependency:go-offline`、`cd e2e && npm ci`。
  - `developerValidationCommands`：`staticAnalysis` = `mvn -B -ntp checkstyle:check`；`build` = `mvn -B -ntp -DskipTests package`；`developerTests` = `mvn -B -ntp test`；`integrationTests` = `mvn -B -ntp verify -Pintegration -DskipUnitTests=true`（`pom.xml` 中 `integration` profile 启用 Failsafe 运行 `*IT.java`，Surefire 的 `skipTests` 绑定属性 `skipUnitTests`，默认 false）；`functionalTests` = `bash .ai-delivery/features/001-v1/runtime/developer-functional-run.sh`。
- **拓扑**：命名空间 `cand-{deliveryShortId}` 中单个非 root 容器；HTTPS 主机名经网关转发 HTTP 到 8080；出站只到 `ticket-db` 的 `test` 实例。
- **迁移策略**：
  - Flyway 版本化脚本位于 `src/main/resources/db/migration/`，命名 `V<n>__<desc>.sql`；已提交的迁移文件永不修改。
  - 只做增量变更（新增表/列/索引；新增列须可空或带默认值）；禁止 `DROP`、`TRUNCATE`、`DELETE` 业务数据、改列类型导致数据损失（NFR-001、FR-030）。
  - 多候选共享库：旧版本应用遇到更新候选已应用的迁移时按 `*:future` 忽略；新列对旧代码透明。
  - 数据作用域（TD-017）：候选、QA、生产都迁移并使用所连数据库的 `public` schema；同一数据库上的升级或重新部署沿用同一份数据。
- **Rollout**：平台构建镜像 → 部署候选 → 启动时迁移 → health 与 candidate-identity 校验通过。
- **Rollback**：回滚到上一镜像即可；由于迁移只增不删，旧镜像可在新增迁移后的 schema 上运行；不提供 schema 回退脚本（回退脚本会删除数据）。

## 验证环境责任矩阵（Verification Environment）

| 验证类型 | 责任阶段 | 环境来源 | 依赖/凭据 | Coding 证据 | 下游结果 |
|---|---|---|---|---|---|
| 静态分析（Checkstyle） | Coding 自测 + 候选验证 | Agent Pod / Runner | 无 | `mvn checkstyle:check` 输出 | 候选验证 `staticAnalysis` 通过 |
| 构建（jar） | Coding 自测 + 候选验证 | Agent Pod / Runner | 无 | `mvn package` 输出 | 候选验证 `build` 通过；ACR Tasks 镜像构建 |
| 单元/切片测试（`app.db.schema` 未设置时为 `public` 且不受 `AIDELIVERY_*` 影响（TD-017）；校验规则、报表周边界与格式化、授权规则、`RoleRefreshFilter`、关键词转义、`@WebMvcTest` 403/302 行为、`/setup` 匿名可达且 CSRF 生效、`InitialAdminSetupService` 分支（关闭时不写入、校验失败不写入、登录名重复不写入）、`SetupTokenVerifier`（空/过短视为未配置、正确/错误/空口令判定、不保留原值、`toString` 与日志不含口令）、`SetupAttemptLimiter`（窗口内第 6 次起拒绝、窗口过后恢复、地址间互不影响、容量上限淘汰）、`src/main/**` 中唯一的 `AIDH_SECRET_` 引用为 `AIDH_SECRET_INITIAL_SETUP_TOKEN`、`AppUserDetailsService` 对 NULL 哈希返回禁用用户、`app.db.schema` 非法值启动失败） | Coding 自测 + 候选验证 | Agent Pod / Runner | 无（数据库以外的纯逻辑，可用替身，不作为功能证据） | `mvn test` 报告 | `developerTests` 通过 |
| 集成测试（Flyway 迁移可重复执行且不删除已有数据、`/api/candidate-identity` 只返回 `deliveryId` 与 `candidateSha`；`InitialAdminSetupIT`（一次性 schema `it_setup_<runId>`，TD-016）：空 schema 迁移后开放、启动不写入任何账号、以随机合成值配置 `app.setup.token`：口令为空或错误时不创建且仍开放、未配置口令的上下文中任何提交都不创建账号、同一来源连续 5 次口令错误后第 6 次（即使口令正确）得 429 且不创建、必填项为空不创建且仍开放、成功创建 BCrypt 哈希 ADMIN 后关闭、关闭后再提交不新增或修改任何行、两个并发提交只成功一个、遗留 NULL 哈希 ADMIN 行不影响开放判定且不被修改、结束 `DROP SCHEMA ... CASCADE`；`public` schema 中以本次运行前缀插入的 NULL 哈希 ADMIN 账号用任意密码登录均失败且提示与凭据错误相同、`ck_app_user_password` 拒绝 NULL 哈希的非 ADMIN 行、仓库 SQL、搜索/筛选组合、员工隔离、认领/分派/状态事务与首次已解决、报表四项口径含 72 小时边界、跨周、再次解决、未分配、已移除类别、无数据） | Coding 自测 + 候选验证 | Agent Pod / Runner，真实 `ticket-db` `dev` 实例 | `AIDH_DEP_TICKET_DB_*` + Pod/Agent 身份 Entra 令牌 | `mvn verify -Pintegration` 报告；运行前缀与清理记录 | `integrationTests` 通过 |
| 功能测试（localhost 启动应用，Playwright Chromium 以三种身份走通 IQA-001～IQA-014 对应流程；另在 localhost:8081 以一次性 schema `e2e_setup_<runId>` 启动第二个实例，走通 IQA-016 对应流程（步骤顺序同 spec IQA-016）：登录页有入口且无注册入口、填写正确合成口令但留空登录名提交被拒、口令为空与口令错误各提交一次均被拒且不创建账号且流程仍可用、以正确合成口令完成设置、登录并创建员工/新增类别/打开周报表、退出后入口不显示、直接访问 `/setup` 得 403、用另一组凭据提交被拒且该凭据登录失败、原管理员仍可登录、页面不回显密码与设置口令） | Coding 自测 + 候选验证 | Agent Pod / Runner，localhost:8080 / 8081 + `dev` 实例 | 同上；8080 实例的管理员种子由 `e2e/support/db.ts` 插入；8081 实例不插入任何数据，以运行时随机合成设置口令启动 | `developer-functional-run.sh` 输出与 Playwright 报告 | `functionalTests` 通过 |
| 候选部署校验（镜像非 root、health、candidate-identity） | 候选验证 | 平台 `cand-{deliveryShortId}` + `test` 实例 | 候选工作负载身份 | 无（平台执行） | 平台部署记录 |
| Independent QA 验收 IQA-001～IQA-014、IQA-016 | Independent QA | 已部署候选 HTTPS 地址 + `test` 实例（`public` schema） | QA 工作负载身份 + `AIDH_SECRET_INITIAL_ADMIN_*`（TD-018）+ `AIDH_SECRET_INITIAL_SETUP_TOKEN`（TD-019）；IQA-016 先执行；员工、IT 同事及 IQA-001 的管理员账号由初始管理员经页面创建，不以 SQL 写入账号；QA 可自行插入回溯时间的工单数据 | 无（Coding 不编写 `qa-run.sh`） | `qa-result.json` |
| IQA-015 升级或重新部署后数据保留 | V1 不在候选环境中验证（spec IQA-015、AC-020：“V1 不验证升级或重新部署后的数据保留，改到后续迭代通过真实升级来验证”，HT-0014） | 无（后续迭代以真实升级验证） | 无 | 集成测试证明迁移可重复执行、不删数据、启动不写入数据（辅助证据，不替代验收） | 后续迭代 |
| IQA-016 初始管理员设置（首次设置 / 已设置后的关闭行为） | 首次设置完整流程：Coding 自测 + 候选验证（`InitialAdminSetupIT` 与 8081 功能测试，真实 `dev` 实例一次性空 schema）；候选环境：Independent QA 按“数据库数据契约”中的情形 ①/② 执行 | 一次性 schema（`dev`）；已部署候选 + `test` 实例 | 不预先插入或修改任何账号数据或配置以影响开放判定；候选环境中初始管理员凭据来自 `AIDH_SECRET_INITIAL_ADMIN_*`（TD-018），设置口令来自 `AIDH_SECRET_INITIAL_SETUP_TOKEN`（TD-019，候选容器与 QA 拿到同一 `test` 值） | `mvn verify -Pintegration` 与 Playwright 报告 | `qa-result.json` 中 IQA-016 |

替身使用说明：`dev`、`test` 均声明 `realInstance=true`，集成测试与功能测试不得使用数据库替身；单元测试中的替身只用于纯逻辑，不计入 functional/integration 证据。

## 快速验证指南（Quickstart）

在 Agent Pod 中（平台已注入 `AIDH_DEP_TICKET_DB_*`）：

```bash
mvn -B -ntp checkstyle:check
mvn -B -ntp -DskipTests package
mvn -B -ntp test
mvn -B -ntp verify -Pintegration -DskipUnitTests=true
java -jar target/helpdesk.jar &          # 启动后自动迁移，监听 localhost:8080
curl -s localhost:8080/actuator/health    # {"status":"UP"}
curl -s localhost:8080/api/candidate-identity
(cd e2e && npm ci && npx playwright test)
```

`.ai-delivery/features/001-v1/runtime/developer-functional-run.sh` 的职责（由 Coding 编写）：生成运行前缀（如 `e2e-<时间戳>-<随机>`）→ 构建并后台启动两个 jar 实例：8080（默认 `public` schema）与 8081（`--server.port=8081 --app.db.schema=e2e_setup_<运行前缀转小写下划线> --app.setup.token=<运行时随机生成的 32 字符合成口令>`，口令同时以环境变量传给 Playwright，不输出）→ 分别轮询 health 至 UP（超时 120 秒）→ 以运行前缀与随机密码（只存在于进程环境，不输出）运行 Playwright → 无论成败都执行按前缀清理与 `DROP SCHEMA e2e_setup_... CASCADE` → 停止两个实例 → 以 Playwright 退出码退出。

集成测试的数据隔离做法：
- 所有账号、类别名使用 `it-<runId>-` 前缀，测试结束 `@AfterAll` 按前缀清理。
- “设置初始管理员”集成测试使用独立 Spring 上下文（`app.db.schema=it_setup_<runId>`、`app.setup.token=<随机合成值>`；另有一个不设置口令的上下文验证失败即关闭），结束时删除该 schema（TD-016、TD-019）。
- 报表测试把测试时钟（`application-it.yml` 中启用的可变 `Clock`）设到一个随机选取的历史周（例如 1970～2000 年之间的某一周），在该时钟下创建工单与状态变更，使本周统计、"未分配"与平均时长只包含本测试数据；积压测试同理（所选历史时点之前不存在其他运行的数据）。

## 支持 Runbook

| 现象 | 排查 | 处置 |
|---|---|---|
| 新部署环境无人能登录 | 打开登录页，看是否显示“设置初始管理员” | 显示：由持有设置口令的业务方点击完成设置后登录（无需运维改库或改配置）；不显示：所连数据库中已有可用管理员，请联系已有管理员 |
| Independent QA 情形 ② 中业务方提供的初始管理员凭据无法登录 | QA 证据中登录失败截图；只读查询 `test` 库中 `role='ADMIN' AND password_hash IS NOT NULL` 的登录名是否与机密登录名一致 | 机密值与已设置账号不一致：由业务方在平台依赖与机密任务中更正机密值；库中已有他人设置的管理员：由人决定改绑一个尚无可用管理员的 `test` 库（平台依赖绑定），不改库、不重开 `/setup` |
| `/setup` 提示“此部署未提供设置口令” | 启动日志中有“未提供有效的设置口令”WARN | 设置口令机密缺失或少于 16 个字符：由人在平台依赖与机密任务中提供或更正 `INITIAL_SETUP_TOKEN`，平台重新部署后生效；不改库、不改配置文件 |
| `/setup` 提示“设置口令不正确”或“尝试次数过多” | 日志中口令失败/超限 WARN 的次数与时间 | 确认使用的是平台为本环境提供的口令；超限时等待 15 分钟（或重新部署使计数清零）后重试；若大量失败来自非业务方，说明入口地址已被他人获知，口令仍保护设置不被抢占，尽快完成设置即可 |
| 业务方访问 `/setup` 得到“此入口不可用” | 预期行为：所连数据库中已有可用管理员（FR-031） | 使用已有管理员账号登录；应用不提供重新开放该流程的方式 |
| 启动失败、health 不通 | 查看容器日志中 Flyway 或连接错误 | 连接/令牌错误：确认依赖绑定与工作负载身份权限（平台依赖绑定节点）；迁移校验失败：检查是否有人修改了已应用的迁移文件，按"只增不删"新增修正迁移 |
| 间歇性数据库连接认证失败 | 日志中 PostgreSQL 认证错误 | 确认 `maxLifetime` 小于令牌有效期；检查身份令牌获取是否受限 |
| 用户反映身份变更未生效 | 查询该账号 `role` | `RoleRefreshFilter` 每请求刷新；若库中已是新值，请用户刷新页面 |
| 报表数字与预期不符 | 以"报表计算"中的 SQL 在同一时刻手工计算；确认 `app.time-zone` | 核对时区与周边界（周一 00:00 起，左闭右开） |
| 需要删除误提交数据 | V1 不提供删除功能（FR-029） | 不在应用中删除；如有业务需要另行提出变更 |

## 开放决策

- 无未决技术决定。DEC-TS-001 已由人工澄清答案 HT-DLV-20261116-72F2B9041E74-0014 选定 D（见 TD-017），spec 已据此修订 IQA-015、IQA-016、AC-020、AC-021 与测试策略；人工审批意见 HT-DLV-20261116-72F2B9041E74-0016 要求的初始管理员凭据交付方式由 TD-018 决定；人工审批意见 HT-DLV-20261116-72F2B9041E74-0018、HT-DLV-20261116-72F2B9041E74-0019 要求的防抢占措施由 TD-019（一次性设置口令 + 失败限流）决定，spec 已据此修订 FR-031、SC-007、IQA-016、AC-021 与测试策略，本设计与之一致。
- 无待上游修订事项。
