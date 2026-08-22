package com.paymesh.reporting.infrastructure.schedule;

import com.paymesh.reporting.application.GenerateReportExportsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The timer, and nothing else -- the reviewed shape ({@code NotificationDispatcher},
 * {@code WebhookDispatcher}). No logic here: everything lives in
 * {@link GenerateReportExportsService}, a plain object taking an injected {@code Clock} that tests
 * drive directly. Absent under {@code dev}, like every other timer in this codebase.
 */
public final class ReportExportGenerator {

    private static final Logger log = LoggerFactory.getLogger(ReportExportGenerator.class);

    private final GenerateReportExportsService generate;

    public ReportExportGenerator(GenerateReportExportsService generate) {
        this.generate = generate;
    }

    @Scheduled(
        fixedDelayString = "${paymesh.reporting.export.interval}",
        initialDelayString = "${paymesh.reporting.export.interval}"
    )
    public void generate() {
        GenerateReportExportsService.GenerationResult result = generate.generate();

        if (result.examined() > 0) {
            log.info(
                "Report export generation examined={} completed={} failed={} errored={}",
                result.examined(), result.completed(), result.failed(), result.errored()
            );
        }
    }
}
