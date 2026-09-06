package com.paymesh.shared.idempotency.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Persistence model for the idempotency_records table (V4__create_idempotency_records.sql).
 * Column definitions must match that migration -- ddl-auto=validate fails startup on drift.
 * <p>
 * Read-only in practice: the adapter writes through single statements so the database decides the
 * outcome of a race, and this entity is only ever loaded.
 */
@Entity
@Table(name = "idempotency_records")
public class IdempotencyRecordJpaEntity {

    @EmbeddedId
    private IdempotencyRecordJpaId id;

    // CHAR(64) in the migration, not VARCHAR. Hibernate maps String to VARCHAR by default and
    // schema validation compares JDBC type codes, so the fixed-width hash column must say so.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    // Enum NAME, never the ordinal; the mapper converts and thereby validates it.
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    // SMALLINT in the migration: HTTP statuses are 100-599, so the column is two bytes and the
    // field has to be the Java type that matches it.
    @Column(name = "response_status")
    private Short responseStatus;

    // TEXT, not VARCHAR(n): a stored response has no useful length bound and a truncated replay
    // would be worse than none.
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "response_body")
    private String responseBody;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    /** Required by JPA. Not for application use. */
    protected IdempotencyRecordJpaEntity() {
    }

    public IdempotencyRecordJpaId id() {
        return id;
    }

    public String requestHash() {
        return requestHash;
    }

    public String status() {
        return status;
    }

    public Short responseStatus() {
        return responseStatus;
    }

    public String responseBody() {
        return responseBody;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant completedAt() {
        return completedAt;
    }
}
