# 内部 IT 工单系统 V2 技术方案

## 技术上下文（Technical Context）

- **交付**：DLV-20261117-88E4DAE97F6A V1 · 内部 IT 工单系统 V2。权威输入为本目录 `intent.md`（INT-001～INT-016，BQ-001～BQ-003 人工答案，人工审批意见 HT-DLV-20261117-88E4DAE97F6A-0002）、`spec.md`（US-001～US-006、FR-001～FR-023、NFR-001、AC-001～AC-016、IQA-001～IQA-010）与已批准的 `technical-baseline.md`（方案 A：Java 21 + Spring Boot 单体、服务端渲染、平台 `postgres` 依赖、Flyway 启动迁移）。
- **编号约定**：本文中不带前缀的 FR/IQA/AC/INV 指 V2 `spec.md`；V1 编号写作“V1 FR-0xx”等。V1 技术决策 TD-001～TD-019 见 `.ai-delivery/features/001-v1/design.md`，除本文明确修订的条目外全部沿用；V2 新增决策从 TD-020 起编号。
- **仓库现状**：V1 已实现并上线（`pom.xml`、`src/`、`e2e/`、根目录 `Dockerfile`；V1 迁移 `src/main/resources/db/migration/V1__init.sql`）。V2 在同一代码库上增量修改，不新建工程、不改变技术栈。
- **技术栈（沿用，不变）**：Java 21（Eclipse Temurin）；Spring Boot 3.5.x：Spring MVC + Thymeleaf、Spring Security（表单登录 + HttpSession + CSRF）、`NamedParameterJdbcTemplate`、Flyway、Actuator、Bean Validation；PostgreSQL JDBC + `com.azure:azure-identity-extensions` 认证插件；Maven + Checkstyle；JUnit 5 / Spring Boot Test / Failsafe；Node 22 + `@playwright/test`（Chromium）。
- **运行形态（不变）**：单进程、单容器、单副本，HTTP `8080`，TLS 在平台网关终止。
- **外部依赖（不变）**：只有 `ticket-db`（`postgres`，`dev`/`test` 均为真实实例）。应用机密仍只有 `INITIAL_SETUP_TOKEN`（应用读取）；`INITIAL_ADMIN_*`（只供 Independent QA，应用不读取）。V2 不引入新的云服务、遥测后端、身份产品或数据库。
- **V2 变更面**：① 期望解决时间与超时标识（列表、搜索/筛选结果、详情）；② 账号启用/停用（登录拒绝、已登录会话失效、分派对象过滤与提交校验、最后一个可用管理员保护、账号列表状态）；③ 周报表第五项“本周超时解决的工单数量”；④ 从 V1 原地升级（迁移 `V2__account_active.sql`）及其开发者升级演练验证；候选环境中由 Independent QA 以 V1 交付留下的持久升级基线逐项核对真实升级结果（DEC-TS-V2-001 人工答案 A，TD-034）。

## 治理与技术约束

| 约束来源 | 约束内容 | 设计响应 |
|---|---|---|
| 宪章「产品与架构」 | 最简单架构，保持已批准范围 | 在 V1 单体上增量修改；期望解决时间不落库，读取时由不可变字段派生（TD-020）；整次升级只有一条只增不删的 DDL（TD-022、TD-029）；不实现范围外能力（通知、工作日日历、手工改时限、删除账号、批量停用、按超时筛选） |
| 宪章「安全与隐私」 | 凭据留在服务端、least privilege、验证输入、不记录 secret 或客户内容 | 停用/启用只有 `ADMIN` 可调用（URL 层 + 服务层双重校验，TD-027）；停用账号在认证与每个请求两处被拒（TD-023、TD-024）；分派目标在事务内校验启用状态（TD-026）；日志只记录目标与操作者用户 id，不记录登录名、姓名或凭据 |
| 宪章「测试与质量」 | 外部 integration 用真实测试服务；Mock 不能替代功能验收 | 集成测试、功能测试与升级演练全部连接 `ticket-db` 的 `dev` 真实实例；不使用 H2/Testcontainers/数据库替身 |
| 宪章「交付治理」 | 可追踪 | 每个 TD、接口、测试标注 FR/IQA/AC 编号；开发者测试文件名带 V2 IQA 编号 |
| 技术基线（方案 A） | Java + Spring Boot 服务端渲染；Flyway 启动迁移、只增不删、可重复执行；`Clock` 注入；公司时区 `Asia/Shanghai` | 全部沿用；“当前时刻”只取注入的 `Clock`（V1 TD-010），超时判定与报表边界同样如此 |
| 平台：postgres 依赖、`sslmode=require`、Entra 令牌、平台不执行迁移、库被多交付与候选共享、不得假设库为空 | 连接、迁移与测试数据 | 沿用 V1 连接配置；迁移在启动时由 Flyway 执行（advisory lock 串行、history 去重、事务性 DDL）；测试以运行前缀或一次性 schema 隔离并清理 |
| 平台：认证方式由依赖绑定选定，应用只实现 `AIDH_DEP_{N}_AUTH_MODE` 所示的一种 | `ticket-db` 已在 V1 以 `platform-identity` 绑定并实现 | 应用只实现 `platform-identity`；启动时校验 `AIDH_DEP_TICKET_DB_AUTH_MODE`（TD-031） |
| 平台：根目录 Dockerfile、数字非 root UID、healthPath、candidateIdentityPath、`requiresPrivateNetwork=false` | 候选构建与部署 | 不变（`USER 10001`、`/actuator/health`、`/api/candidate-identity`） |
| 平台：Independent QA 针对已部署候选执行，不能部署其他版本或重新部署候选；“部署 V1”不是平台能力 | IQA-009、IQA-010 原文要求“先部署 V1、再升级、再次升级” | 人工澄清答案 HT-DLV-20261117-88E4DAE97F6A-0005（DEC-TS-V2-001 选 A）：完整的“V1→快照→升级→再次升级→中断重跑”由开发者升级演练在真实 `dev` 实例上完成（TD-030）；Independent QA 在候选环境以 V1 IQA-015 持久基线逐项核对 `test` 库真实升级结果，并在平台提供受控重新部署能力时以“重新部署同一候选”执行 IQA-010 的再次升级（TD-034）；spec IQA-009/IQA-010 与测试策略须按该答案修订（本次以回退上游提出） |
| spec「假设」A-002～A-006 | 打开或刷新页面时判定超时；停用无需原因、不写处理记录；停用账号按原姓名显示；管理员不看工单明细；单个停用 | 超时在渲染时判定（TD-021）；停用只改 `app_user.active`，不写 `ticket_event`（TD-022）；姓名显示查询不变；不提供批量接口 |
| V1 人工审批与答案（V1 TD-011、TD-017～TD-019） | `/setup` 一次性、设置口令防抢占、数据作用域为所连库 `public` | 全部沿用；`/setup` 开放判定不随停用状态改变（TD-032），满足 FR-021“升级后仍不可用” |

## 架构概览（Architecture）

```
浏览器（员工 / IT 同事 / 管理员）
      │ HTTPS
      ▼
平台网关（TLS 终止）── HTTP ──► 容器 :8080（USER 10001）
                                     │
               ┌─────────────────────┴──────────────────────────┐
               │ Spring Boot 单体（helpdesk.jar）                 │
               │  Security 过滤链：表单登录、CSRF、                 │
               │    RoleRefreshFilter（每请求读库：身份 + 启用状态）│ ← V2：停用即踢出（TD-024）
               │  Web 层（Thymeleaf）                              │ ← V2：期望解决时间、超时标识、
               │                                                  │        启用/停用入口、报表第五项
               │  服务层（授权 + 校验 + 事务）                      │ ← V2：AccountStatusService（TD-025）
               │  数据访问层（手写 SQL）                            │ ← V2：期望解决时间 SQL 表达式（TD-020）
               │  Flyway（启动迁移 V1 → V2）  Actuator（health）     │ ← V2：V2__account_active.sql
               └─────────────────────┬──────────────────────────┘
                                     │ JDBC + TLS + Entra 令牌
                                     ▼
                         PostgreSQL 项目库（依赖 ticket-db）
```

