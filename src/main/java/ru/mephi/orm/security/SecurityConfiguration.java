package ru.mephi.orm.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import ru.mephi.orm.entity.Role;
import ru.mephi.orm.entity.UserAccount;
import ru.mephi.orm.repository.UserAccountRepository;

@Configuration
public class SecurityConfiguration {

    static final String ISSUER = "orm-course-platform";
    static final String AUDIENCE = "orm-course-api";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, Environment environment) throws Exception {
        var authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("role");
        authorities.setAuthorityPrefix("ROLE_");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);

        return http
                .csrf(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(routes -> {
                    routes.requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll();
                    if (environment.acceptsProfiles(Profiles.of("demo"))) {
                        routes.requestMatchers("/v3/api-docs", "/v3/api-docs/**",
                                "/swagger-ui.html", "/swagger-ui/**").permitAll();
                    }
                    routes.requestMatchers("/api/**").authenticated().anyRequest().denyAll();
                })
                .oauth2ResourceServer(server -> server
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint((request, response, failure) -> unauthorized(response))
                        .accessDeniedHandler((request, response, failure) -> problem(response, 403, "Forbidden")))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, failure) -> unauthorized(response))
                        .accessDeniedHandler((request, response, failure) -> problem(response, 403, "Forbidden")))
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(UserAccountRepository accounts) {
        return email -> accounts.findByEmail(email)
                .map(AccountPrincipal::new)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown account"));
    }

    @Bean
    AuthenticationManager authenticationManager(UserDetailsService users, PasswordEncoder passwords) {
        var provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(passwords);
        return new ProviderManager(provider);
    }

    @Bean
    RSAPublicKey jwtPublicKey(@Value("${app.jwt.public-key}") String value) throws Exception {
        byte[] bytes = Base64.getDecoder().decode(value);
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(bytes));
    }

    @Bean
    JwtEncoder jwtEncoder(RSAPublicKey publicKey, @Value("${app.jwt.private-key}") String value) throws Exception {
        byte[] bytes = Base64.getDecoder().decode(value);
        var privateKey = (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(bytes));
        if (!publicKey.getModulus().equals(privateKey.getModulus())) {
            throw new IllegalArgumentException("JWT keys must be a matching RSA key pair");
        }
        var key = new RSAKey.Builder(publicKey).privateKey(privateKey).keyID("orm-api").build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
    }

    @Bean
    JwtDecoder jwtDecoder(RSAPublicKey publicKey, UserAccountRepository accounts) {
        var decoder = NimbusJwtDecoder.withPublicKey(publicKey).build();
        OAuth2TokenValidator<Jwt> claims = jwt -> validClaims(jwt, accounts)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(ISSUER), claims));
        return decoder;
    }

    private boolean validClaims(Jwt jwt, UserAccountRepository accounts) {
        if (jwt.getAudience() == null || !jwt.getAudience().contains(AUDIENCE) || jwt.getExpiresAt() == null) {
            return false;
        }
        try {
            Role role = Role.valueOf(jwt.getClaimAsString("role"));
            long userId = Long.parseLong(jwt.getSubject());
            return userId > 0 && accounts.findById(userId).map(account -> account.getRole() == role).orElse(false);
        } catch (IllegalArgumentException | NullPointerException failure) {
            return false;
        }
    }

    static final class AccountPrincipal extends org.springframework.security.core.userdetails.User {

        private final long accountId;
        private final Role role;

        AccountPrincipal(UserAccount account) {
            super(account.getEmail(), account.getPasswordHash(),
                    List.of(new SimpleGrantedAuthority("ROLE_" + account.getRole().name())));
            accountId = account.getId();
            role = account.getRole();
        }

        long accountId() {
            return accountId;
        }

        Role role() {
            return role;
        }
    }

    private static void unauthorized(HttpServletResponse response) throws IOException {
        response.setHeader("WWW-Authenticate", "Bearer");
        problem(response, 401, "Unauthorized");
    }

    private static void problem(HttpServletResponse response, int status, String title) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.getWriter().write("""
                {"type":"about:blank","title":"%s","status":%d,"detail":"%s"}
                """.formatted(title, status, title));
    }
}
