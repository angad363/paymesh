package com.paymesh.audit.infrastructure.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How the audit-export generator is tuned. The {@code interval} is resolved straight into
 * {@code @Scheduled} from the environment rather than bound here -- two places to read one value is
 * how one of them drifts, the same note {@code ReportExportProperties} carries.
 *
 * @param enabled   whether the timer bean is registered at all. <b>Defaults on; the dev profile
 *     turns it off</b>, because the suite runs under dev and a timer flipping an export to COMPLETED
 *     while a test asserts it is PENDING is a flake generator
 * @param batchSize how many PENDING exports one pass may take, each in its own transaction
 * @param maxRows   the largest export a single row may carry. The CSV lives in a TEXT column (V36),
 *     so this is the ceiling that stops one platform-wide year-long window becoming a multi-megabyte
 *     column. An export over the cap is FAILED with a reason naming the number
 */
@Validated
@ConfigurationProperties("paymesh.audit.export")
public record AuditExportProperties(

    boolean enabled,

    @Min(1)
    int batchSize,

    @Min(1)
    int maxRows
) {
}
