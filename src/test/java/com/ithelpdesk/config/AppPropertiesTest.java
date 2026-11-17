package com.ithelpdesk.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** 配置绑定：app.db.schema 默认 public、不受 AIDELIVERY_* 影响、非法值启动失败；app.setup.token 默认空（TD-016、TD-017、TD-019）。 */
class AppPropertiesTest {

    @Configuration
    @EnableConfigurationProperties(AppProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(Config.class);

    @Test
    void schemaDefaultsToPublic() {
        runner.run(ctx -> assertThat(ctx.getBean(AppProperties.class).db().schema()).isEqualTo("public"));
    }

    @Test
    void deploymentIdentityDoesNotChangeSchema() {
        runner.withPropertyValues("AIDELIVERY_DELIVERY_ID=DLV-TEST-1", "AIDELIVERY_CANDIDATE_SHA=0123abcd")
            .run(ctx -> assertThat(ctx.getBean(AppProperties.class).db().schema()).isEqualTo("public"));
    }

    @Test
    void validDisposableSchemaIsAccepted() {
        runner.withPropertyValues("app.db.schema=it_setup_ab12cd34")
            .run(ctx -> assertThat(ctx.getBean(AppProperties.class).db().schema()).isEqualTo("it_setup_ab12cd34"));
    }

    @Test
    void invalidSchemaFailsStartup() {
        for (String bad : new String[] {"Public", "it-setup", "a;drop", "1abc", "", "a".repeat(64)}) {
            runner.withPropertyValues("app.db.schema=" + bad)
                .run(ctx -> assertThat(ctx).as("schema=%s", bad).hasFailed());
        }
    }

    @Test
    void setupTokenDefaultsToEmptyWithoutSecret() {
        String injected = System.getenv("AIDH_SECRET_INITIAL_SETUP_TOKEN");
        runner.run(ctx -> {
            AppProperties props = ctx.getBean(AppProperties.class);
            if (injected == null) {
                assertThat(props.setup().token()).isEmpty();
            } else {
                assertThat(props.setup().token()).isEqualTo(injected);
            }
        });
        new ApplicationContextRunner().withUserConfiguration(Config.class)
            .run(ctx -> assertThat(ctx.getBean(AppProperties.class).setup().token()).isEmpty());
    }

    @Test
    void toStringDoesNotExposeSetupToken() {
        String token = "synthetic-token-" + System.nanoTime();
        runner.withPropertyValues("app.setup.token=" + token).run(ctx -> {
            AppProperties props = ctx.getBean(AppProperties.class);
            assertThat(props.setup().token()).isEqualTo(token);
            assertThat(props.toString()).doesNotContain(token);
            assertThat(props.setup().toString()).doesNotContain(token);
        });
    }
}
