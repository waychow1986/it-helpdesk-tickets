package com.ithelpdesk.config;

import com.ithelpdesk.auth.RoleRefreshFilter;
import com.ithelpdesk.user.UserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/** 表单登录 + HttpSession + CSRF + URL 授权（TD-005、design「安全、隐私、身份与 Secret」）。 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    public static final String CSP = "default-src 'self'";

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, UserRepository users) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/login", "/actuator/health", "/api/candidate-identity", "/css/**", "/error")
                    .permitAll()
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET, "/tickets/new").hasRole("EMPLOYEE")
                .requestMatchers(HttpMethod.POST, "/tickets").hasRole("EMPLOYEE")
                .requestMatchers("/tickets/*/claim", "/tickets/*/assign", "/tickets/*/status").hasRole("IT_STAFF")
                .requestMatchers("/tickets", "/tickets/**").hasAnyRole("EMPLOYEE", "IT_STAFF")
                .anyRequest().authenticated())
            .formLogin(form -> form
                .loginPage("/login")
                .loginProcessingUrl("/login")
                .defaultSuccessUrl("/", true)
                .failureUrl("/login?error")
                .permitAll())
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/login?logout")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID"))
            .sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
            .headers(headers -> headers
                .frameOptions(frame -> frame.deny())
                .contentSecurityPolicy(csp -> csp.policyDirectives(CSP)))
            .addFilterBefore(new RoleRefreshFilter(users), AuthorizationFilter.class);
        return http.build();
    }
}