- 升级 = 新版本镜像启动时 Flyway 把所连库从 V1（history 版本 1）迁移到 V2（版本 2）；重复执行升级 = 再次启动同一或更新版本（Flyway 识别已应用版本，不重复执行）。
- 期望解决时间是 `submitted_at` 与 `urgency` 的确定函数，所有读取路径在 SQL 中用同一表达式计算；超时标识在渲染时用请求内唯一的 `now` 判定。
- 账号启用状态是 `app_user.active`，在三处生效：认证（`AppUserDetailsService`）、每个已认证请求（`RoleRefreshFilter`）、分派（候选列表与提交时行锁校验）。

## 技术决策与替代方案

| 决策 ID | 选择 | 理由 | 已考虑替代方案 | 来源 |
|---|---|---|---|---|
| TD-020 | 期望解决时间**不落库**，由 `submitted_at + 时长(urgency)` 在读取时派生。时长唯一来源是 `Urgency` 枚举新增的 `resolutionHours()`（`HIGH`=4、`MEDIUM`=24、`LOW`=72）与 `resolutionDuration()`；SQL 表达式由新类 `ticket/ExpectedResolution.sql(String alias)` 依据枚举生成：`(<a>.submitted_at + CASE <a>.urgency WHEN 'HIGH' THEN INTERVAL '4 hours' WHEN 'MEDIUM' THEN INTERVAL '24 hours' WHEN 'LOW' THEN INTERVAL '72 hours' END)`，列表/搜索/详情查询以别名 `expected_resolve_at` 选出，报表在 WHERE 中复用 | `submitted_at` 与 `urgency` 提交后不可修改（V1），派生值天然满足 INV-001、FR-002“一经确定不再改变”；V1 既有工单无需任何数据写入即“补上”期望解决时间（FR-020），升级与重复升级不改动任何业务行（FR-019、FR-023、INV-006、NFR-001）；以小时为单位的 `timestamptz + interval` 与会话时区无关，即自然时间、不顺延（FR-001）；回滚到 V1 再升级、或升级后仍有旧代码写入的工单都不会出现缺失值 | 新增 `ticket.expected_resolve_at` 列并在迁移中 `UPDATE` 回填（升级需写全部工单行；中断/回滚/旧代码写入会留下 NULL，须重复回填，增加 NFR-001 风险）；生成列 `GENERATED ALWAYS AS … STORED`（`timestamptz + interval` 非 IMMUTABLE，须自定义函数绕过，且 ADD COLUMN 重写全表） | FR-001、FR-002、FR-020、FR-023、INV-001、INV-006、NFR-001 |
| TD-021 | 超时判定在 Java：`ticket/Overdue.isOverdue(TicketStatus status, Instant expectedAt, Instant now)` = `status ∈ {OPEN, IN_PROGRESS} && now.isAfter(expectedAt)`（严格晚于，相等不算）；控制器在每个请求开始时取一次 `clock.instant()` 作为本页 `now`，列表各行与详情使用同一 `now`；不在 SQL 中使用 `now()` | 沿用 V1 TD-010（时间可测试）；同页判定一致；状态改回未解决时下一次查看即按当时时刻判定（FR-006） | SQL 中 `now() > expected`（不可注入测试时钟）；页面脚本定时刷新（A-002 不要求，且 CSP 禁止内联脚本） | FR-004、FR-005、FR-006、INV-002、A-002 |
| TD-022 | 账号启用状态 = 新列 `app_user.active BOOLEAN NOT NULL DEFAULT TRUE`（迁移 `V2__account_active.sql`）。V1 既有行在 ADD COLUMN 时取默认值 TRUE，迁移中不出现 UPDATE；`UserRepository.insert` 不写 `active` 列（依赖默认值，与 V1 写入形态一致）；停用/启用只执行 `UPDATE app_user SET active = :v WHERE id = :id AND active = :old`，不改 `login_name`、`password_hash`、`display_name`、`role`，不写 `ticket_event` | 最小 schema 变更；PostgreSQL 11+ 对带常量默认值的 ADD COLUMN 只改目录、不重写表；满足 FR-007、FR-008、FR-021、INV-003、INV-005、A-003；登录名唯一约束不变，停用账号继续占用登录名（FR-016） | 独立的 `account_status` 历史表（A-003 不要求记录）；`deactivated_at` 时间戳列（无已批准需求使用）；软删除（与“可重新启用、数据照常显示”不符） | FR-007、FR-008、FR-016、FR-021、INV-003、INV-005 |
| TD-023 | 停用账号登录被拒：`CurrentUser` 增加 `active` 字段，`isEnabled() = passwordHash != null && active`；`AppUserDetailsService` 查询包含 `active`。Spring Security `DaoAuthenticationProvider` 对禁用用户抛 `DisabledException`，沿用 V1 `failureUrl("/login?error")`，提示文字与凭据错误完全相同（“登录名或密码错误”），不新增任何区分性提示或参数 | 与 V1 对 NULL 哈希账号的处理同一机制；提示一致（FR-009、AC-006） | 自定义提示“账号已停用”（违反 FR-009“与 V1 登录失败提示一致”） | FR-009、FR-010、AC-006 |
| TD-024 | 已登录会话在下一次操作时失效：`RoleRefreshFilter` 读取的账号记录增加 `active`；账号不存在**或** `active=false` 时清空 SecurityContext、使 HttpSession 失效并 `302 /login`（对 GET 与 POST 一致，在授权与业务处理之前，因此该请求不产生任何读写）；启用账号的身份刷新逻辑不变 | V1 已在每个已认证请求按 id 读库（V1 TD-006），只扩展判定条件即可满足“下一次操作被拒绝并被引导至登录入口” | 会话注册表在停用时主动踢出（需额外组件，收益相同） | FR-009、FR-014、AC-006、AC-009 |
| TD-025 | 停用/启用由新服务 `user/AccountStatusService` 执行，单个事务内：① `SELECT pg_advisory_xact_lock(7243012)` 串行化全部停用/启用操作；② `SELECT … FROM app_user WHERE id=:id FOR UPDATE` 读取目标；③ 目标不存在 → `ValidationException("账号不存在")`；目标已是期望状态 → 返回“账号已是停用状态”/“账号已是启用状态”，不写库；④ 停用且目标为**可用管理员**（`role='ADMIN' AND active AND password_hash IS NOT NULL`）时，统计除目标外的可用管理员数，为 0 → `ValidationException("系统必须至少保留一个可用的管理员")`，不写库；⑤ 否则 UPDATE 并提交 | advisory lock 使两个管理员同时互相停用也只能成功一个，INV-004 在并发下成立；“可用管理员”排除 V1 遗留的 NULL 哈希 ADMIN 行（其不能登录）；停用自己与停用他人同一规则（FR-014） | 只用行锁 `FOR UPDATE` 锁管理员集合（READ COMMITTED 下计数需额外重读，易错）；数据库触发器（逻辑分散） | FR-007、FR-014、INV-004、AC-009 |
| TD-026 | 分派：候选列表改用新查询 `UserRepository.findAssignableItStaff()`（`role='IT_STAFF' AND active`，按姓名、id 排序，排除本人）；提交时在工单行锁之后以 `findAssigneeCandidateForShare(id)`（`SELECT role, active FROM app_user WHERE id=:id FOR SHARE`）校验：非 `IT_STAFF` 或为本人 → 沿用 V1 提示“分派对象必须是其他 IT 同事”；`active=false` → “该 IT 同事已停用，不能分派，请刷新后重新选择”；两者都不改工单、不写记录。`FOR SHARE` 与停用的 `UPDATE` 互斥，提交分派与停用并发时按提交顺序生效。筛选下拉的“处理人”选项（V1：IT 同事 ∪ 曾任处理人）、认领、报表人员行**不按 active 过滤** | 满足“不再出现在可分派对象中”与“提交时已停用则拒绝”；历史筛选与报表照常（FR-013） | 只过滤下拉、不在提交时校验（不满足 FR-011 并发情形）；停用时清空其名下工单处理人（违反 FR-012、BQ-003 A） | FR-011、FR-012、FR-013、AC-007、AC-008 |
| TD-027 | 管理员页面入口：`POST /admin/users/{id}/deactivate`、`POST /admin/users/{id}/activate`（`/admin/**` 需 `ADMIN`，服务层再次校验）；账号列表新增“状态”列（启用/停用）与每行一个按钮（启用状态显示“停用”，停用状态显示“启用”）；成功或“已是该状态”→ PRG 302 `/admin/users` + flash；校验失败 → PRG + flashError；**例外**：管理员成功停用自己时，控制器直接以 200 渲染 `admin/users`（列表显示本人为“停用”，flash“已停用该账号”），不重定向，该会话的下一次请求由 TD-024 拒绝并引导至登录入口 | PRG 沿用 V1；自我停用若重定向，重定向后的 GET 已被 TD-024 拦截，管理员看不到操作结果，直接渲染使 IQA-006 步骤 3“成功、显示为停用”与步骤 4“下一次操作被拒”都可观察 | 自我停用后立即注销（结果页不可见）；JSON API + 脚本（违背 V1 无脚本 CSP 与服务端渲染） | FR-007、FR-008、FR-014、FR-015、AC-005、AC-009、AC-010 |
| TD-028 | 周报表第五项：`WeeklyReportRepository.overdueResolvedCount(WeekRange)` = `SELECT COUNT(*) FROM ticket t WHERE t.first_resolved_at >= :weekStart AND t.first_resolved_at < :weekEnd AND t.first_resolved_at > <ExpectedResolution.sql("t")>`；`WeeklyReport` 增加字段 `overdueResolved`（long），页面在 V1 四项之后新增一节“本周超时解决的工单数量”，0 时显示 `0`。V1 四项 SQL、行集合与格式完全不改 | 直接复用 V1 冗余列 `first_resolved_at`（只在首次解决时写入，V1 TD-008），口径即 BQ-002 A：再次解决、只关闭、恰好等于均不计入 | 从 `ticket_event` 回放首次解决（结果相同但更复杂） | FR-017、FR-018、FR-022、AC-011、AC-012 |
| TD-029 | 升级机制：`V2__account_active.sql` 只含一条语句 `ALTER TABLE app_user ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT TRUE;`。Flyway 在 PostgreSQL 上把单个迁移放在一个事务中执行（PostgreSQL DDL 可回滚），中途中断（进程退出、连接断开）则整体回滚，history 不记录成功，下次启动重新执行；Flyway 用 advisory lock 串行化并发启动的多个实例；已应用的版本不再执行；`validateOnMigrate`（默认）在每次启动校验 V1、V2 文件校验和未被修改。`MigrationPolicyTest` 扩展为：所有迁移文件不得包含 `DROP`、`TRUNCATE`、`DELETE`、`UPDATE`、`INSERT`、`ALTER … TYPE`、`RENAME`，文件名匹配 `V\d+__[A-Za-z0-9_]+\.sql` | 升级只增加一列，不读写任何业务行，“不新增、丢失或改变数据”由结构保证并由静态策略测试与升级演练双重验证；重复执行与中断后重跑的结果与一次成功执行相同（FR-023、AC-015、AC-016） | 应用内自写升级逻辑或启动时 Java 回填（需自管幂等与锁）；Flyway `R__` 可重复迁移（每次校验和变化都会重跑，不需要） | FR-019、FR-021、FR-023、NFR-001、AC-013、AC-015、AC-016 |
| TD-030 | 升级演练（开发者验证，真实 `dev` 实例）：在一次性 schema 上用**同一份已提交的 V1 迁移**构造 V1 状态（V2 jar 以 `--spring.flyway.target=1` 启动即只应用 V1，随后停止），按 V1 数据契约以 SQL 写入覆盖 IQA-009 前置条件的 V1 合成数据集并记录快照，再以 V2 默认配置启动同一 schema 完成升级，用浏览器以原凭据逐身份核对；随后写入 V2 新数据、再次启动（重复升级）并逐项比对；另有集成测试在 V2 迁移执行中途终止数据库连接，验证回滚与重跑结果（细节见“数据流”“验证环境责任矩阵”） | 平台中 Independent QA 无法部署 V1，重新部署候选也尚非平台能力（DEC-TS-V2-001 人工答案 A）；V1 schema 只由 `V1__init.sql` 决定且 Flyway 校验和保证与线上一致，故以它构造的 V1 状态与真实 V1 库结构相同；真实 PostgreSQL、真实 Flyway、真实应用与浏览器，不使用替身 | 从 git 历史构建 V1 jar（候选验证的检出不保证包含历史）；只做单元测试（不满足宪章真实集成） | FR-019～FR-023、NFR-001、AC-013～AC-016、IQA-009、IQA-010 |
| TD-031 | `ticket-db` 认证方式：应用只实现 `platform-identity`（V1 已以该方式绑定并实现）。新增 `config/DatabaseAuthModeCheck`（`ApplicationContextInitializer` 或 `EnvironmentPostProcessor`，在数据源创建前执行）：读取 `AIDH_DEP_TICKET_DB_AUTH_MODE`，为空或缺失视为 `platform-identity`；为 `platform-identity` 时继续；其他值（含 `credential`）以错误日志“ticket-db 认证方式 <值> 未在本版本实现”启动失败（非零退出），不尝试读取 `AIDH_DEP_TICKET_DB_PASSWORD` | 平台规则：应用只实现绑定时选定的一种，更换视为需求变更；失败即可见，避免以错误方式连接 | 同时实现两种方式（超出已绑定范围） | 平台运行环境画像 |
| TD-032 | `/setup` 开放判定保持 V1 TD-011 原样：`NOT EXISTS(SELECT 1 FROM app_user WHERE role='ADMIN' AND password_hash IS NOT NULL)`，**不**加入 `active` 条件 | INV-004 保证始终存在启用的管理员；即使判定不看 `active`，一旦设置过管理员流程即永久关闭，升级后同样关闭（FR-021）；若加入 `active` 条件，任何异常数据都可能重新开放设置入口，削弱 V1 防抢占语义 | 以“存在可用管理员”判定（停用状态异常时会重开入口） | FR-021、V1 TD-011 |
| TD-033 | 页面自动化定位：在 V1 `data-testid` 约定上只新增，不改名不删除（见“Interface 与契约”）；新增元素同时带机器可读属性（`data-expected-at` 为 ISO-8601 UTC 瞬时，`data-overdue` 为 `true`/`false`，`data-active` 为 `true`/`false`） | 开发者功能测试与 Independent QA 可稳定定位并精确核对时刻 | 只靠可见文本定位（受格式与语言影响） | IQA-001～IQA-008 |
| TD-034 | Independent QA 的升级核对（DEC-TS-V2-001 人工答案 A）：① **IQA-009 升级前数据基线 = V1 交付 Independent QA 在 `test` 库中建立的持久基线**（前缀 `q15-72f2b9041e74-`，V1 IQA-015 deferred baseline：一名员工、一名 IT 同事、一个类别、三张不同紧急程度与状态的工单及其补充说明、回复、认领与状态记录，快照存于标题为 `<前缀> 基线快照` 的工单描述中，含建立时候选 SHA、页面视图与数据库行摘要，基线账号凭据可由快照中的 nonce 按 V1 QA 计划的规则推导）。V2 候选首次部署到 `test` 库即对该基线执行真实的 V1→V2 升级；QA 用原凭据登录并**逐项核对基线中的账号、类别、工单、沟通与处理记录仍然存在且内容未变**（页面 V1 字段与快照一致、数据库 V1 列摘要与快照一致），而不只是检查不变量；同时核对基线工单的期望解决时间与超时标识、基线账号为“启用”、迁移历史与报表。② **IQA-010 再次升级**：平台提供“按 QA 计划声明、在场景之间对同一候选做受控重新部署并给出部署证据”的能力时，QA 计划声明在 IQA-010 之前重新部署同一候选，比对重新部署前后的数据快照与 `flyway_schema_history`；该能力不可用时，IQA-010 在候选环境只做迁移历史与 V2 数据保留的只读核对，再次升级与中断重跑以开发者升级演练（TD-030）为证据，QA 不声称执行了再次升级。③ 不要求也不实现“部署 V1”。应用侧不需要为此新增任何接口、后门或配置：核对只依赖 V1 保留的 `data-testid`（TD-033）、V1 列不变（TD-022、TD-029）与只读 SQL | 用真实 V1 候选在真实 `test` 库中写入、真实 V2 候选升级的数据核对升级，是平台内唯一能让独立 QA 直接观察“升级前数据仍在且未变”的方式；基线账号在 V2 中只新增 `active` 列，V1 列与数据库摘要所用列集合不受迁移影响，可逐项比对 | 只检查不变量（被人工答案否定）；为 QA 增加构造 V1 数据的测试接口（产品后门，违反最简单与 least privilege）；等待平台“部署 V1”能力（非平台能力，人工答案明确不作要求） | 人工澄清答案 HT-DLV-20261117-88E4DAE97F6A-0005；FR-019～FR-023、NFR-001、AC-013～AC-016、IQA-009、IQA-010；V1 `tasks-qa.md` IQA-015 |

