package com.ithelpdesk.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ithelpdesk.user.AppUser;
import com.ithelpdesk.user.Role;
import com.ithelpdesk.user.UserRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class AppUserDetailsServiceTest {

    private final UserRepository users = mock(UserRepository.class);
    private final AppUserDetailsService service = new AppUserDetailsService(users);

    @Test
    void nullHashAdminIsDisabled() {
        when(users.findByLoginName("admin")).thenReturn(Optional.of(
            new AppUser(1, "admin", null, "初始管理员", Role.ADMIN, Instant.EPOCH, null)));
        UserDetails details = service.loadUserByUsername("admin");
        assertThat(details.isEnabled()).isFalse();
    }

    @Test
    void normalAccountIsEnabled() {
        when(users.findByLoginName("e1")).thenReturn(Optional.of(
            new AppUser(2, "e1", "$2a$10$abc", "员工", Role.EMPLOYEE, Instant.EPOCH, 1L)));
        UserDetails details = service.loadUserByUsername("e1");
        assertThat(details.isEnabled()).isTrue();
        assertThat(details.getAuthorities()).extracting("authority").containsExactly("ROLE_EMPLOYEE");
    }

    @Test
    void unknownAccountThrowsAuthenticationException() {
        when(users.findByLoginName("nobody")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.loadUserByUsername("nobody"))
            .isInstanceOf(UsernameNotFoundException.class)
            .isInstanceOf(AuthenticationException.class);
    }

    @Test
    void unknownAccountAndNullHashBothFailAuthentication() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
        when(users.findByLoginName("admin")).thenReturn(Optional.of(
            new AppUser(1, "admin", null, "初始管理员", Role.ADMIN, Instant.EPOCH, null)));
        when(users.findByLoginName("nobody")).thenReturn(Optional.empty());
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(service);
        provider.setPasswordEncoder(encoder);
        assertThatThrownBy(() -> provider.authenticate(
            UsernamePasswordAuthenticationToken.unauthenticated("nobody", "x")))
            .isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> provider.authenticate(
            UsernamePasswordAuthenticationToken.unauthenticated("admin", "")))
            .isInstanceOf(DisabledException.class);
        assertThatThrownBy(() -> provider.authenticate(
            UsernamePasswordAuthenticationToken.unauthenticated("admin", "anything")))
            .isInstanceOf(DisabledException.class);
    }
}
