package com.ithelpdesk.support;

import com.ithelpdesk.auth.AppUserDetailsService;
import com.ithelpdesk.config.ClockConfig;
import com.ithelpdesk.config.SecurityConfig;
import com.ithelpdesk.web.TimeFormat;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

/** Web 切片测试共享配置：真实安全配置、真实 UserDetailsService（仓库为替身）、时钟与时间格式化（不连接数据库）。 */
@TestConfiguration
@Import({SecurityConfig.class, ClockConfig.class, TimeFormat.class, AppUserDetailsService.class})
public class WebSliceTestConfig {
}
