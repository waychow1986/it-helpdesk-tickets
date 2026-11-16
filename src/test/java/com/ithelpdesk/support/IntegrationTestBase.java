package com.ithelpdesk.support;

import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 集成测试基类：完整 Spring 上下文连接真实 ticket-db dev 实例（不使用任何数据库替身）；
 * 每个测试类使用唯一运行前缀，@AfterAll 按前缀清理。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("it")
@Import(TestClockConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class IntegrationTestBase {

    @Autowired
    protected NamedParameterJdbcTemplate jdbc;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected MockMvc mvc;

    protected TestData data;

    @BeforeAll
    void createRunPrefix() {
        data = new TestData(jdbc, RunPrefix.newPrefix());
    }

    @AfterAll
    void cleanupRunData() {
        data.cleanup();
        clock.set(Instant.now());
    }
}
