package br.com.techchallenge.mecanica.execution.infrastructure.security;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import br.com.techchallenge.mecanica.execution.infrastructure.config.SecurityConfig;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitWebConfig(classes = {SecurityConfig.class, JwtService.class, JwtAuthenticationFilter.class,
    SecurityRoutesTest.WebConfig.class})
class SecurityRoutesTest {
    private static final KeyPair KEY = key();
    private static final String ISSUER = "security-test";
    @Autowired WebApplicationContext context;
    private MockMvc mvc;

    @Configuration
    @EnableWebMvc
    static class WebConfig {
        @Bean Probe probe() { return new Probe(); }
    }
    // Probe isolates authorization from business effects; controller contracts have their own tests.
    @RestController
    static class Probe {
        @RequestMapping("/**") String route() { return "authorized"; }
    }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("app.jwt.public-key", () -> "-----BEGIN PUBLIC KEY-----\n"
            + Base64.getEncoder().encodeToString(KEY.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----");
        properties.add("app.jwt.issuer", () -> ISSUER);
    }
    @BeforeEach
    void configure() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean("springSecurityFilterChain", jakarta.servlet.Filter.class)).build();
    }
    @Test
    void administrativeRoutesRequireOperator() throws Exception {
        for (String path : ADMIN_PATHS) {
            mvc.perform(post(path)).andExpect(status().isUnauthorized());
            mvc.perform(post(path).header("Authorization", bearer("CLIENTE"))).andExpect(status().isForbidden());
            mvc.perform(post(path).header("Authorization", bearer("OPERADOR"))).andExpect(status().isOk());
            mvc.perform(post(path).header("Authorization", bearer("ADMINISTRADOR"))).andExpect(status().isOk());
        }
    }
    @Test
    void invalidExpiredAndWrongIssuerTokensAreRejected() throws Exception {
        for (String token : new String[]{"invalid", token("OPERADOR", "other-issuer", Instant.now().plusSeconds(60), KEY),
                token("OPERADOR", ISSUER, Instant.now().minusSeconds(60), KEY),
                token("OPERADOR", ISSUER, Instant.now().plusSeconds(60), key())}) {
            mvc.perform(post(ADMIN_PATHS[0]).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        }
        mvc.perform(post(ADMIN_PATHS[0]).header("Authorization", bearer("DESCONHECIDO")))
            .andExpect(status().isForbidden());
    }
    @Test
    void healthRemainsPublicAndAuthenticationDoesNotLeakAcrossRequests() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(post(ADMIN_PATHS[0]).header("Authorization", bearer("OPERADOR"))).andExpect(status().isOk());
        mvc.perform(post(ADMIN_PATHS[0])).andExpect(status().isUnauthorized());
    }
    @Test
    void customerCannotReadInternalRepairOrStock() throws Exception {
        for (String path : new String[]{"/reparos/fila", "/reparos/id", "/pecas/id", "/servicos/id", "/reparos/id/reserva"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header("Authorization", bearer("CLIENTE"))).andExpect(status().isForbidden());
            mvc.perform(get(path).header("Authorization", bearer("OPERADOR"))).andExpect(status().isOk());
        }
    }
    private static final String[] ADMIN_PATHS = new String[]{"/reparos/cancelamentos", "/reparos", "/reparos/id/iniciar", "/reparos/id/concluir", "/reparos/id/cancelar", "/reparos/id/diagnostico", "/reparos/id/reserva", "/pecas", "/pecas/id/entradas", "/servicos"};
    private static String bearer(String role) {
        return "Bearer " + token(role, ISSUER, Instant.now().plusSeconds(120), KEY);
    }
    private static String token(String role, String issuer, Instant expiration, KeyPair key) {
        return Jwts.builder().setSubject(UUID.randomUUID().toString()).setIssuer(issuer)
            .setExpiration(Date.from(expiration)).claim("role", role)
            .signWith(key.getPrivate(), SignatureAlgorithm.RS256).compact();
    }
    private static KeyPair key() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (java.security.GeneralSecurityException error) {
            throw new IllegalStateException(error);
        }
    }
}
