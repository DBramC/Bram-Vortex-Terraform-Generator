package com.christos_bramis.bram_vortex_terraform_generator.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "terraform_jobs")
public class TerraformJob {

    @Id
    @Column(name = "id")
    private String id;

    // Προσθέτουμε nullable = false γιατί κάθε TF job πρέπει να συνδέεται με μια ανάλυση
    @Column(name = "analysis_job_id", nullable = false)
    private String analysisJobId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "status")
    private String status;

    // Το bytea είναι ο σωστός τύπος για ZIP/Binary αρχεία στην Postgres
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "terraform_zip", columnDefinition = "bytea")
    private byte[] terraformZip;

    // --- Constructors ---
    public TerraformJob() {}

    public TerraformJob(String id, String analysisJobId, String userId, String status) {
        this.id = id;
        this.analysisJobId = analysisJobId;
        this.userId = userId;
        this.status = status;
    }

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