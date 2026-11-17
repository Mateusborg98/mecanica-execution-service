package br.com.techchallenge.mecanica.execution.infrastructure.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

class SecurityServicesCoverageTest {

    private static final String ISSUER = "mecanica-auth";
    private static final KeyPair KEY_PAIR = generateKeyPair();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldValidateTokenIssuedWithExpectedRsaKeyAndIssuer() {
        JwtService service = jwtService();
        String subject = UUID.randomUUID().toString();
        String token = issueToken(subject, "CLIENTE", ISSUER);

        assertEquals(subject, service.extractSubject(token));
        assertEquals("CLIENTE", service.extractRole(token));
        assertTrue(service.isValid(token));
        assertFalse(service.isValid("invalid-token"));
        assertFalse(service.isValid(issueToken(subject, "CLIENTE", "other-issuer")));
    }

    @Test
    void shouldLoadPublicKeyFromPemWithEscapedLineBreaks() {
        String escapedPem = publicKeyPem().replace("\n", "\\n");

        assertEquals("RSA", new RsaPublicKeyLoader().load(escapedPem).getAlgorithm());
    }

    @Test
    void shouldRejectInvalidPublicKeyAndJwtConfiguration() {
        RsaPublicKeyLoader loader = new RsaPublicKeyLoader();

        assertThrows(IllegalArgumentException.class, () -> loader.load(null));
        assertThrows(IllegalArgumentException.class, () -> loader.load("invalid"));
        assertThrows(IllegalArgumentException.class, () -> loader.load(
                "-----BEGIN PUBLIC KEY-----\ninvalid\n-----END PUBLIC KEY-----"));
        assertThrows(IllegalArgumentException.class,
                () -> new JwtService((java.security.PublicKey) null, ISSUER));
        assertThrows(IllegalArgumentException.class,
                () -> new JwtService(KEY_PAIR.getPublic(), " "));
    }

    @Test
    void filterShouldAuthenticateValidBearerAndContinueChain() throws Exception {
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtService());
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        String subject = UUID.randomUUID().toString();

        when(request.getHeader("Authorization"))
                .thenReturn("Bearer " + issueToken(subject, "CLIENTE", ISSUER));

        filter.doFilterInternal(request, response, chain);

        var authentication = SecurityContextHolder.getContext().getAuthentication();
        assertEquals(subject, authentication.getName());
        assertTrue(authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_CLIENTE")));
        verify(chain).doFilter(request, response);
    }

    @Test
    void filterShouldIgnoreInvalidTokenMissingHeaderAndExistingAuthentication()
            throws Exception {
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtService());
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        when(request.getHeader("Authorization")).thenReturn("Bearer invalid");
        filter.doFilterInternal(request, response, chain);
        assertNull(SecurityContextHolder.getContext().getAuthentication());

        when(request.getHeader("Authorization")).thenReturn(null);
        filter.doFilterInternal(request, response, chain);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("existing", null));
        when(request.getHeader("Authorization")).thenReturn(
                "Bearer " + issueToken(UUID.randomUUID().toString(), "CLIENTE", ISSUER));
        filter.doFilterInternal(request, response, chain);
        assertEquals("existing", SecurityContextHolder.getContext().getAuthentication().getName());
    }

    @Test
    void filterShouldSkipOnlyConfiguredPublicRoutes() {
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtService());
        HttpServletRequest request = mock(HttpServletRequest.class);

        when(request.getServletPath()).thenReturn(
                "/swagger-ui/index.html",
                "/v3/api-docs",
                "/actuator/health",
                "/clientes",
                null);

        assertTrue(filter.shouldNotFilter(request));
        assertTrue(filter.shouldNotFilter(request));
        assertTrue(filter.shouldNotFilter(request));
        assertFalse(filter.shouldNotFilter(request));
        assertFalse(filter.shouldNotFilter(request));
    }

    private static JwtService jwtService() {
        return new JwtService(KEY_PAIR.getPublic(), ISSUER);
    }

    private static String issueToken(String subject, String role, String issuer) {
        Instant now = Instant.now();
        return Jwts.builder()
                .setSubject(subject)
                .setIssuer(issuer)
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(now.plusSeconds(900)))
                .claim("role", role)
                .claim("documentType", "CPF")
                .signWith(KEY_PAIR.getPrivate(), SignatureAlgorithm.RS256)
                .compact();
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to generate test key", exception);
        }
    }

    private static String publicKeyPem() {
        String encoded = Base64.getMimeEncoder(64, new byte[] {'\n'})
                .encodeToString(KEY_PAIR.getPublic().getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n"
                + encoded
                + "\n-----END PUBLIC KEY-----";
    }
}
