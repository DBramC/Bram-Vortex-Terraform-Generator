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
    @GetMapping("/download/by-analysis/{analysisJobId}")
    public ResponseEntity<byte[]> downloadByAnalysisId(
            @PathVariable String analysisJobId,
            Authentication auth) {

        String userId = auth.getName();

        // Χρησιμοποιούμε μια μέθοδο στο repository για να βρούμε το job βάσει analysisJobId
        return terraformJobRepository.findByAnalysisJobId(analysisJobId)
                .map(job -> {
                    // 1. SECURITY CHECK: Ανήκει το Job σε αυτόν τον χρήστη;
                    if (!job.getUserId().equals(userId)) {
                        System.err.println("🚫 [SECURITY] Unauthorized download attempt by user: " + userId);
                        return ResponseEntity.status(HttpStatus.FORBIDDEN).<byte[]>build();
                    }

                    // 2. STATUS & CONTENT CHECK: Είναι έτοιμο ΚΑΙ έχει πραγματικά δεδομένα;
                    // Αν το status δεν είναι COMPLETED, ή το zip είναι null, ή το zip είναι άδειο (0 bytes)
                    if (!"COMPLETED".equals(job.getStatus()) || job.getTerraformZip() == null || job.getTerraformZip().length == 0) {
                        System.out.println("⏳ [TF CONTROLLER] Generation not finished or file empty for Job: " + analysisJobId);
                        // Επιστρέφουμε 202 ACCEPTED (Σημαίνει: Το έλαβα αλλά επεξεργάζεται ακόμα)
                        return ResponseEntity.status(HttpStatus.ACCEPTED).<byte[]>build();
                    }

                    // 3. SUCCESS: Κατέβασμα του αρχείου
                    System.out.println("✅ [TF CONTROLLER] Sending ZIP file for Job: " + analysisJobId);
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.parseMediaType("application/zip"));
                    headers.setContentDispositionFormData("attachment", "vortex-terraform-" + analysisJobId + ".zip");
                    // Αποτροπή caching για να παίρνει πάντα την πιο πρόσφατη έκδοση
                    headers.setCacheControl("must-revalidate, post-check=0, pre-check=0");

                    return new ResponseEntity<>(job.getTerraformZip(), headers, HttpStatus.OK);
                })
                .orElse(ResponseEntity.notFound().build()); // Αν δεν βρεθεί καν στη βάση, 404
    }


}