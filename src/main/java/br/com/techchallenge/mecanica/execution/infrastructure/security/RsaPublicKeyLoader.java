package br.com.techchallenge.mecanica.execution.infrastructure.security;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public class RsaPublicKeyLoader {

    private static final String PUBLIC_KEY_HEADER =
            "-----BEGIN PUBLIC KEY-----";
    private static final String PUBLIC_KEY_FOOTER =
            "-----END PUBLIC KEY-----";

    public PublicKey load(String pem) {
        if (pem == null || pem.isBlank()) {
            throw new IllegalArgumentException(
                    "A chave pública RSA é obrigatória");
        }

        String normalizedPem = pem
                .replace("\\n", "\n")
                .trim();

        if (!normalizedPem.startsWith(PUBLIC_KEY_HEADER)
                || !normalizedPem.endsWith(PUBLIC_KEY_FOOTER)) {
            throw new IllegalArgumentException(
                    "A chave pública deve estar no formato PEM X.509");
        }

        String encodedKey = normalizedPem
                .replace(PUBLIC_KEY_HEADER, "")
                .replace(PUBLIC_KEY_FOOTER, "")
                .replaceAll("\\s", "");

        try {
            byte[] decodedKey = Base64.getDecoder().decode(encodedKey);
            PublicKey publicKey = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(decodedKey));

            if (!(publicKey instanceof RSAPublicKey)) {
                throw new IllegalArgumentException(
                        "A chave informada não é uma chave pública RSA");
            }

            return publicKey;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "O conteúdo da chave pública RSA é inválido",
                    exception);
        } catch (GeneralSecurityException exception) {
            throw new IllegalArgumentException(
                    "Não foi possível carregar a chave pública RSA",
                    exception);
        }
    }
}