## 领域与数据模型

### 枚举变化

| 枚举 | 变化 |
|---|---|
| `Urgency` | 新增 `resolutionHours()`：`HIGH`→4、`MEDIUM`→24、`LOW`→72；`resolutionDuration()` = `Duration.ofHours(resolutionHours())`（TD-020） |
| 其他（`Role`、`TicketStatus`、`EventType`） | 不变；不新增“超时”状态（spec“业务状态”：超时是派生标识） |

### 表结构变化（迁移 `V2__account_active.sql`）

```sql
-- V2：账号启用 / 停用（FR-007～FR-016、FR-021）。只增不删；不读写任何业务行（TD-029）。
-- 期望解决时间由 submitted_at 与 urgency 派生，不落库（TD-020）。
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT TRUE;
```

- 其余表（`category`、`ticket`、`ticket_comment`、`ticket_event`）与 V1 完全相同；`V1__init.sql` 不修改（Flyway 校验和保护）。
- 不新增索引：V2 没有获批准的性能要求；超时判定在已取出的页面行上进行，报表第五项按 V1 已有索引 `ix_ticket_first_resolved` 限定本周范围。

### 派生值与不变量映射

| 概念 | 计算 / 存储 | 不变量 |
|---|---|---|
| 期望解决时间 | `submitted_at + resolutionHours(urgency) 小时`；SQL 别名 `expected_resolve_at`；页面按公司时区 `yyyy-MM-dd HH:mm` 显示（V1 `TimeFormat`），属性 `data-expected-at` 为 ISO-8601 UTC | INV-001（输入字段不可修改，故值不变） |
| 超时未解决 | `Overdue.isOverdue(status, expectedAt, now)`，渲染时计算 | INV-002 |
| 启用 / 停用 | `app_user.active` | INV-003、INV-005 |
| 可用管理员 | `role='ADMIN' AND active AND password_hash IS NOT NULL` | INV-004（TD-025） |
| 可分派对象 | `role='IT_STAFF' AND active` | FR-011 |
| 本周超时解决 | `first_resolved_at ∈ [weekStart, weekEnd) AND first_resolved_at > expected_resolve_at` | FR-017 |
| 升级不改数据 | 迁移只有 ADD COLUMN；`MigrationPolicyTest` 静态禁止写语句 | INV-006、NFR-001 |

