package com.christos_bramis.bram_vortex_terraform_generator.service;

import com.christos_bramis.bram_vortex_terraform_generator.entity.AnalysisJob;
import com.christos_bramis.bram_vortex_terraform_generator.entity.TerraformJob;
import com.christos_bramis.bram_vortex_terraform_generator.repository.AnalysisJobRepository;
import com.christos_bramis.bram_vortex_terraform_generator.repository.TerraformJobRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.converter.MapOutputConverter;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class TerraformService {

    private final TerraformJobRepository terraformJobRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;
    private final MapOutputConverter mapOutputConverter; // Προσθήκη για επαναχρησιμοποίηση

    public TerraformService(TerraformJobRepository terraformJobRepository,
                            AnalysisJobRepository analysisJobRepository,
                            ChatModel chatModel) {
        this.terraformJobRepository = terraformJobRepository;
        this.analysisJobRepository = analysisJobRepository;
        this.chatModel = chatModel;
        this.objectMapper = new ObjectMapper();
        this.mapOutputConverter = new MapOutputConverter();
    }

    public void generateAndSaveTerraform(String terraformJobId, String analysisJobId, String userId) {
        System.out.println("🚀 [TF-SERVICE] Starting generation for TF Job: " + terraformJobId);

        TerraformJob job = new TerraformJob();
        job.setId(terraformJobId);
        job.setAnalysisJobId(analysisJobId);
        job.setUserId(userId);
        job.setStatus("GENERATING");
        terraformJobRepository.save(job);

        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                System.out.println("🔍 [TF-SERVICE] Reading Blueprint from Database...");
                AnalysisJob analysisJob = analysisJobRepository.findById(analysisJobId)
                        .orElseThrow(() -> new RuntimeException("Analysis blueprint not found for ID: " + analysisJobId));

                String blueprintJson = analysisJob.getBlueprintJson();
                if (blueprintJson == null || blueprintJson.isEmpty()) {
                    throw new RuntimeException("Blueprint JSON is empty in database.");
                }

                JsonNode rootNode = objectMapper.readTree(blueprintJson);
                String computeType = rootNode.path("computeType").asText("Managed Container");

                String promptNoAnsible = String.format("""
                    You are a Principal Cloud Architect and Terraform Expert specialized in Container Orchestration and Serverless.
                    Your task is to generate PRODUCTION-READY Terraform code for a MANAGED CONTAINER/K8S deployment based on the blueprint.
        
                    --- ARCHITECTURAL BLUEPRINT (JSON) ---
                    %s  
                    --------------------------------------

                    ENGINEERING REQUIREMENTS & BEST PRACTICES:
                    1. **Providers (`providers.tf`)**: Configure the correct cloud provider and region based on the blueprint.
                    2. **Variables (`variables.tf`)**: Extract configurations like cluster names, container images, and ports. No hardcoding.
                    3. **Core Infrastructure (`main.tf`)**:
                        - Provision the managed service (e.g., AWS ECS, Fargate, or Kubernetes Cluster).
                        - **Security & IAM**: Generate necessary IAM Roles, Execution Policies, and Task Definitions.
                        - **Networking**: Generate VPC, Subnets, and a Load Balancer (ALB/NLB). Route traffic to the 'targetContainerPort'.
                        - Inject environment variables directly into the container definition/spec.
                        - Apply tags: ManagedBy = "Bram Vortex", Project = "Repo_Name".
                    4. **Outputs (`outputs.tf`)**: 
                    - MANDATORY: Expose the Load Balancer DNS or Service URL as `app_url`.

                    OUTPUT FORMAT (CRITICAL):
                    - Respond with a SINGLE, VALID JSON object and absolutely NOTHING else.
                    - DO NOT wrap the response in markdown blocks.
                    - No introductory or concluding text. 

                    EXPECTED JSON SCHEMA:
                    {
                        "main.tf": "<raw terraform code>",
                        "variables.tf": "<raw terraform code>",
                        "outputs.tf": "<raw terraform code>",
                        "providers.tf": "<raw terraform code>"
                    }
                    """, blueprintJson);

                String promptYesAnsible = String.format("""
                    You are a Principal Cloud Architect and Terraform Expert specialized in Infrastructure-as-Service (IaaS).
                    Your task is to generate PRODUCTION-READY Terraform code for a VIRTUAL MACHINE deployment based on the blueprint.

                    --- ARCHITECTURAL BLUEPRINT (JSON) ---
                    %s
                    --------------------------------------

                    ENGINEERING REQUIREMENTS & BEST PRACTICES:
                    1. **Providers (`providers.tf`)**: Configure the correct cloud provider and region based on the blueprint.
                    2. **Variables (`variables.tf`)**: 
                       - Extract configurations like instance sizes, ports, and env vars.
                       - MANDATORY: Include a variable for `ssh_public_key` to be used for instance access.
                    3. **Core Infrastructure (`main.tf`)**:
                       - Provision the Virtual Machine instance.
                       - **SSH Access**: Create an SSH Key Pair resource (e.g., `aws_key_pair`) using the `ssh_public_key` variable.
                       - **Security**: Generate VPC, Subnets, and Security Groups. Open port 22 (SSH) and the blueprint's 'targetContainerPort' for inbound traffic.
                       - Inject environment variables into the VM metadata or user_data.
                       - Apply tags: ManagedBy = "Bram Vortex", Project = "Repo_Name".
                    4. **Outputs (`outputs.tf`)**: 
                       - MANDATORY: Expose the Public IP as `instance_public_ip`. This is critical for downstream Ansible configuration.

                    OUTPUT FORMAT (CRITICAL):
                    - Respond with a SINGLE, VALID JSON object and absolutely NOTHING else.
                    - DO NOT wrap the response in markdown blocks.
                    - No introductory or concluding text. 

                    EXPECTED JSON SCHEMA:
                    {
                      "main.tf": "<raw terraform code>",
                      "variables.tf": "<raw terraform code>",
                      "outputs.tf": "<raw terraform code>",
                      "providers.tf": "<raw terraform code>"
                    }
                    """, blueprintJson);

                String selectedBasePrompt;
                if ("Virtual Machine".equalsIgnoreCase(computeType)) {
                    selectedBasePrompt = promptYesAnsible;
                    System.out.println("🎯 [TF-SERVICE] Using VM-optimized prompt.");
                } else {
                    selectedBasePrompt = promptNoAnsible;
                    System.out.println("🎯 [TF-SERVICE] Using Container/K8S-optimized prompt.");
                }

                String finalPrompt = selectedBasePrompt + "\n\n" + mapOutputConverter.getFormat();

                Map<String, String> tfFiles = new HashMap<>();
                boolean isValid = false;
                int attempts = 0;
                String lastError = "";

                // --- ΛΟΓΙΚΗ VALIDATION & SELF-CORRECTION (Max 3 attempts) ---
                while (!isValid && attempts < 3) {
                    attempts++;
                    String currentPrompt = (attempts == 1) ? finalPrompt :
                            "The previous Terraform code you generated had validation errors:\n" + lastError +
                                    "\n\nPlease fix the code and return only the corrected JSON.";

                    System.out.println("🧠 [TF-SERVICE] Calling Gemini - Attempt #" + attempts);
                    String aiResponse = chatModel.call(currentPrompt);

                    // Καθαρισμός και Parsing
                    assert aiResponse != null;
                    tfFiles = parseAiResponse(aiResponse);

                    // Εκτέλεση Terraform Validate
                    System.out.println("🔍 [TF-SERVICE] Validating code with Terraform CLI...");
                    ValidationResult result = performValidation(tfFiles);

                    if (result.valid) {
                        isValid = true;
                        System.out.println("✅ [TF-SERVICE] Code is VALID.");
                    } else {
                        lastError = result.errorOutput;
                        System.err.println("⚠️ [TF-SERVICE] Validation Error: " + lastError);
                    }
                }

                if (!isValid) {
                    throw new RuntimeException("AI failed to generate valid Terraform after 3 attempts.");
                }

                // ΒΗΜΑ Δ: Δημιουργία ZIP και Αποθήκευση
                System.out.println("📦 [TF-SERVICE] Packing valid files into ZIP...");
                byte[] zipBytes = createZipInMemory(tfFiles);

                job.setTerraformZip(zipBytes);
                job.setStatus("COMPLETED");
                terraformJobRepository.save(job);

            } catch (Exception e) {
                System.err.println("❌ [TF-SERVICE ERROR]: " + e.getMessage());
                job.setStatus("FAILED");
                terraformJobRepository.save(job);
            }
        });
    }

    // --- ΒΟΗΘΗΤΙΚΕΣ ΜΕΘΟΔΟΙ ΓΙΑ ΤΟ VALIDATE ---

    private Map<String, String> parseAiResponse(String response) {
        String clean = response.trim();
        if (clean.startsWith("```")) {
            clean = clean.replaceAll("^```json\\s*", "").replaceAll("```$", "").trim();
        }
        Map<String, Object> raw = mapOutputConverter.convert(clean);
        Map<String, String> files = new HashMap<>();
        raw.forEach((k, v) -> files.put(k, String.valueOf(v)));
        return files;
    }

    private ValidationResult performValidation(Map<String, String> files) {
        try {
            Path tempDir = Files.createTempDirectory("vortex-tf-");
            for (Map.Entry<String, String> entry : files.entrySet()) {
                Files.writeString(tempDir.resolve(entry.getKey()), entry.getValue());
            }

            // Terraform Init & Validate
            runCommand(tempDir, "terraform init -backend=false");
            String validateJson = runCommand(tempDir, "terraform validate -json");

            JsonNode res = objectMapper.readTree(validateJson);
            if (res.path("valid").asBoolean()) {
                return new ValidationResult(true, null);
            } else {
                return new ValidationResult(false, validateJson);
            }
        } catch (Exception e) {
            return new ValidationResult(false, e.getMessage());
        }
    }

    private String runCommand(Path dir, String cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
        pb.directory(dir.toFile());

        // Ανακατεύθυνση του Error Stream στο Input Stream για να τα διαβάζουμε όλα μαζί
        pb.redirectErrorStream(true);

        Process p = pb.start();
        StringBuilder output = new StringBuilder();

        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                output.append(line).append("\n");
                // ΑΥΤΟ ΕΙΝΑΙ ΤΟ ΚΛΕΙΔΙ: Τυπώνει τη γραμμή στην κονσόλα του container
                System.out.println("[TF-CLI] " + line);
            }
        }

        int exitCode = p.waitFor();
        if (exitCode != 0) {
            System.err.println("❌ Command '" + cmd + "' failed with exit code " + exitCode);
        }

        return output.toString();
    }

    private static class ValidationResult {
        boolean valid; String errorOutput;
        ValidationResult(boolean v, String e) { this.valid = v; this.errorOutput = e; }
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