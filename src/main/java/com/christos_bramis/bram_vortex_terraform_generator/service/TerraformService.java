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
    private final ObjectMapper objectMapper; // Jackson για το JSON parsing

    public TerraformService(TerraformJobRepository terraformJobRepository,
                            AnalysisJobRepository analysisJobRepository,
                            ChatModel chatModel) {
        this.terraformJobRepository = terraformJobRepository;
        this.analysisJobRepository = analysisJobRepository;
        this.chatModel = chatModel;
        this.objectMapper = new ObjectMapper();
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
                // ΒΗΜΑ Α: Ανάγνωση Blueprint απευθείας από τη Βάση
                System.out.println("🔍 [TF-SERVICE] Reading Blueprint from Database...");

                AnalysisJob analysisJob = analysisJobRepository.findById(analysisJobId)
                        .orElseThrow(() -> new RuntimeException("Analysis blueprint not found for ID: " + analysisJobId));

                String blueprintJson = analysisJob.getBlueprintJson();

                if (blueprintJson == null || blueprintJson.isEmpty()) {
                    throw new RuntimeException("Blueprint JSON is empty in database.");
                }

                // Εξαγωγή του computeType από το JSON string χωρίς αλλαγή στο Entity
                JsonNode rootNode = objectMapper.readTree(blueprintJson);
                String computeType = rootNode.path("computeType").asText("Managed Container");

                System.out.println("🎯 [TF-SERVICE] Detected Compute Type: " + computeType);

                // ΒΗΜΑ Β: Ορισμός των Prompts
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

                // ΒΗΜΑ Γ: Επιλογή Prompt και Κλήση στο Gemini
                var mapOutputConverter = new MapOutputConverter();
                String selectedBasePrompt;

                if ("Virtual Machine".equalsIgnoreCase(computeType)) {
                    selectedBasePrompt = promptYesAnsible;
                    System.out.println("🎯 [TF-SERVICE] Using VM-optimized prompt (Ansible-ready).");
                } else {
                    selectedBasePrompt = promptNoAnsible;
                    System.out.println("🎯 [TF-SERVICE] Using Container/K8S-optimized prompt.");
                }

                String finalPrompt = selectedBasePrompt + "\n\n" + mapOutputConverter.getFormat();

                System.out.println("🧠 [TF-SERVICE] Calling Gemini AI...");
                String aiResponse = chatModel.call(finalPrompt);

                // Καθαρισμός του string από πιθανά Markdown backticks
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