- `AppUser` 记录增加 `boolean active`；`UserRepository` 的列清单 `COLUMNS` 增加 `active`。
- 工单读取模型（`TicketRepository` 的 `SELECT` 常量及其映射的 `Ticket`/列表行记录）增加 `Instant expectedResolveAt`；`TicketDetail`/列表视图模型增加 `boolean overdue`（由控制器用本请求 `now` 计算后传入模板）。
- `WeeklyReport` 增加 `long overdueResolved`。

## Component 边界与项目结构

Coding 可写的仓库路径（唯一授权来源）：

- `src/main/java/`
- `src/main/resources/`
- `src/test/java/`
- `src/test/resources/`
- `e2e/`
- `config/checkstyle/`
- `.gitignore`

说明：根目录 `pom.xml`、`Dockerfile`、`.dockerignore` 为平台固定授权的包管理与构建文件；`.ai-delivery/features/002-v1/runtime/deployment.json` 与 `.ai-delivery/features/002-v1/runtime/developer-functional-run.sh` 由 Coding 按平台授权编写；`.ai-delivery/features/002-v1/runtime/qa-run.sh` 只由 Independent QA 编写，Coding 不编写、不预置其任何内容。`src/main/resources/db/migration/V1__init.sql` 虽位于授权目录内，但不得修改（TD-029）。

目录树（阅读用，只列 V2 新增或修改的文件；其余同 V1 design）：

```
src/main/java/com/ithelpdesk/
  config/      DatabaseAuthModeCheck（新增，TD-031）；HelpdeskApplication 或 META-INF/spring.factories 注册它
  auth/        CurrentUser（+active，isEnabled）、AppUserDetailsService、RoleRefreshFilter（停用即踢出）
  user/        AppUser（+active）、UserRepository（COLUMNS+active、findAssignableItStaff、findAssigneeCandidateForShare、
               lockForStatusChange、countOtherUsableAdmins、updateActive、acquireAccountStatusLock）、
               AccountStatusService（新增，TD-025）、UserAdminController（deactivate/activate 路由）
  ticket/      Urgency（+resolutionHours/resolutionDuration）、ExpectedResolution（新增，SQL 表达式）、Overdue（新增）、
               TicketRepository（SELECT + expected_resolve_at）、Ticket/TicketDetail/TicketPage（+expectedResolveAt、overdue）、
               TicketQueryService（分派候选改用 findAssignableItStaff）、TicketCommandService（分派校验 active）、TicketController（请求内 now）
  report/      WeeklyReport（+overdueResolved）、WeeklyReportRepository（overdueResolvedCount）、WeeklyReportService
src/main/resources/
  db/migration/V2__account_active.sql   （新增）
  templates/tickets/list.html、tickets/detail.html、admin/users.html、admin/report.html
  static/css/app.css                    （超时标识样式 .overdue）
src/test/java/com/ithelpdesk/
  MigrationPolicyTest（扩展禁止项）、MigrationIT（当前版本断言改为 2，新增 active 列与默认值断言）
  ticket/ExpectedResolutionTest、OverdueTest、TicketExpectedTimeIT、TicketAssignmentIT（扩展停用分派）
  user/AccountStatusServiceTest、AccountStatusIT、UserAdminControllerTest（或 WebSlice）
  auth/AppUserDetailsServiceTest、RoleRefreshFilterTest（扩展）、DeactivatedLoginIT
  report/WeeklyReportIT（扩展第五项）、WeeklyReportTest
  config/DatabaseAuthModeCheckTest
  upgrade/UpgradeRehearsalIT、UpgradeInterruptionIT、UpgradeFixtures（V1 合成数据集与快照工具，供两者共用）
e2e/
  tests/v2-iqa-001-expected-time.spec.ts … v2-iqa-008-overdue-resolved-report.spec.ts（对应 V2 IQA-001～IQA-008；
               其中 v2-iqa-006-last-admin.spec.ts 归入 8081 一次性 schema 的 initial-admin project）
  tests/upgrade/v2-iqa-009-upgrade.spec.ts、tests/upgrade/v2-iqa-010-repeat-upgrade.spec.ts
  support/upgrade-seed.ts（V1 数据集写入与快照）、support/upgrade-snapshot.ts（比对工具）、support/upgrade.teardown.ts
  playwright.config.ts（新增 upgrade-seed / upgrade-verify / upgrade-repeat / upgrade-cleanup projects）
  V1 的 tests/iqa-0xx-*.spec.ts 保留为回归测试，只在 V2 行为确有变化处调整断言
```

组件边界规则（沿用 V1 并补充）：

- 控制器只做参数绑定、取本请求 `now`、调用服务、选择视图；超时判定只调用 `Overdue`，不在模板中写时间比较。
- 期望解决时间的时长只在 `Urgency` 中定义；任何 SQL 只能通过 `ExpectedResolution.sql(alias)` 获得表达式，禁止在其他位置手写 `interval '4 hours'` 等常量（`ExpectedResolutionTest` 校验生成的 SQL 与枚举一致）。
- 账号启用状态只由 `AccountStatusService` 写入；`UserAdminService` 不触碰 `active`。
- 迁移文件只做结构增量，不包含数据写入（TD-029）。

## 数据流、状态、并发与生命周期

### 关键流程（V2 新增或变化）

