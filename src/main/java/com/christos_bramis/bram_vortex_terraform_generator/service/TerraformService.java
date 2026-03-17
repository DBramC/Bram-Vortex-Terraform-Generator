package com.christos_bramis.bram_vortex_terraform_generator.service;

import com.christos_bramis.bram_vortex_terraform_generator.entity.AnalysisJob;
import com.christos_bramis.bram_vortex_terraform_generator.entity.TerraformJob;
import com.christos_bramis.bram_vortex_terraform_generator.repository.AnalysisJobRepository;
import com.christos_bramis.bram_vortex_terraform_generator.repository.TerraformJobRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.converter.MapOutputConverter;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class TerraformService {

    private final TerraformJobRepository terraformJobRepository;
    private final AnalysisJobRepository analysisJobRepository; // Read-only repo για το blueprint
    private final ChatModel chatModel;

    public TerraformService(TerraformJobRepository terraformJobRepository,
                            AnalysisJobRepository analysisJobRepository,
                            ChatModel chatModel) {
        this.terraformJobRepository = terraformJobRepository;
        this.analysisJobRepository = analysisJobRepository;
        this.chatModel = chatModel;
    }

    public void generateAndSaveTerraform(String terraformJobId, String analysisJobId, String userId) {
        System.out.println("🚀 [TF-SERVICE] Starting generation for TF Job: " + terraformJobId);

        // 1. Αρχικοποίηση του Terraform Job στη Βάση
        TerraformJob job = new TerraformJob();
        job.setId(terraformJobId);
        job.setAnalysisJobId(analysisJobId);
        job.setUserId(userId);
        job.setStatus("GENERATING");
        terraformJobRepository.save(job);

        // 2. Ασύγχρονη Εκτέλεση
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                // ΒΗΜΑ Α: Ανάγνωση Blueprint απευθείας από τη Βάση (Shared Database)
                System.out.println("🔍 [TF-SERVICE] Reading Blueprint from Database...");

                AnalysisJob analysisJob = analysisJobRepository.findById(analysisJobId)
                        .orElseThrow(() -> new RuntimeException("Analysis blueprint not found for ID: " + analysisJobId));

                String blueprintJson = analysisJob.getBlueprintJson();

                if (blueprintJson == null || blueprintJson.isEmpty()) {
                    throw new RuntimeException("Blueprint JSON is empty in database.");
                }

                // ΒΗΜΑ Β: Δημιουργία AI Prompt (Το prompt σου παραμένει 100% ίδιο)
                System.out.println("🧠 [TF-SERVICE] Calling Gemini AI to write Terraform code...");

                String prompt = String.format("""
                    You are a Principal Cloud Architect and Terraform Expert.
                    Your task is to generate PRODUCTION-READY, secure, and highly modular Terraform code based EXACTLY on the provided architectural blueprint.
                
                    --- ARCHITECTURAL BLUEPRINT (JSON) ---
                    %s
                    --------------------------------------

                    ENGINEERING REQUIREMENTS & BEST PRACTICES:
                    1. **Providers (`providers.tf`)**: Configure the correct cloud provider (e.g., AWS, Azure, GCP) and region based on the blueprint.
                    2. **Variables (`variables.tf`)**: Do NOT hardcode values in `main.tf`. Extract configurations like instance sizes, ports, and environment variables into `variables.tf` with descriptions and sensible defaults.
                    3. **Core Infrastructure (`main.tf`)**:
                       - Provision the requested compute target (Containers, VMs, or Kubernetes).
                        - IMPORTANT: Automatically generate the necessary Networking/Security resources (VPC, Subnets, Security Groups/Firewalls) to allow traffic on the requested 'targetContainerPort'.
                        - Inject the application configurations (Environment Variables) from the blueprint into the compute resource securely.
                        - Apply standard resource tags (e.g., ManagedBy = "Bram Vortex", Project = "Repo_Name").
                    4. **Outputs (`outputs.tf`)**: Expose critical deployment data (e.g., Public IPs, Load Balancer URLs, or Cluster Endpoints) so the user knows how to access their app.

                    OUTPUT FORMAT (CRITICAL RULES):
                    - You must respond with a SINGLE, VALID JSON object and absolutely NOTHING else.
                    - DO NOT wrap the response in markdown blocks (e.g., do not use ```json or ```).
                    - No introductory or concluding text. 
    
                    EXPECTED JSON SCHEMA:
                    {
                      "main.tf": "<raw terraform code for infrastructure>",
                      "variables.tf": "<raw terraform code for variables>",
                      "outputs.tf": "<raw terraform code for outputs>",
                      "providers.tf": "<raw terraform code for provider setup>"
                    }
                    """, blueprintJson);

                // ΒΗΜΑ Γ: Ενσωμάτωση Converter και Κλήση στο Gemini
                var mapOutputConverter = new MapOutputConverter();
                String finalPrompt = prompt + "\n\n" + mapOutputConverter.getFormat();

                String aiResponse = chatModel.call(finalPrompt);

                // Καθαρισμός του string από πιθανά Markdown backticks πριν το parsing
                String cleanResponse = aiResponse.trim();
                if (cleanResponse.startsWith("```")) {
                    cleanResponse = cleanResponse.replaceAll("^```json\\s*", "").replaceAll("```$", "").trim();
                }

                // Parsing με Spring AI Converter
                Map<String, Object> tfFilesRaw = mapOutputConverter.convert(cleanResponse);

                Map<String, String> tfFiles = new HashMap<>();
                if (tfFilesRaw != null) {
                    tfFilesRaw.forEach((k, v) -> tfFiles.put(k, String.valueOf(v)));
                }

                // ΒΗΜΑ Δ: Δημιουργία ZIP In-Memory
                System.out.println("📦 [TF-SERVICE] Packing files into ZIP in-memory...");
                byte[] zipBytes = createZipInMemory(tfFiles);

                // ΒΗΜΑ Ε: Αποθήκευση στη Βάση
                System.out.println("💾 [TF-SERVICE] Saving ZIP to PostgreSQL...");
                job.setTerraformZip(zipBytes);
                job.setStatus("COMPLETED");
                terraformJobRepository.save(job);

                System.out.println("✅ [TF-SERVICE] Terraform generation COMPLETE for Job: " + terraformJobId);

            } catch (Exception e) {
                System.err.println("❌ [TF-SERVICE ERROR] Failed to generate Terraform: " + e.getMessage());
                job.setStatus("FAILED");
                terraformJobRepository.save(job);
            }
        });
    }

    private byte[] createZipInMemory(Map<String, String> files) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (Map.Entry<String, String> entry : files.entrySet()) {
                ZipEntry zipEntry = new ZipEntry(entry.getKey());
                zos.putNextEntry(zipEntry);
                zos.write(entry.getValue().getBytes());
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }
}