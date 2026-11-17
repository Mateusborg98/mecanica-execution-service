package br.com.techchallenge.mecanica.execution.infrastructure.security;

import java.security.PublicKey;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;

@Service
public class JwtService {

    private final PublicKey publicKey;
    private final String issuer;

    @Autowired
    public JwtService(
            @Value("${app.jwt.public-key}") String publicKeyPem,
            @Value("${app.jwt.issuer:mecanica-auth}") String issuer) {

        this(new RsaPublicKeyLoader().load(publicKeyPem), issuer);
    }

    JwtService(PublicKey publicKey, String issuer) {
        if (publicKey == null) {
            throw new IllegalArgumentException("A chave pública é obrigatória");
        }
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("O emissor do token é obrigatório");
        }

        this.publicKey = publicKey;
        this.issuer = issuer;
    }

    public String extractSubject(String token) {
        return extractClaims(token).getSubject();
    }

    public String extractRole(String token) {
        return extractClaims(token).get("role", String.class);
    }

    public boolean isValid(String token) {
        try {
            extractClaims(token);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private Claims extractClaims(String token) {
        return Jwts.parserBuilder()
                .requireIssuer(issuer)
                .setSigningKey(publicKey)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }
}