1. **工单列表 / 搜索 / 筛选**（`GET /tickets`）：控制器取 `now = clock.instant()` → `TicketQueryService.search` 执行 V1 SQL（可见范围与条件不变），`SELECT` 多出 `expected_resolve_at` → 每行 `overdue = Overdue.isOverdue(status, expectedResolveAt, now)` → 模板显示“期望解决时间”列与超时标识。员工“我的工单”、IT 同事“全部工单”、搜索与筛选结果是同一页面，因此一致显示（FR-003）。
2. **工单详情**（`GET /tickets/{id}`）：同上取 `now`，详情增加“期望解决时间”项与超时标识；授权逻辑（员工只能看本人，V1 TD-014）不变。
3. **提交工单**：不变；期望解决时间在提交成功后的首次读取即可见（派生）。页面不提供任何设置或修改期望解决时间的输入（FR-002、AC-002）。
4. **状态修改 / 认领 / 分派 / 沟通**：不改变 `submitted_at`、`urgency`，故期望解决时间不变；超时标识在下一次查看时按当时状态与时刻判定（FR-006）。
5. **分派**（`POST /tickets/{id}/assign`）：事务内 `lockForUpdate(ticket)` → `findAssigneeCandidateForShare(target)`：不存在或非 `IT_STAFF` 或为本人 → V1 提示；`active=false` → “该 IT 同事已停用，不能分派，请刷新后重新选择”；均回滚、工单不变 → 否则沿用 V1 写入与 `ASSIGN` 事件（TD-026）。详情页分派下拉只列 `findAssignableItStaff()` 结果（排除本人）。
6. **停用 / 启用**（`POST /admin/users/{id}/deactivate|activate`，TD-025、TD-027）：事务：advisory lock(7243012) → 目标行 `FOR UPDATE` → 状态相同则提示“账号已是停用状态/账号已是启用状态” → 停用可用管理员时计数其余可用管理员，0 则拒绝“系统必须至少保留一个可用的管理员” → `UPDATE app_user SET active=:v WHERE id=:id AND active=:old` → 提交 → 日志 INFO“账号已停用（用户 id=<目标>，操作者 id=<操作者>）”/“账号已启用（…）”。不写 `ticket_event`，不改任何工单（FR-012、INV-005、A-003）。
7. **登录**：`AppUserDetailsService` 返回的 `CurrentUser.isEnabled()` 为 `passwordHash != null && active`；停用账号即使凭据正确也得到 `/login?error` 与 V1 相同提示（TD-023）。重新启用后用原凭据即可登录（密码哈希从未改动，FR-010）。
8. **每个已认证请求**：`RoleRefreshFilter` 读取账号；不存在或已停用 → 清上下文、失效会话、302 `/login`；否则按 V1 刷新身份（TD-024）。
9. **周报表**：V1 四项不变；新增 `overdueResolvedCount(week)`（TD-028）。
10. **升级（应用启动）**：读取配置 → `DatabaseAuthModeCheck`（TD-031）→ 连接池 → Flyway：取 advisory lock → 校验已应用迁移校验和 → 若 history 最高版本为 1，则在单个事务中执行 V2（ADD COLUMN）并写 history → 释放锁 → 启动 Web。history 已含 2 时不执行任何迁移。任一步失败进程非零退出，事务回滚，库保持升级前状态。

### 状态机

- **账号**：`启用 ⇄ 停用`；启用→停用受 INV-004 约束；与身份（`role`）独立；V1 身份切换（EMPLOYEE↔IT_STAFF）对停用账号仍可执行（不影响启用状态）。
- **工单**：沿用 V1 四状态；“超时未解决”为派生标识，不是状态。

### 并发与生命周期

- 停用/启用全部经同一 advisory lock 串行；停用与分派通过 `app_user` 行上的 `FOR UPDATE`/`FOR SHARE` 互斥，先提交者生效：分派先提交则工单已分派给该同事（随后停用不改工单，FR-012）；停用先提交则分派被拒。
- 停用后，持有人所有已存在的会话在下一次请求时失效（每请求读库），不依赖会话注册表；内存会话在重新部署后本就丢失（V1）。
- 多个候选/实例同时启动时 Flyway advisory lock 保证 V2 只执行一次；仍在运行的旧版本（V1）实例忽略 `active` 列（新列有默认值，对 V1 写入透明，V1 TD-004 `*:future`）。

## Interface 与契约（Contract）

### HTTP 路由（只列 V2 新增或行为变化；其余同 V1 design）

| 方法与路径 | 允许身份 | 说明 | 追踪 |
|---|---|---|---|
| `GET /tickets` | 员工、IT 同事 | 每行新增“期望解决时间”列与超时标识；查询参数、分页、可见范围不变 | FR-003、FR-005 |
| `GET /tickets/{id}` | 员工（仅本人）、IT 同事 | 新增“期望解决时间”与超时标识；IT 同事的分派下拉只含启用状态的其他 IT 同事 | FR-003、FR-005、FR-011 |
| `POST /tickets/{id}/assign` | IT 同事 | 目标已停用 → 拒绝，PRG 回详情并 flashError，工单不变 | FR-011、AC-007 |
| `GET /admin/users` | 管理员 | 账号列表新增“状态”列与停用/启用按钮 | FR-008、FR-015 |
| `POST /admin/users/{id}/deactivate` | 管理员 | `_csrf`；成功 → 302 `/admin/users` + flash“已停用该账号”（停用自己时 200 直接渲染，TD-027）；已停用 → flash“账号已是停用状态”；最后一个可用管理员 → flashError“系统必须至少保留一个可用的管理员”；目标不存在 → flashError“账号不存在” | FR-007、FR-014、AC-005、AC-009 |
| `POST /admin/users/{id}/activate` | 管理员 | `_csrf`；成功 → 302 + flash“已启用该账号”；已启用 → flash“账号已是启用状态” | FR-007、FR-010 |
| 员工 / IT 同事访问上述 `/admin/**` | — | URL 层 403（`error/403.html`，V1 统一契约），服务层再次 `requireAdmin`；账号状态不变 | FR-015、AC-010 |
| `GET /admin/reports/weekly` | 管理员 | 新增第五项 | FR-017 |
| `POST /login` | 匿名 | 停用账号 → `/login?error`，提示同 V1 | FR-009 |
| 任意已认证请求（会话属于已停用账号） | — | 302 `/login`，会话失效，不执行请求 | FR-009 |
| `GET /actuator/health`、`GET /api/candidate-identity`、`/setup` | 同 V1 | 不变 | 平台、FR-021 |

### 统一行为契约（V2 补充）

- 停用/启用不显示、不回显任何凭据；成功提示不含登录名以外的账号信息（页面本就列出登录名）。
- 停用/启用失败（数据库异常）→ 事务回滚，HTTP 500“操作失败，请重试”（V1 契约），状态保持操作前（spec 错误表）。
- 超时标识文字为“已超时”，样式类 `overdue`（红底白字粗体标签，`app.css`），与未超时行明确区分；未超时或已解决/已关闭的工单不渲染该元素。
- 期望解决时间显示格式与提交时间相同（`yyyy-MM-dd HH:mm`，公司时区），对任何状态都显示。

### 页面自动化定位约定（V1 全部保留，V2 新增）

- 列表行（`ticket-row`）：`row-expected`（文本为期望解决时间，属性 `data-expected-at`）、`row-overdue`（仅超时时存在，文本“已超时”）；`ticket-row` 本身新增属性 `data-overdue="true|false"`。
- 详情：`ticket-expected`（属性 `data-expected-at`）、`ticket-overdue`（仅超时时存在）；详情根容器新增 `data-overdue`。
- 账号列表行（`user-row`）：`user-row-status`（文本“启用”/“停用”，属性 `data-active="true|false"`）、`user-deactivate`（启用账号上的按钮）、`user-activate`（停用账号上的按钮）。操作结果沿用 `flash`。
- 周报表：`report-overdue-resolved`（第五项数值）。
- 分派失败提示沿用详情页 `flash`。

### 数据库数据契约（供测试准备数据）

