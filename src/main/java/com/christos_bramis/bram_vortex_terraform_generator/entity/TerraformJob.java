package com.christos_bramis.bram_vortex_terraform_generator.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "terraform_jobs")
public class TerraformJob {

    @Id
    private String id;

    @Column(name = "analysis_job_id", nullable = false)
    private String analysisJobId;

    @Column(name = "user_id") // Ρητή δήλωση για να ταιριάζει με την Postgres
    private String userId;

    private String status;

    @Lob // Δηλώνει ότι είναι μεγάλο αρχείο (Binary)
    @Column(name = "terraform_zip")
    private byte[] terraformZip;

    // --- Getters & Setters (Παραμένουν ως έχουν) ---
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