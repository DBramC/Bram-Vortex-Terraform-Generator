package com.christos_bramis.bram_vortex_terraform_generator.controller;

import com.christos_bramis.bram_vortex_terraform_generator.entity.TerraformJob;
import com.christos_bramis.bram_vortex_terraform_generator.repository.TerraformJobRepository;
import com.christos_bramis.bram_vortex_terraform_generator.service.TerraformService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/terraform")
public class Terraform {

    private final TerraformService terraformService;
    private final TerraformJobRepository terraformJobRepository;

    public Terraform(TerraformService terraformService, TerraformJobRepository terraformJobRepository) {
        this.terraformService = terraformService;
        this.terraformJobRepository = terraformJobRepository;
    }

    /**
     * Endpoint Webhook - Δέχεται το αίτημα από τον Analyzer.
     * Το userId προκύπτει από το JWT Token που μεταφέρθηκε (Token Propagation).
     */
    @PostMapping("/generate/{analysisJobId}")
    public ResponseEntity<String> generateTerraform(
            @PathVariable String analysisJobId,
            Authentication auth) { // <--- Λήψη από το Security Context

        String userId = auth.getName();
        System.out.println("🚀 [TF CONTROLLER] Webhook received for Job: " + analysisJobId + " from User: " + userId);

        try {
            String terraformJobId = UUID.randomUUID().toString();

            // Καλούμε το Service χρησιμοποιώντας την έγκυρη ταυτότητα του χρήστη
            terraformService.generateAndSaveTerraform(terraformJobId, analysisJobId, userId);

            return ResponseEntity.ok(terraformJobId);
        } catch (Exception e) {
            System.err.println("❌ [CONTROLLER ERROR]: " + e.getMessage());
            return ResponseEntity.internalServerError().body("Error starting generation: " + e.getMessage());
        }
    }

    /**
     * Endpoint για Download.
     * Προστατεύεται από το JWT: Μόνο ο ιδιοκτήτης του Job μπορεί να κατεβάσει το αρχείο.
     */
    @GetMapping("/download/{terraformJobId}")
    public ResponseEntity<byte[]> downloadTerraform(
            @PathVariable String terraformJobId,
            Authentication auth) {

        String userId = auth.getName();
        System.out.println("📦 [TF CONTROLLER] Download request for Job: " + terraformJobId + " by User: " + userId);

        return terraformJobRepository.findById(terraformJobId)
                .map(job -> {
                    // SECURITY CHECK: Διασταύρωση userId από τη βάση με το userId από το Token
                    if (!job.getUserId().equals(userId)) {
                        System.err.println("🚫 [SECURITY] Unauthorized download attempt by user: " + userId);
                        return ResponseEntity.status(HttpStatus.FORBIDDEN).<byte[]>build();
                    }

                    if (!"COMPLETED".equals(job.getStatus()) || job.getTerraformZip() == null) {
                        return ResponseEntity.status(HttpStatus.ACCEPTED).<byte[]>build();
                    }

                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.parseMediaType("application/zip"));
                    headers.setContentDispositionFormData("attachment", "vortex-terraform-" + terraformJobId + ".zip");
                    headers.setCacheControl("must-revalidate, post-check=0, pre-check=0");

                    return new ResponseEntity<>(job.getTerraformZip(), headers, HttpStatus.OK);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Endpoint για το Status.
     * Επιστρέφει την κατάσταση (GENERATING, COMPLETED, κτλ) στον ιδιοκτήτη.
     */
    @GetMapping("/status/{terraformJobId}")
    public ResponseEntity<String> getStatus(@PathVariable String terraformJobId, Authentication auth) {
        String userId = auth.getName();
        return terraformJobRepository.findById(terraformJobId)
                .map(job -> {
                    if (!job.getUserId().equals(userId)) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN).<String>build();
                    }
                    return ResponseEntity.ok(job.getStatus());
                })
                .orElse(ResponseEntity.notFound().build());
    }
}