- V1 数据契约（V1 design“数据库数据契约”）全部沿用：开发者与 Independent QA 可用各自环境身份直连 `ticket-db` 的 `public` schema，按本次运行前缀插入回溯时间的工单（`submitted_at`、`status`、`first_resolved_at`、`first_resolved_assignee_id` 与对应 `STATUS` 事件）并按前缀清理；Independent QA 不以 SQL 写入或修改任何 `app_user` 行，账号经页面创建、停用与启用。
- V2 补充：工单行**没有**期望解决时间列，测试准备数据时只需写入 `submitted_at` 与 `urgency`，期望值按 `submitted_at + 4/24/72 小时` 独立计算；`app_user.active` 由页面操作改变，测试不直接写该列（开发者升级演练的 V1 数据集写入发生在 V2 迁移之前，此时该列尚不存在）。
- IQA-002 / IQA-008 的回溯数据示例：A 高紧急、`submitted_at = now - 5h`、`OPEN`；P1 高紧急、`submitted_at` 与 `first_resolved_at` 均在本周且 `first_resolved_at > submitted_at + 4h`；P3 的首次解决在上周、本周再次 `RESOLVED` 时只追加 `STATUS` 事件并更新 `status`，不改 `first_resolved_at`（与应用写入语义一致）。
- V1 持久升级基线（TD-034，只读）：前缀 `q15-72f2b9041e74-` 的 `app_user`、`category`、`ticket`、`ticket_comment`、`ticket_event` 行与快照工单是升级前数据基线，任何测试与 QA 运行都不得修改或清理（V2 QA 的前缀清理不得匹配该前缀；IQA-006 只停用 QA 自己创建的前缀管理员，基线账号不是管理员）。快照中的数据库摘要只覆盖 V1 列（`app_user` 的 `id,login_name,display_name,role` 等），V2 新增的 `active` 列不在摘要内，单独核对为 TRUE；V2 详情页在 V1 字段之外新增期望解决时间与超时标识，页面比对以快照中的 V1 字段为准，新增字段按 FR-020 独立核对。基线完全不存在（快照工单与全部前缀行均无）时，说明 `test` 库中从未建立基线或库被替换，属执行环境问题；快照存在而其引用的任何行缺失或改变，属产品缺陷。
- 只读核对（Independent QA 可用）：`SELECT version, success, installed_on, checksum FROM flyway_schema_history ORDER BY installed_rank`；`SELECT count(*) FROM app_user WHERE NOT active`；任意工单 `submitted_at`、`urgency` 用于独立计算期望值。

## 安全、隐私、身份与 Secret

- **认证与会话**：沿用 V1（BCrypt、HttpSession、CSRF、登录换 Session ID、Cookie `HttpOnly`/`SameSite=Lax`/`Secure`）。停用账号在认证（TD-023）与每个请求（TD-024）两处被拒；登录失败提示不区分“账号停用”“不存在”“密码错误”，不泄露账号状态。
- **授权（least privilege）**：`/admin/users/*/deactivate|activate` 在 URL 层要求 `ADMIN`，服务层 `requireAdmin` 再校验，且操作者本身必须为启用状态（由 TD-024 保证已停用的管理员无法到达服务层）；员工/IT 同事无入口且直接请求得 403（FR-015）。期望解决时间与超时标识只出现在用户原本有权查看的工单页面（员工只见本人，V1 TD-014）。
- **输入验证**：路径变量 `{id}` 为正整数，否则 400；目标账号存在性、启用状态、最后一个可用管理员、分派对象启用状态均在服务层事务内校验；SQL 全部参数化。
- **数据库身份**：`DefaultAzureCredential`（候选/QA 为工作负载身份，Agent Pod 为 Pod 身份），令牌受众 `https://ossrdbms-aad.database.windows.net/.default`，`sslmode=require`；`AIDH_DEP_TICKET_DB_AUTH_MODE` 只接受 `platform-identity`（TD-031），不读取也不需要 `AIDH_DEP_TICKET_DB_PASSWORD`。
- **机密**：`docs/dependencies.json` 的 `secrets` 不变：应用只读取 `AIDH_SECRET_INITIAL_SETUP_TOKEN`（V1 TD-019）；`AIDH_SECRET_INITIAL_ADMIN_*` 只供 Independent QA 登录与 IQA 场景使用（V1 TD-018）。V1 `SecretUsagePolicyTest` 保留。机密缺失时的行为不变：应用照常启动，`/setup` 无法完成（失败即关闭），平台在依赖与机密任务中请人提供。
- **日志隐私**：停用/启用日志只记录目标与操作者用户 id；不记录登录名、姓名、凭据、工单标题/描述/沟通内容；`RequestLogFilter` 不变。

## 配置、可观测性与运维

### 配置

- `application.yml`：V1 全部键值不变（数据源、Hikari、Flyway `baseline-on-migrate=true`/`baseline-version=0`、`app.time-zone=Asia/Shanghai`、`app.db.schema=public`、`app.setup.token=${AIDH_SECRET_INITIAL_SETUP_TOKEN:}` 等）。V2 不新增配置键；`spring.flyway.target` 不在配置文件中设置，只由开发者升级演练以命令行参数传入（TD-030），候选、QA、生产从不设置。
- 环境变量：只读取 `AIDH_DEP_TICKET_DB_HOST/PORT/DATABASE/USER/AUTH_MODE`、`AIDELIVERY_DELIVERY_ID`、`AIDELIVERY_CANDIDATE_SHA`、`AIDH_SECRET_INITIAL_SETUP_TOKEN`；不引入项目专用变量。
- 期望解决时间时长（4/24/72 小时）是业务口径常量（BQ-001 A），写在 `Urgency` 中，不做成可配置项（spec 范围外：按条件设定不同时限）。

### 可观测性

- 日志：stdout，INFO；新增停用/启用 INFO、分派因停用被拒 INFO“分派被拒绝：目标账号已停用（工单 id=<id>，目标 id=<id>）”、`DatabaseAuthModeCheck` 失败 ERROR；Flyway 启动日志自带“Migrating schema … to version 2”或“Schema … is up to date”，作为升级是否执行的运维证据。
- 健康：`/actuator/health`（含数据库）。不引入遥测后端或告警。

### 运维要点

- 升级无需任何手工步骤：部署 V2 镜像即在启动时完成；再次部署（同版本或更新版本）即“重复执行升级”，不会重复写入。
- 停用/启用、最后一个可用管理员保护由管理员在页面完成，无运维操作。

## SLO、限制、扩缩容、重试与恢复

- **SLO**：spec 无数值性能、可用率、升级时长或停机时长要求，本方案不设数值 SLO（spec“性能、可靠性与其他质量边界”）。
- **限制**：同 V1（列表每页 50 条、字段长度上限、连接池 10）；停用/启用一次只针对一个账号（A-006）。
- **扩缩容**：单副本（V1）。
- **重试**：业务写操作不自动重试；停用/启用失败整体回滚并提示重试。升级失败时进程退出，由平台重启后 Flyway 重新执行未完成的迁移。
- **恢复**：升级中断 → 事务回滚，库保持 V1 状态 → 再次启动即完成升级（AC-016）。V2 迁移只加列，不需要数据恢复步骤；数据库备份由平台托管服务承担。

## 部署拓扑、迁移、Rollout 与 Rollback

- **镜像与部署契约**：同 V1（多阶段 Dockerfile、`USER 10001`、`containerPort` 8080、`healthPath` `/actuator/health`、`candidateIdentityPath` `/api/candidate-identity`、`requiresPrivateNetwork` false）。部署契约写在 `.ai-delivery/features/002-v1/runtime/deployment.json`（由 Coding 编写），验证命令与 V1 相同，只把 `functionalTests` 指向 `bash .ai-delivery/features/002-v1/runtime/developer-functional-run.sh`：
  - `developerSetupCommands`：`mvn -B -ntp -q dependency:go-offline`、`cd e2e && npm ci`。
  - `developerValidationCommands`：`staticAnalysis` = `mvn -B -ntp checkstyle:check`；`build` = `mvn -B -ntp -DskipTests package`；`developerTests` = `mvn -B -ntp test`；`integrationTests` = `mvn -B -ntp verify -Pintegration -DskipUnitTests=true`；`functionalTests` = `bash .ai-delivery/features/002-v1/runtime/developer-functional-run.sh`。
- **拓扑**：命名空间 `cand-{deliveryShortId}` 中单个非 root 容器；出站只到 `ticket-db` 的 `test` 实例。
- **迁移策略**：
  - 新增 `V2__account_active.sql`（TD-029）；已提交迁移文件永不修改；只增不删、无数据写入。
  - 候选首次部署到 `test` 库即对该库（含 V1 IQA-015 持久基线）执行真实的 V1→V2 升级；之后的候选（缺陷修复）部署或对同一候选的受控重新部署时，Flyway 判定已是版本 2，不执行迁移、history 不新增行——这正是 IQA-010 在候选环境比对的“再次升级”结果（TD-034）。
  - 生产：部署 V2 镜像即对线上 V1 库执行同一迁移；线上真实升级结果属发布阶段平台证据（spec 测试策略）。
