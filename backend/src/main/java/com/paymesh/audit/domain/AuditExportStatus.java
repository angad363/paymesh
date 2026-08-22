package com.paymesh.audit.domain;

/** Where an audit export is. Mirrors {@code ck_audit_exports_status} (V36). */
public enum AuditExportStatus {

    /** Requested, not yet rendered. The generator's candidate set. */
    PENDING,

    /** Rendered. The row carries its CSV, its row count and its completion time. */
    COMPLETED,

    /**
     * Given up on. Terminal, and today only reachable for a window holding more rows than a single
     * TEXT column may carry; a transient failure stays PENDING and retries, because rendering rows
     * this process can already read is deterministic.
     */
    FAILED
}
