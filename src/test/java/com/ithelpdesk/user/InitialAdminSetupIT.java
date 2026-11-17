package com.ithelpdesk.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.ithelpdesk.HelpdeskApplication;
import com.ithelpdesk.auth.AppUserDetailsService;
import com.ithelpdesk.auth.CurrentUser;
import com.ithelpdesk.support.IntegrationTestBase;
import com.ithelpdesk.support.RunPrefix;
import com.ithelpdesk.support.TestClockConfig;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 一次性“设置初始管理员”在真实 dev 实例上的完整行为（FR-031、AC-021、IQA-016；TD-011、TD-016、TD-017、TD-019）：
 * 在一次性 schema it_setup_&lt;runId&gt; 中以独立应用上下文启动（Flyway 自动建表），结束时 DROP SCHEMA ... CASCADE。
 * 不使用任何数据库替身；设置口令为运行时随机合成值，不输出。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InitialAdminSetupIT extends IntegrationTestBase {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final String runId = randomHex(4);
    private final String schema = "it_setup_" + runId;
    private final String schemaNoToken = "it_setup_" + runId + "_nt";
    private final String token = "Tk-" + randomHex(16);
    private final String adminPassword = RunPrefix.randomPassword();

    private ConfigurableApplicationContext ctx;
    private ConfigurableApplicationContext ctxNoToken;
    private MockMvc setupMvc;
    private NamedParameterJdbcTemplate setupJdbc;

    private static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    @BeforeAll
    void startDisposableSchemaContexts() {
        // TD-016 前置条件：当前身份可在 dev 实例创建并删除一次性 schema（被拒绝时测试失败并给出原因，不改用替身）。
        String probe = schema + "_probe";
        try {
            jdbc.getJdbcOperations().execute("CREATE SCHEMA " + probe);
            jdbc.getJdbcOperations().execute("DROP SCHEMA " + probe + " CASCADE");
        } catch (RuntimeException e) {
            throw new IllegalStateException("当前身份无法在 ticket-db dev 实例创建一次性 schema（TD-016 前置条件不满足）", e);
        }
        ctx = start(schema, token);
        ctxNoToken = start(schemaNoToken, "");
        setupMvc = mvcOf(ctx);
        setupJdbc = ctx.getBean(NamedParameterJdbcTemplate.class);
    }

    @AfterAll
    void dropDisposableSchemas() {
        for (ConfigurableApplicationContext c : new ConfigurableApplicationContext[] {ctx, ctxNoToken}) {
            if (c != null) {
                c.close();
            }
        }
        jdbc.getJdbcOperations().execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        jdbc.getJdbcOperations().execute("DROP SCHEMA IF EXISTS " + schemaNoToken + " CASCADE");
        Long left = jdbc.queryForObject("SELECT COUNT(*) FROM pg_namespace WHERE nspname IN (:a, :b)",
            new MapSqlParameterSource().addValue("a", schema).addValue("b", schemaNoToken), Long.class);
        assertThat(left).isZero();
    }

    private static ConfigurableApplicationContext start(String schemaName, String setupToken) {
        return new SpringApplicationBuilder(HelpdeskApplication.class, TestClockConfig.class)
            .web(WebApplicationType.SERVLET)
            .profiles("it")
            .run("--server.port=0", "--app.db.schema=" + schemaName, "--app.setup.token=" + setupToken);
    }

    private static MockMvc mvcOf(ConfigurableApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup((WebApplicationContext) context).apply(springSecurity()).build();
    }

    private static MockHttpServletRequestBuilder setupPost(String address, String tokenValue, String login,
                                                           String password, String display) {
        return post("/setup").with(csrf()).with(r -> {
            r.setRemoteAddr(address);
            return r;
        }).param("setupToken", tokenValue).param("loginName", login).param("password", password)
            .param("displayName", display);
    }

    private long userCount(NamedParameterJdbcTemplate template) {
        return template.queryForObject("SELECT COUNT(*) FROM app_user", Map.of(), Long.class);
    }

    private boolean open(ConfigurableApplicationContext context) {
        return context.getBean(InitialAdminSetupService.class).isSetupOpen();
    }

    private MockHttpServletResponse perform(MockMvc target, MockHttpServletRequestBuilder request) throws Exception {
        return target.perform(request).andReturn().getResponse();
    }

    @Test
    @Order(1)
    void freshSchemaIsMigratedOpenAndStartupWroteNoAccount() throws Exception {
        assertThat(setupJdbc.queryForObject("SELECT current_schema()", Map.of(), String.class)).isEqualTo(schema);
        assertThat(userCount(setupJdbc)).isZero();
        assertThat(open(ctx)).isTrue();
        assertThat(perform(setupMvc, get("/login")).getContentAsString()).contains("data-testid=\"setup-link\"");
        MockHttpServletResponse form = perform(setupMvc, get("/setup"));
        assertThat(form.getStatus()).isEqualTo(200);
        assertThat(form.getContentAsString()).contains("data-testid=\"setup-token\"")
            .doesNotContain("setup-token-missing");
    }

    @Test
    @Order(2)
    void emptyOrWrongTokenCreatesNothing() throws Exception {
        String login = data.name("root-a");
        for (String bad : new String[] {"", "Wrong-" + token}) {
            MockHttpServletResponse r = perform(setupMvc,
                setupPost("192.0.2.1", bad, login, adminPassword, "合成管理员"));
            assertThat(r.getStatus()).isEqualTo(200);
            assertThat(r.getContentAsString()).contains("data-testid=\"field-error-setupToken\"")
                .doesNotContain(adminPassword).doesNotContain(token);
        }
        assertThat(userCount(setupJdbc)).isZero();
        assertThat(open(ctx)).isTrue();
    }

    @Test
    @Order(3)
    void sixthAttemptAfterFiveWrongTokensIsThrottledEvenWithCorrectToken() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(perform(setupMvc, setupPost("192.0.2.2", "Wrong-" + i, data.name("root-b"), adminPassword,
                "合成管理员")).getStatus()).isEqualTo(200);
        }
        MockHttpServletResponse r = perform(setupMvc,
            setupPost("192.0.2.2", token, data.name("root-b"), adminPassword, "合成管理员"));
        assertThat(r.getStatus()).isEqualTo(429);
        assertThat(r.getContentAsString()).contains("data-testid=\"setup-throttled\"");
        assertThat(userCount(setupJdbc)).isZero();
        assertThat(open(ctx)).isTrue();
    }

    @Test
    @Order(4)
    void blankRequiredFieldsCreateNothing() throws Exception {
        MockHttpServletResponse r = perform(setupMvc, setupPost("192.0.2.3", token, "  ", "", " "));
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(r.getContentAsString()).contains("field-error-loginName", "field-error-password",
            "field-error-displayName");
        assertThat(userCount(setupJdbc)).isZero();
        assertThat(open(ctx)).isTrue();
    }

    @Test
    @Order(5)
    void legacyNullHashAdminDoesNotCloseAndIsNotModified() throws Exception {
        String legacy = data.name("legacy");
        setupJdbc.update("INSERT INTO app_user(login_name, password_hash, display_name, role, created_at) "
            + "VALUES (:l, NULL, '遗留管理员', 'ADMIN', now())", Map.of("l", legacy));
        assertThat(open(ctx)).isTrue();
        MockHttpServletResponse r = perform(setupMvc,
            setupPost("192.0.2.3", token, legacy, adminPassword, "接管尝试"));
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(r.getContentAsString()).contains("登录名已存在");
        Map<String, Object> row = setupJdbc.queryForMap(
            "SELECT password_hash, display_name, role FROM app_user WHERE login_name = :l", Map.of("l", legacy));
        assertThat(row.get("password_hash")).isNull();
        assertThat(row.get("display_name")).isEqualTo("遗留管理员");
        assertThat(userCount(setupJdbc)).isEqualTo(1);
        assertThat(open(ctx)).isTrue();
    }

    @Test
    @Order(6)
    void concurrentSubmissionsCreateExactlyOneAdminWhichCanAuthenticate() throws Exception {
        String first = data.name("root-c1");
        String second = data.name("root-c2");
        CountDownLatch go = new CountDownLatch(1);
        List<CompletableFuture<Integer>> calls = List.of(first, second).stream().map(login ->
            CompletableFuture.supplyAsync(() -> {
                try {
                    go.await(10, TimeUnit.SECONDS);
                    return perform(setupMvc, setupPost("192.0.2.4", token, login, adminPassword, "合成管理员"))
                        .getStatus();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            })).toList();
        go.countDown();
        List<Integer> statuses = calls.stream().map(CompletableFuture::join).sorted().toList();
        assertThat(statuses).containsExactly(302, 403);

        List<Map<String, Object>> admins = setupJdbc.queryForList(
            "SELECT login_name, password_hash, created_by FROM app_user WHERE role = 'ADMIN' "
                + "AND password_hash IS NOT NULL", Map.of());
        assertThat(admins).hasSize(1);
        Map<String, Object> admin = admins.get(0);
        assertThat((String) admin.get("password_hash")).startsWith("$2a$10$");
        assertThat(admin.get("created_by")).isNull();
        assertThat(open(ctx)).isFalse();

        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(ctx.getBean(AppUserDetailsService.class));
        provider.setPasswordEncoder(ctx.getBean(PasswordEncoder.class));
        CurrentUser principal = (CurrentUser) provider.authenticate(UsernamePasswordAuthenticationToken
            .unauthenticated((String) admin.get("login_name"), adminPassword)).getPrincipal();
        assertThat(principal.getRole()).isEqualTo(Role.ADMIN);
    }

    @Test
    @Order(7)
    void closedFlowRejectsEverythingAndChangesNothing() throws Exception {
        List<Map<String, Object>> before = setupJdbc.queryForList(
            "SELECT id, login_name, password_hash, display_name, role FROM app_user ORDER BY id", Map.of());
        String existing = setupJdbc.queryForObject("SELECT login_name FROM app_user WHERE role = 'ADMIN' "
            + "AND password_hash IS NOT NULL", Map.of(), String.class);
        assertThat(perform(setupMvc, get("/setup")).getStatus()).isEqualTo(403);
        assertThat(perform(setupMvc, get("/login")).getContentAsString()).doesNotContain("setup-link");
        for (String login : new String[] {data.name("root-late"), existing}) {
            MockHttpServletResponse r = perform(setupMvc,
                setupPost("192.0.2.5", token, login, RunPrefix.randomPassword(), "接管尝试"));
            assertThat(r.getStatus()).isEqualTo(403);
            assertThat(r.getContentAsString()).contains("data-testid=\"setup-closed\"");
        }
        assertThat(setupJdbc.queryForList(
            "SELECT id, login_name, password_hash, display_name, role FROM app_user ORDER BY id", Map.of()))
            .isEqualTo(before);
    }

    @Test
    @Order(8)
    void withoutConfiguredTokenNoSubmissionCreatesAnAccount() throws Exception {
        MockMvc noTokenMvc = mvcOf(ctxNoToken);
        NamedParameterJdbcTemplate noTokenJdbc = ctxNoToken.getBean(NamedParameterJdbcTemplate.class);
        assertThat(noTokenJdbc.queryForObject("SELECT current_schema()", Map.of(), String.class))
            .isEqualTo(schemaNoToken);
        assertThat(perform(noTokenMvc, get("/setup")).getContentAsString())
            .contains("data-testid=\"setup-token-missing\"");
        for (String anyToken : new String[] {"", token, "Some-Other-Token-123456"}) {
            MockHttpServletResponse r = perform(noTokenMvc,
                setupPost("192.0.2.6", anyToken, data.name("root-nt"), adminPassword, "合成管理员"));
            assertThat(r.getStatus()).isEqualTo(200);
            assertThat(r.getContentAsString()).contains("此部署未提供设置口令，暂不能完成设置");
        }
        assertThat(userCount(noTokenJdbc)).isZero();
        assertThat(open(ctxNoToken)).isTrue();
    }
}
