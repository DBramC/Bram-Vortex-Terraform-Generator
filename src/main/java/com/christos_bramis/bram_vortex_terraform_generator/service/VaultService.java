package com.christos_bramis.bram_vortex_terraform_generator.service;

import org.springframework.stereotype.Service;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.support.VaultResponse;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;

@Service
public class VaultService {

    private final VaultTemplate vaultTemplate;

    public VaultService(VaultTemplate vaultTemplate) {
        this.vaultTemplate = vaultTemplate;
    }

    // Ανάκτηση του Public Key από το Transit engine της Vault
    public String getSigningPublicKey() {
        String path = "transit/keys/jwt-signing-key";
        try {
            VaultResponse response = vaultTemplate.read(path);
            if (response != null && response.getData() != null) {
                Map<String, Object> data = response.getData();
                String latestVersion = String.valueOf(data.get("latest_version"));
                Map<String, Object> keysMap = (Map<String, Object>) data.get("keys");
                Map<String, Object> keyVersionData = (Map<String, Object>) keysMap.get(latestVersion);
                return (String) keyVersionData.get("public_key");
            }
            return null;
        } catch (Exception e) {
            System.err.println("❌ Error fetching Public Key: " + e.getMessage());
            return null;
        }
    }

    // Μετατροπή του PEM String σε αντικείμενο PublicKey
    public PublicKey getKeyFromPEM(String pem) throws Exception {
        String publicKeyPEM = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] encoded = Base64.getDecoder().decode(publicKeyPEM);
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(encoded));
    }
}