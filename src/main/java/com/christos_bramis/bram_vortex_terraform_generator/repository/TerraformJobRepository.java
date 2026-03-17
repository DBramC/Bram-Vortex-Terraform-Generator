package com.christos_bramis.bram_vortex_terraform_generator.repository;

import com.christos_bramis.bram_vortex_terraform_generator.entity.TerraformJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TerraformJobRepository extends JpaRepository<TerraformJob, String> {
    // Μπορεί να χρειαστείς να βρεις το Terraform Job με βάση το Analysis Job
    TerraformJob findByAnalysisJobId(String analysisJobId);
}