- **Rollout**：平台构建镜像 → 部署候选 → 启动迁移 → health 与 candidate-identity 校验。
- **Rollback**：回滚到 V1 镜像即可运行（V1 忽略 `active` 列；新列有默认值，V1 写入照常）；不提供 schema 回退脚本（回退会删除数据）。回滚期间 V1 不识别停用状态，已停用账号在 V1 上可以登录；再次部署 V2 后停用状态原样生效（列值从未被 V1 修改）。回滚属于发布阶段的人工决定，Runbook 中列明该影响。

## 验证环境责任矩阵（Verification Environment）

| 验证类型 | 责任阶段 | 环境来源 | 依赖/凭据 | Coding 证据 | 下游结果 |
|---|---|---|---|---|---|
| 静态分析（Checkstyle） | Coding 自测 + 候选验证 | Agent Pod / Runner | 无 | `mvn checkstyle:check` 输出 | `staticAnalysis` 通过 |
| 构建 | Coding 自测 + 候选验证 | Agent Pod / Runner | 无 | `mvn package` 输出 | `build` 通过；ACR 镜像构建 |
| 单元/切片测试：`ExpectedResolutionTest`（三种紧急程度 4/24/72 小时、跨夜/跨周末/跨月不顺延、生成 SQL 与枚举一致）；`OverdueTest`（四种状态 × 早于/恰好等于/晚于）；`AccountStatusServiceTest`（非管理员拒绝、已是该状态不写、最后可用管理员含自己拒绝、NULL 哈希 ADMIN 不计入可用管理员）；`RoleRefreshFilterTest`（停用 → 302 `/login` 且会话失效，GET 与 POST）；`AppUserDetailsServiceTest`（停用 → 禁用用户）；Web 切片：员工/IT 同事访问 deactivate/activate 得 403、CSRF 生效、自我停用 200 渲染；`DatabaseAuthModeCheckTest`；`MigrationPolicyTest`（禁止写语句）；V1 既有单元测试 | Coding 自测 + 候选验证 | Agent Pod / Runner | 无（纯逻辑，替身不计入功能证据） | `mvn test` 报告 | `developerTests` 通过 |
| 集成测试（真实 `dev` 库，`public` schema 以运行前缀隔离，报表用历史周测试时钟）：`MigrationIT`（版本为 2、`active` 列存在且默认 TRUE、再次 migrate 执行 0 个）；`TicketExpectedTimeIT`（列表/搜索/筛选/详情 SQL 返回的期望值、操作前后不变）；`TicketAssignmentIT` 扩展（停用目标被拒且工单与事件不变、并发停用与分派只按顺序生效）；`AccountStatusIT`（停用/启用不改其他列、两个管理员并发互相停用只成功一个、停用后历史工单/沟通/处理记录/按处理人筛选/报表处理量照常）；`DeactivatedLoginIT`（停用后正确凭据登录与错误凭据得到同一 `/login?error`、已登录会话下一次请求被重定向、启用后原凭据可登录）；`WeeklyReportIT` 扩展（第五项：恰好等于、上周首次解决本周再解决、只关闭、解决后改回、无数据为 0；V1 四项结果不变）；`UpgradeRehearsalIT`（一次性 schema `it_upgrade_<runId>`：Flyway `target=1` → 写入 V1 数据集 → 全表快照（V1 全部列，按主键排序）→ migrate → 快照逐行逐列相等、新增列全为 TRUE、history 有且仅有版本 1、2 → 再 migrate 执行 0 个、快照仍相等 → `DROP SCHEMA … CASCADE`）；`UpgradeInterruptionIT`（一次性 schema：V1 数据集就绪后，测试连接对 `app_user` 持 `ACCESS EXCLUSIVE` 锁使 V2 迁移阻塞，再以 `pg_terminate_backend` 终止迁移连接 → 断言迁移失败、history 最高版本仍为 1、`active` 列不存在、快照不变 → 释放锁重跑 migrate → 结果与另一 schema 中一次成功升级的结果（结构与全部数据）相同；若 `dev` 角色无权终止自身会话，则改为关闭迁移所用 DataSource 的物理连接达到同一效果，不使用替身） | Coding 自测 + 候选验证 | Agent Pod / Runner，`ticket-db` `dev` 实例 | `AIDH_DEP_TICKET_DB_*` + Pod/Agent 身份 Entra 令牌 | `mvn verify -Pintegration` 报告；运行前缀与 schema 清理记录 | `integrationTests` 通过 |
| 功能测试（Playwright Chromium，localhost）：8080 实例（`public` schema）跑 V1 回归用例与 V2 `v2-iqa-001`～`v2-iqa-008`（三种身份；回溯工单由 `e2e/support/db.ts` 插入）；8081 实例（一次性 schema）先跑 V1 IQA-016 回归，随后在同一 schema 上跑 `v2-iqa-006-last-admin.spec.ts`——此时该 schema 中恰有一个可用管理员（IQA-016 刚设置的那个），完整走通 IQA-006 六个步骤（`initial-admin` project 内按文件顺序执行）；8082 实例跑升级演练 `v2-iqa-009`/`v2-iqa-010`（见下行） | Coding 自测 + 候选验证 | Agent Pod / Runner，localhost:8080/8081/8082 + `dev` 实例 | 同上；合成管理员种子与随机密码只在进程环境 | `developer-functional-run.sh` 输出与 Playwright 报告 | `functionalTests` 通过 |
| 升级演练（IQA-009、IQA-010 的开发者执行方式，TD-030）：一次性 schema `e2e_upgrade_<suffix>`；① 以 `--app.db.schema=<s> --spring.flyway.target=1 --server.port=8082` 启动 V2 jar，health UP 后停止（schema 为 V1 结构，history 只有版本 1）；② `upgrade-seed` project 以 SQL 写入 V1 数据集（员工/IT 同事/管理员各至少一个且有已知随机密码的 BCrypt 哈希、被取消 IT 同事身份的账号、有效/已移除/改名类别、三种紧急程度 × 四种状态 × 有无处理人、已超期与未超期的未解决工单、含补充说明/回复/认领/分派/状态事件的工单、本周首次已解决工单），并保存 V1 全列快照与按 V1 口径计算的报表四项期望值到临时目录；③ 以默认配置（无 target）在同一 schema 启动 → 升级；`upgrade-verify` project：各身份原凭据登录、账号列表全部“启用”、各身份可见工单列表与详情（含沟通与处理记录）与快照一致、员工访问他人工单 403、每张既有工单期望解决时间 = 提交时间 + 4/24/72 小时、超时标识只出现在超期未解决工单、报表四项等于期望值且第五项符合 FR-017、`/setup` 403；SQL 快照比对 V1 列逐行相等；④ 在 V2 中新增数据（新工单与回复、停用一个账号、把一张既有工单改为已解决）并保存快照；⑤ 停止并以相同参数重启（重复升级），`upgrade-repeat` project 逐项比对：无重复行、期望解决时间不变、停用账号仍停用且不能登录、新增数据完整；⑥ 清理 schema | Coding 自测 + 候选验证（每个候选都执行） | Agent Pod / Runner，localhost:8082 + `dev` 实例 | 同上 | Playwright 报告、快照比对输出（只含 id 与列名差异，不含凭据） | `functionalTests` 通过 |
| 候选部署校验（镜像非 root、health、candidate-identity、启动迁移成功） | 候选验证 | `cand-{deliveryShortId}` + `test` 实例 | 候选工作负载身份 | 无（平台执行） | 平台部署记录 |
| Independent QA IQA-001～IQA-008 | Independent QA | 已部署候选 HTTPS + `test` 实例 `public` schema | QA 工作负载身份；`AIDH_SECRET_INITIAL_ADMIN_*` 登录初始管理员；其他账号经页面创建；回溯工单按数据契约插入并清理。IQA-006 的 A1 为机密中的初始管理员；前置“仅有一个可用管理员”由 QA 以 A1 经账号管理页面停用历次 QA 运行创建的前缀管理员达成（只经页面，不改库）；若库中还有非 QA 创建的其他可用管理员，QA 不处置他人账号，按平台归类作为执行环境问题记录诊断（`passed=false`，defects、testGaps 为空）；步骤 6 后 A1 恢复启用，测试环境保持初始管理员可用 | 无（Coding 不编写 `qa-run.sh`） | `qa-result.json` |
| Independent QA IQA-009（TD-034） | Independent QA：以 V1 IQA-015 持久基线（前缀 `q15-72f2b9041e74-`）为升级前快照，核对 `test` 库的真实 V1→V2 升级：基线账号用原凭据登录、身份不变、账号列表中为“启用”；基线类别、三张工单的页面 V1 字段（状态、处理人、提交时间、沟通记录、处理记录）与快照逐项一致，数据库 V1 列摘要与快照一致；基线员工“我的工单”完整且不能访问他人工单；基线工单期望解决时间 = 提交时间 + 4/24/72 小时、超时标识按状态与当前时刻正确；`flyway_schema_history` 版本 1 早于版本 2、版本 2 成功且只出现一次、快照建立候选 SHA 与当前候选 SHA 不同；V1 时期设置的初始管理员用机密中的原凭据登录且为“启用”；报表四项与按 V1 口径的独立 SQL 一致、第五项符合 FR-017；`/setup` 不可用 | 已部署候选 HTTPS + `test` 实例 | QA 工作负载身份；`AIDH_SECRET_INITIAL_ADMIN_*`；基线凭据由快照推导（不输出）；只读 SQL | 开发者升级演练报告（辅助证据，供 QA 与 UAT 引用） | `qa-result.json`；线上真实升级为发布阶段平台证据 |
| Independent QA IQA-010（TD-034） | Independent QA：在 V2 中新增数据（新工单与回复、停用一个前缀合成账号、把一张前缀合成工单改为已解决）并保存快照（含账号状态、工单期望解决时间与超时标识、沟通与处理记录、报表，及 `flyway_schema_history` 全部行）。平台提供受控重新部署能力时：QA 计划声明在 IQA-010 之前重新部署同一候选，重新部署后逐项比对快照（无重复行、期望解决时间不变、停用账号仍停用且不能登录、新增数据与 V1 基线完整、history 行数与内容不变），并引用平台部署证据；能力不可用时：只做上述快照数据与 history 的只读核对（版本 2 只成功一次、无失败行），再次升级与中断重跑以开发者升级演练为证据，结果中如实说明未执行重新部署 | 已部署候选 HTTPS + `test` 实例（+ 平台受控重新部署，可用时） | 同上 | 同上 | `qa-result.json`；平台重新部署证据（可用时） |

