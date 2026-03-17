package com.christos_bramis.bram_vortex_terraform_generator.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "analysis_jobs")
public class AnalysisJob {

    @Id
    @Column(name = "job_id", insertable = false, updatable = false)
    private String jobId;

    // Εδώ είναι το μόνο πεδίο που μας νοιάζει να διαβάσουμε!
    @Column(name = "blueprint_json", columnDefinition = "jsonb", insertable = false, updatable = false)
    private String blueprintJson;

    // Βάζουμε ΜΟΝΟ Getters. Καθόλου Setters για να είναι 100% Read-Only!
    public String getJobId() { return jobId; }
    public String getBlueprintJson() { return blueprintJson; }
}