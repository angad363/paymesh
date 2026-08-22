package com.paymesh.audit.infrastructure.schedule;

import com.paymesh.audit.application.GenerateAuditExportsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The timer, and nothing else -- the reviewed shape ({@code ReportExportGenerator}). No logic here:
 * everything lives in {@link GenerateAuditExportsService}, a plain object taking an injected
 * {@code Clock} that tests drive directly. Absent under {@code dev}, like every other timer here.
 */
public final class AuditExportGenerator {

    private static final Logger log = LoggerFactory.getLogger(AuditExportGenerator.class);

    private final GenerateAuditExportsService generate;

    public AuditExportGenerator(GenerateAuditExportsService generate) {
        this.generate = generate;
    }

    @Scheduled(
        fixedDelayString = "${paymesh.audit.export.interval}",
        initialDelayString = "${paymesh.audit.export.interval}"
    )
    public void generate() {
        GenerateAuditExportsService.GenerationResult result = generate.generate();

        if (result.examined() > 0) {
            log.info(
                "Audit export generation examined={} completed={} failed={} errored={}",
                result.examined(), result.completed(), result.failed(), result.errored()
            );
        }
    }
}