替身使用说明：`ticket-db` 的 `dev`、`test` 均为真实实例（`realInstance` 均为 true），集成测试、功能测试与升级演练不得使用数据库替身；单元测试中的替身只用于纯逻辑，不计入 functional/integration 证据。本交付没有使用模拟服务的依赖。

## 快速验证指南（Quickstart）

在 Agent Pod 中（平台已注入 `AIDH_DEP_TICKET_DB_*`）：

```bash
mvn -B -ntp checkstyle:check
mvn -B -ntp -DskipTests package
mvn -B -ntp test
mvn -B -ntp verify -Pintegration -DskipUnitTests=true
bash .ai-delivery/features/002-v1/runtime/developer-functional-run.sh
```

`.ai-delivery/features/002-v1/runtime/developer-functional-run.sh` 的职责（由 Coding 以 V1 脚本为基础编写）：

1. 校验 `AIDH_DEP_TICKET_DB_*`；检查 8080/8081/8082 未占用；生成运行前缀、种子管理员随机密码、升级演练账号随机密码、32 字符合成设置口令（只在进程环境，不输出）；派生 `e2e_setup_<suffix>` 与 `e2e_upgrade_<suffix>`。
2. 构建 jar；`npm ci`（如需要）。
3. 启动 8080（`public`）与 8081（`e2e_setup_<suffix>` + 合成口令），health UP 后运行 `chromium` 与 `initial-admin` projects。
4. 升级演练：8082 以 `--spring.flyway.target=1` 启动至 UP 后停止 → `upgrade-seed` → 8082 以默认配置启动至 UP（日志须含迁移到版本 2）→ `upgrade-verify` → 停止并重启 8082 至 UP（日志须含 up to date）→ `upgrade-repeat`。
5. 无论成败：运行 `cleanup`、`initial-admin-cleanup`、`upgrade-cleanup`（`DROP SCHEMA e2e_upgrade_… CASCADE`）→ 停止全部实例 → 以首个失败的退出码退出。

集成测试数据隔离：账号与类别用 `it-<runId>-` 前缀并在 `@AfterAll` 清理；报表与超时测试用可变测试时钟设到随机历史周；升级测试与 `AccountStatusIT` 中“唯一可用管理员”用例使用一次性 schema（`it_upgrade_<runId>`、`it_admin_<runId>`）并在结束时 `DROP SCHEMA … CASCADE`（V1 TD-016 机制，`app.db.schema` 只由测试设置）。

## 支持 Runbook

| 现象 | 排查 | 处置 |
|---|---|---|
| 部署 V2 后启动失败、health 不通 | 容器日志中 Flyway 或连接错误；`flyway_schema_history` 最高版本 | 版本仍为 1 且日志为连接/令牌错误：确认依赖绑定与身份权限后重新部署（迁移会重新执行）；校验和错误：说明有人修改了已提交的迁移文件，恢复原文件后重新部署 |
| 启动日志“ticket-db 认证方式 … 未在本版本实现” | 依赖绑定选择的认证方式 | 本版本只实现 `platform-identity`；改回该方式绑定，或作为需求变更提出 |
| 用户反映“账号无法登录，提示登录名或密码错误” | 管理员在账号列表看该账号状态 | 状态为“停用”：如需恢复，由管理员点击“启用”；状态为“启用”：按凭据错误处理 |
| 管理员停用账号时提示“系统必须至少保留一个可用的管理员” | 预期行为（FR-014） | 先创建或启用另一个管理员账号，再执行停用 |
| IT 同事分派时提示“该 IT 同事已停用” | 预期行为（FR-011） | 刷新页面，从当前分派对象中重新选择 |
| 停用的 IT 同事名下仍有未解决工单 | 预期行为（FR-012、BQ-003 A） | 其他 IT 同事认领或重新分派 |
| 工单显示的期望解决时间与预期不符 | 核对提交时间与紧急程度；`app.time-zone` | 期望 = 提交时间 + 4/24/72 小时（自然时间）；页面按公司时区显示 |
| 报表第五项与手工计算不符 | 以 TD-028 SQL 在同一时刻计算；确认周边界（周一 00:00 起，左闭右开） | 核对首次已解决时刻是否在本周且严格晚于期望解决时间 |
| 回滚到 V1 后已停用账号可以登录 | V1 不识别停用状态（见 Rollback） | 预期的回滚影响；重新部署 V2 后停用状态原样恢复，无需改库 |
| `/setup` 在升级后仍显示“此入口不可用” | 预期行为（FR-021、TD-032） | 使用已有管理员账号登录 |

## 开放决策

- **DEC-TS-V2-001（已由人工答复）**：IQA-009、IQA-010 在候选环境中的执行方式。人工澄清答案 HT-DLV-20261117-88E4DAE97F6A-0005 选 A，并补充：(1) 升级前数据基线使用 V1 交付 Independent QA 留下的 IQA-015 deferred baseline，QA 逐项核对其中账号、类别、工单、沟通与处理记录仍然存在且内容未变；(2) 平台受控重新部署能力可用时，IQA-010 的再次升级改为由 QA 计划声明在 IQA-010 之前重新部署同一候选并比对前后数据与迁移历史；“部署 V1”不是平台能力，不作要求。本设计已按此落实（TD-030、TD-034、数据契约、验证矩阵）。
- 该答案要求修订 spec 的 IQA-009、IQA-010 与测试策略（当前原文仍以“测试环境先部署 V1、再升级、再次升级”为前置），本 Session 不能修改 spec，故以回退 `specification` 提出；spec 修订后本设计无需再改。
- 除此之外无未决技术决定。
