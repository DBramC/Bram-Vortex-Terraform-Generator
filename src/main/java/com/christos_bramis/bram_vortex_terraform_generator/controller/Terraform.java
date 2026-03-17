package com.christos_bramis.bram_vortex_terraform_generator.controller;

import com.christos_bramis.bram_vortex_terraform_generator.entity.TerraformJob;
import com.christos_bramis.bram_vortex_terraform_generator.repository.TerraformJobRepository;
import com.christos_bramis.bram_vortex_terraform_generator.service.TerraformService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/terraform")
public class Terraform {

    private final TerraformService terraformService;
    private final TerraformJobRepository terraformJobRepository;

    // Constructor Injection για τα απαραίτητα beans
    public Terraform(TerraformService terraformService, TerraformJobRepository terraformJobRepository) {
        this.terraformService = terraformService;
        this.terraformJobRepository = terraformJobRepository;
    }

    /**
     * Endpoint που δέχεται το Webhook από τον Repo Analyzer.
     * Ξεκινάει την επικοινωνία με το AI και τη δημιουργία του ZIP.
     */
    @PostMapping("/generate/{analysisJobId}")
    public ResponseEntity<String> generateTerraform(@PathVariable String analysisJobId, @RequestParam String userId) {
        System.out.println("🚀 [CONTROLLER] Webhook received from Analyzer for Job: " + analysisJobId);

        try {
            // Δημιουργούμε ένα μοναδικό ID για τη συγκεκριμένη παραγωγή Terraform
            String terraformJobId = UUID.randomUUID().toString();

            // Καλούμε το Service (το οποίο τρέχει Async, οπότε δεν μπλοκάρουμε τον Analyzer)
            terraformService.generateAndSaveTerraform(terraformJobId, analysisJobId, userId);

            // Επιστρέφουμε το ID στον Analyzer (ή στο frontend) για να ξέρουν πώς να το αναζητήσουν
            return ResponseEntity.ok(terraformJobId);
        } catch (Exception e) {
            System.err.println("❌ [CONTROLLER ERROR]: " + e.getMessage());
            return ResponseEntity.internalServerError().body("Error starting generation: " + e.getMessage());
        }
    }

    /**
     * Endpoint για το κατέβασμα του παραγόμενου ZIP από τον χρήστη.
     */
    @GetMapping("/download/{terraformJobId}")
    public ResponseEntity<byte[]> downloadTerraform(@PathVariable String terraformJobId, @RequestParam String userId) {
        System.out.println("📦 [CONTROLLER] Download request for TF Job: " + terraformJobId);

        // 1. Αναζήτηση στη βάση
        return terraformJobRepository.findById(terraformJobId)
                .map(job -> {
                    // 2. SECURITY CHECK: Ανήκει αυτό το Job στον χρήστη που το ζητάει;
                    if (!job.getUserId().equals(userId)) {
                        System.err.println("🚫 [SECURITY] User " + userId + " tried to access unauthorized job: " + terraformJobId);
                        return ResponseEntity.status(HttpStatus.FORBIDDEN).<byte[]>build();
                    }

                    // 3. CHECK STATUS: Είναι έτοιμο το αρχείο;
                    if (!"COMPLETED".equals(job.getStatus()) || job.getTerraformZip() == null) {
                        return ResponseEntity.status(HttpStatus.ACCEPTED).<byte[]>build(); // 202 Accepted (σημαίνει "ακόμα δουλεύω")
                    }

                    // 4. PREPARE DOWNLOAD: Φτιάχνουμε τα headers για τον browser
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.parseMediaType("application/zip"));
                    headers.setContentDispositionFormData("attachment", "vortex-terraform-" + terraformJobId + ".zip");
                    headers.setCacheControl("must-revalidate, post-check=0, pre-check=0");

                    return new ResponseEntity<>(job.getTerraformZip(), headers, HttpStatus.OK);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Προαιρετικό: Endpoint για να βλέπει το frontend το status (GENERATING, COMPLETED, FAILED)
     */
    @GetMapping("/status/{terraformJobId}")
    public ResponseEntity<String> getStatus(@PathVariable String terraformJobId) {
        return terraformJobRepository.findById(terraformJobId)
                .map(job -> ResponseEntity.ok(job.getStatus()))
                .orElse(ResponseEntity.notFound().build());
    }
}