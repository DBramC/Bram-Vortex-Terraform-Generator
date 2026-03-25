package com.christos_bramis.bram_vortex_terraform_generator.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "analysis_jobs")
public class AnalysisJob {

    @Id
    @Column(name = "job_id", insertable = false, updatable = false)
    private String jobId;

    @Column(name = "repository_name", insertable = false, updatable = false)
    private String repositoryName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "blueprint_json", columnDefinition = "jsonb")
    private JsonNode blueprintJson;
    // Βάζουμε ΜΟΝΟ Getters. Καθόλου Setters για να είναι 100% Read-Only!
    public String getJobId() { return jobId; }
    public JsonNode getBlueprintJson() { return blueprintJson; }
    public String getRepositoryName() { return repositoryName; }
}