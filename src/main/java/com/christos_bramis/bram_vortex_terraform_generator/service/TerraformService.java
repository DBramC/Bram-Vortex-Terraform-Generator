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
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class TerraformService {

    private final TerraformJobRepository terraformJobRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;
    private final MapOutputConverter mapOutputConverter;
    private final VaultService vaultService;

    public TerraformService(TerraformJobRepository terraformJobRepository,
                            AnalysisJobRepository analysisJobRepository,
                            ChatModel chatModel,
                            VaultService vaultService) {
        this.terraformJobRepository = terraformJobRepository;
        this.analysisJobRepository = analysisJobRepository;
        this.chatModel = chatModel;
        this.vaultService = vaultService;
        this.objectMapper = new ObjectMapper();
        this.mapOutputConverter = new MapOutputConverter();
    }

    public void generateAndSaveTerraform(String terraformJobId, String analysisJobId, String userId, String token) {
        System.out.println("🚀 [TF-SERVICE] Starting generation for TF Job: " + terraformJobId);

        TerraformJob job = new TerraformJob();
        job.setId(terraformJobId);
        job.setAnalysisJobId(analysisJobId);
        job.setUserId(userId);
        job.setStatus("GENERATING");
        terraformJobRepository.save(job);

        CompletableFuture.runAsync(() -> {
            try {
                System.out.println("🔍 [TF-SERVICE] Reading Blueprint from Database...");
                AnalysisJob analysisJob = analysisJobRepository.findById(analysisJobId)
                        .orElseThrow(() -> new RuntimeException("Analysis blueprint not found for ID: " + analysisJobId));

                JsonNode blueprintNode = analysisJob.getBlueprintJson();
                if (blueprintNode == null || blueprintNode.isNull()) {
                    throw new RuntimeException("Blueprint JSON is empty in database.");
                }

                String blueprintJsonString = blueprintNode.toPrettyString();
                String repoName = analysisJob.getRepositoryName();
                String computeType = blueprintNode.path("computeCategory").asText();

                // --- PROMPTS ---
                String promptNoAnsible = String.format("""
                    You are a Principal Cloud Architect and Terraform Expert specialized in Container Orchestration and Serverless.
                    Your task is to generate PRODUCTION-READY Terraform code for a MANAGED CONTAINER/K8S deployment based on the blueprint.

                    --- ARCHITECTURAL BLUEPRINT (JSON) ---
                    %s
                    --------------------------------------

                    ENGINEERING REQUIREMENTS & BEST PRACTICES:
                        1. **Providers (`providers.tf`)**: Configure the provider based on 'targetCloud'.
                        2. **Variables (`variables.tf`)**: Extract configurations dynamically. No hardcoding of infrastructure sizes.
                        3. **Core Infrastructure (`main.tf`)**:
                            - Provision the managed service based on 'targetCompute' (e.g., AWS ECS, EKS).
                            - **Sizing**: Strictly use the values provided in the 'computeSpecs' object (e.g., CPU, Memory, Node Types, Replicas).
                            - **Security & IAM**: Generate necessary IAM Roles, Execution Policies, and Task Definitions.
                            - **Networking**: Generate VPC, Subnets, and a Load Balancer (ALB/NLB). Route traffic to the 'targetContainerPort'.
                            - **Health Checks**: If 'deploymentMetadata.healthCheckEndpoint' is provided, configure the Load Balancer target group health check to use that path.
                            - **Environment Variables**: Inject ALL key-value pairs from 'configurationSettings' directly into the container definition/spec.
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
                    """, blueprintJsonString);

                String promptYesAnsible = String.format("""
                    You are a Principal Cloud Architect and Terraform Expert specialized in Infrastructure-as-Service (IaaS).
                    Your task is to generate PRODUCTION-READY Terraform code for a VIRTUAL MACHINE deployment based on the blueprint.

                    --- ARCHITECTURAL BLUEPRINT (JSON) ---
                    %s
                    --------------------------------------

                    ENGINEERING REQUIREMENTS & BEST PRACTICES:
                        1. **Providers (`providers.tf`)**: Configure the provider based on 'targetCloud'.
                        2. **Variables (`variables.tf`)**: 
                            - Extract configurations dynamically.
                            - MANDATORY: Include a variable for `ssh_public_key` to be used for instance access.
                        3. **Core Infrastructure (`main.tf`)**:
                            - **VM Sizing & OS**: Select the Instance Type based on 'computeSpecs.instance_family'. Select the Machine Image (AMI) based precisely on the 'deploymentMetadata.osDistro' (e.g., Ubuntu 22.04).
                            - **SSH Access**: Create an SSH Key Pair resource (e.g., `aws_key_pair`) using the `ssh_public_key` variable, and attach it to the instance.
                            - **Networking & Security**: Generate VPC, Subnets, and Security Groups. Ensure inbound traffic is explicitly allowed for port 22 (SSH) AND the 'targetContainerPort' defined in the JSON.
                            - **Environment Variables**: Inject 'configurationSettings' into the VM metadata or user_data script.
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
                    """, blueprintJsonString);

                String selectedBasePrompt;
                String sshPublicKey = null;

                if ("VM".equalsIgnoreCase(computeType)) {
                    selectedBasePrompt = promptYesAnsible;
                    System.out.println("🎯 [TF-SERVICE] - "+ computeType + " - Using VM-optimized prompt.");
                    sshPublicKey = vaultService.createAndStoreSshKeyPair(userId, repoName, terraformJobId);
                } else {
                    selectedBasePrompt = promptNoAnsible;
                    System.out.println("🎯 [TF-SERVICE] - "+ computeType + " - Using Container/K8S-optimized prompt.");
                }

                String finalPrompt = selectedBasePrompt + "\n\n" + mapOutputConverter.getFormat();

                Map<String, String> finalTfFiles = new HashMap<>(); // Η "μνήμη" μας
                boolean isValid = false;
                int attempts = 0;
                String lastError = "";

                while (!isValid && attempts < 3) {
                    attempts++;

                    String currentPrompt;
                    if (attempts == 1) {
                        currentPrompt = finalPrompt;
                    } else {
                        // Στο retry, του θυμίζουμε ΤΙ θέλουμε (Schema) και του λέμε να στείλει ΟΛΑ τα αρχεία.
                        currentPrompt = "The previous Terraform code you generated had validation errors:\n" + lastError +
                                "\n\nPlease fix the code and return a SINGLE, VALID JSON object containing ALL files." +
                                "\n\nCRITICAL: You MUST include 'main.tf', 'variables.tf', 'outputs.tf', and 'providers.tf' in your response. Do not send partial updates." +
                                "\n\n" + mapOutputConverter.getFormat();
                    }

                    System.out.println("🧠 [TF-SERVICE] Calling Gemini - Attempt #" + attempts);
                    String aiResponse = chatModel.call(currentPrompt);

                    assert aiResponse != null;
                    Map<String, String> newFiles = parseAiResponse(aiResponse);

                    // ΑΝΤΙ ΝΑ ΔΙΑΓΡΑΦΟΥΜΕ ΤΑ ΠΑΛΙΑ ΑΡΧΕΙΑ, ΤΑ ΚΑΝΟΥΜΕ MERGE
                    finalTfFiles.putAll(newFiles);

                    // Αν είναι VM, σιγουρευόμαστε ότι το κλειδί SSH δεν χάθηκε στο Merge
                    if (sshPublicKey != null && finalTfFiles.containsKey("variables.tf")) {
                        String currentVars = finalTfFiles.get("variables.tf");
                        if (!currentVars.contains("ssh_public_key")) {
                            String injectedVar = "\nvariable \"ssh_public_key\" { default = \"" + sshPublicKey + "\" }\n";
                            finalTfFiles.put("variables.tf", currentVars + injectedVar);
                        }
                    }

                    // Ελέγχουμε αν όντως έχουμε όλα τα βασικά αρχεία, αλλιώς κάτι πήγε πολύ στραβά
                    if (!finalTfFiles.containsKey("main.tf") || !finalTfFiles.containsKey("providers.tf")) {
                        lastError = "Missing critical files (main.tf or providers.tf) in the JSON response.";
                        System.err.println("⚠️ [TF-SERVICE] Validation Error: " + lastError);
                        continue;
                    }

                    System.out.println("🔍 [TF-SERVICE] Validating code with Terraform CLI...");
                    ValidationResult result = performValidation(finalTfFiles);

                    if (result.valid) {
                        isValid = true;
                        System.out.println("✅ [TF-SERVICE] Code is VALID.");
                    } else {
                        lastError = result.errorOutput;
                        System.err.println("⚠️ [TF-SERVICE] Validation Error: " + lastError);
                    }
                }

                if (!isValid) {
                    throw new RuntimeException("AI failed to generate valid Terraform after 3 attempts. Last error: " + lastError);
                }

                System.out.println("📦 [TF-SERVICE] Packing valid files into ZIP...");
                byte[] zipBytes = createZipInMemory(finalTfFiles);

                job.setTerraformZip(zipBytes);
                job.setStatus("COMPLETED");
                terraformJobRepository.save(job);
                notifyOrchestrator(analysisJobId, "TERRAFORM", "COMPLETED", token);

            } catch (Exception e) {
                System.err.println("❌ [TF-SERVICE ERROR]: " + e.getMessage());
                job.setStatus("FAILED");
                terraformJobRepository.save(job);
                notifyOrchestrator(analysisJobId, "TERRAFORM", "FAILED", token);
            }
        });
    }

    private void notifyOrchestrator(String jobId, String service, String status, String token) {
        String url = String.format("http://repo-analyzer-svc/dashboard/internal/callback/%s?service=%s&status=%s",
                jobId, service, status);

        RestClient internalClient = RestClient.create();
        internalClient.post()
                .uri(url)
                .header("Authorization", "Bearer " + token) // 👈 Το token επιστρέφει στον Analyzer
                .retrieve()
                .toBodilessEntity();
    }

    private Map<String, String> parseAiResponse(String response) {
        String clean = response.trim();
        if (clean.startsWith("```")) {
            clean = clean.replaceAll("^```json\\s*", "").replaceAll("```$", "").trim();
        }
        Map<String, Object> raw = mapOutputConverter.convert(clean);
        Map<String, String> files = new HashMap<>();
        assert raw != null;
        raw.forEach((k, v) -> files.put(k, String.valueOf(v)));
        return files;
    }

    private ValidationResult performValidation(Map<String, String> files) {
        try {
            Path tempDir = Files.createTempDirectory("vortex-tf-");
            for (Map.Entry<String, String> entry : files.entrySet()) {
                Files.writeString(tempDir.resolve(entry.getKey()), entry.getValue());
            }
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
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                output.append(line).append("\n");
            }
        }
        p.waitFor();
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