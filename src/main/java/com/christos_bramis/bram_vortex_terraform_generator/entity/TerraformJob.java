package com.christos_bramis.bram_vortex_terraform_generator.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "terraform_jobs") // Ο δικός του, ανεξάρτητος πίνακας
public class TerraformJob {

    @Id
    private String id; // Το ID αυτού του Terraform Job

    @Column(name = "analysis_job_id", nullable = false)
    private String analysisJobId; // Κρατάμε το ID της ανάλυσης για reference

    private String userId;

    private String status; // π.χ. GENERATING, COMPLETED, FAILED

    // ΕΔΩ ΕΙΝΑΙ Η ΜΑΓΕΙΑ: Η Postgres θα το κάνει BYTEA (BLOB)
    @Column(name = "terraform_zip")
    private byte[] terraformZip;

    // --- Getters & Setters ---
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getAnalysisJobId() { return analysisJobId; }
    public void setAnalysisJobId(String analysisJobId) { this.analysisJobId = analysisJobId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public byte[] getTerraformZip() { return terraformZip; }
    public void setTerraformZip(byte[] terraformZip) { this.terraformZip = terraformZip; }
}