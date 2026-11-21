package ru.mephi.orm.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.mephi.orm.service.ApiFailure;

@Service
public class AuthService {

    private static final long TOKEN_SECONDS = 1800;

    private final AuthenticationManager authentication;
    private final JwtEncoder encoder;

    public AuthService(AuthenticationManager authentication, JwtEncoder encoder) {
        this.authentication = authentication;
        this.encoder = encoder;
    }

    @Transactional(readOnly = true)
    public Token login(String email, String password) {
        String normalizedEmail = email.strip().toLowerCase(Locale.ROOT);
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw ApiFailure.unauthorized();
        }
        SecurityConfiguration.AccountPrincipal account;
        try {
            var authenticated = authentication.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(normalizedEmail, password));
            account = (SecurityConfiguration.AccountPrincipal) authenticated.getPrincipal();
        } catch (AuthenticationException failure) {
            throw ApiFailure.unauthorized();
        }
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder()
                .issuer(SecurityConfiguration.ISSUER)
                .audience(List.of(SecurityConfiguration.AUDIENCE))
                .subject(Long.toString(account.accountId()))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(TOKEN_SECONDS))
                .claim("role", account.role().name()).build();
        var header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return new Token(encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue(),
                "Bearer", TOKEN_SECONDS);
    }

    public record Token(String accessToken, String tokenType, long expiresIn) {}
}
