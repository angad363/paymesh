package com.paymesh.reporting.domain;

/** Where an export is. Mirrors {@code ck_report_exports_status}. */
public enum ReportExportStatus {

    /** Requested, not yet rendered. The generator's candidate set. */
    PENDING,

    /** Rendered. The row carries its CSV, its row count and its completion time. */
    COMPLETED,

    /**
     * Given up on. Terminal, and today only reachable for a window the generator can never satisfy;
     * a transient failure stays PENDING and is retried, because rendering rows this process can
     * already read is deterministic and a budget would only turn a bug into silence.
     */
    FAILED
}
