package com.christos_bramis.bram_vortex_terraform_generator.service;

import org.springframework.stereotype.Service;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.support.VaultResponse;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;

@Service
public class VaultService {

    private final VaultTemplate vaultTemplate;

    public VaultService(VaultTemplate vaultTemplate) {
        this.vaultTemplate = vaultTemplate;
    }

    /**
     * Δημιουργεί ένα RSA Key Pair, αποθηκεύει το Private Key στο Vault
     * και επιστρέφει το Public Key σε OpenSSH format.
     */
    public String createAndStoreSshKeyPair(String userId,String repoName, String jobId) {
        try {
            System.out.println("🔑 [VAULT-SERVICE] Generating RSA 4096-bit Key Pair for Job: " + jobId);

            // 1. Παραγωγή του Key Pair
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(4096);
            KeyPair pair = keyGen.generateKeyPair();

            // 2. Format Private Key σε PEM format (για την Ansible)
            String privateKeyPem = "-----BEGIN RSA PRIVATE KEY-----\n" +
                    Base64.getMimeEncoder().encodeToString(pair.getPrivate().getEncoded()) +
                    "\n-----END RSA PRIVATE KEY-----";

            // 3. Format Public Key σε OpenSSH format (για την Terraform)
            // Σημείωση: Το πρόθεμα 'ssh-rsa' είναι απαραίτητο για τα Cloud Providers
            String publicKeyOpenSSH = "ssh-rsa " +
                    Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()) +
                    " vortex-generated-key-" + jobId;

            // 4. Αποθήκευση στο Vault
            // Path: secret/users/{userId}/{repoName}/{jobId}
            String vaultPath = String.format("secret/users/%s/%s/%s", userId, repoName, jobId);

            System.out.println("🏦 [VAULT-SERVICE] Storing Private Key in Vault path: " + vaultPath);

            vaultTemplate.write(vaultPath, Map.of("private_key", privateKeyPem));
            vaultTemplate.write(vaultPath, Map.of("public_key", publicKeyOpenSSH));


            return publicKeyOpenSSH;

        } catch (Exception e) {
            System.err.println("❌ [VAULT-SERVICE ERROR] Failed to manage SSH keys: " + e.getMessage());
            throw new RuntimeException("Could not create/store SSH key pair", e);
        }
    }

    // --- Οι υπάρχουσες μέθοδοί σου ---

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

    public PublicKey getKeyFromPEM(String pem) throws Exception {
        String publicKeyPEM = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] encoded = Base64.getDecoder().decode(publicKeyPEM);
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(encoded));
    }
}