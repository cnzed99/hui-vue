package cn.iocoder.yudao.server.print.email;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import static cn.iocoder.yudao.server.print.email.PrintEmailSettingsException.Reason.SECRET_UNAVAILABLE;

@Component
public class PrintEmailSettingsSecretCodec {
    public record EncryptedSecret(String ciphertext, String nonce, String keyVersion) {}
    private final SecretKeySpec key;
    private final String keyVersion;
    private final SecureRandom random = new SecureRandom();

    public PrintEmailSettingsSecretCodec(
            @Value("${print.email-settings-master-key-base64:}") String encodedKey,
            @Value("${print.email-settings-key-version:v1}") String keyVersion) {
        SecretKeySpec parsed = null;
        try {
            byte[] bytes = Base64.getDecoder().decode(encodedKey == null ? "" : encodedKey);
            if (bytes.length == 32 && keyVersion != null && keyVersion.matches("[A-Za-z0-9._-]{1,32}")) {
                parsed = new SecretKeySpec(bytes, "AES");
            }
        } catch (IllegalArgumentException ignored) { }
        this.key = parsed;
        this.keyVersion = keyVersion;
    }

    public boolean available() { return key != null; }

    public EncryptedSecret encrypt(long tenantId, long storeId, String plaintext) {
        if (!available() || plaintext == null || plaintext.isEmpty()) throw new PrintEmailSettingsException(SECRET_UNAVAILABLE);
        byte[] nonce = new byte[12]; random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(("print_email_settings\u0000" + tenantId + "\u0000" + storeId).getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return new EncryptedSecret(Base64.getEncoder().encodeToString(encrypted),
                    Base64.getEncoder().encodeToString(nonce), keyVersion);
        } catch (GeneralSecurityException e) {
            throw new PrintEmailSettingsException(SECRET_UNAVAILABLE);
        }
    }
}
