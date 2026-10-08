package com.paymesh.payment.infrastructure.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * How Payment's confirm reaches a risk decision, and what it does when it cannot (ADR-044).
 * <p>
 * SHARES THE {@code paymesh.risk} PREFIX with {@code com.paymesh.risk.infrastructure.config.
 * RiskProperties} (the in-process module's own {@code velocityWindow}) ON PURPOSE: inside this one
 * process, both are "how Risk is configured", and Spring Boot happily binds two
 * {@code @ConfigurationProperties} records to the same prefix as long as their fields do not
 * collide. Splitting them under different prefixes would suggest two unrelated concerns where
 * there is one.
 *
 * @param mode {@code in-process} (default, the dual-path safety net -- {@code RiskModuleCheck})
 *     or {@code http} ({@code RiskServiceHttpCheck}, calling the extracted risk deployable).
 *     {@code @NotBlank} rather than an enum: an unrecognized value must be a clear startup failure
 *     at the {@code @ConditionalOnProperty} matcher, not a silently-ignored typo.
 * @param evaluateUrl where the risk service's evaluation endpoint lives. Only read in {@code http}
 *     mode; defaults to the risk deployable's own port so a dev environment needs no override to
 *     flip the flag.
 * @param evaluateKey the shared secret {@code RiskEvaluationKeyFilter} checks on the other side of
 *     the wire (ADR-044 section 3). Only read in {@code http} mode.
 * @param connectTimeout how long the HTTP call may take to open a connection before this counts as
 *     unreachable.
 * @param readTimeout how long the HTTP call may wait for a response before this counts as
 *     unreachable -- also the circuit breaker's slow-call threshold (a call that is still running
 *     at this age is treated as a failure even if it eventually completes).
 * @param fallbackBlockAtOrAboveMinor the by-tier threshold {@code RiskUnavailablePolicy} refuses at
 *     or above when Risk cannot be reached. Default 50000 minor units (500.00 in a two-decimal
 *     currency) -- a placeholder proportionate figure, not a risk-modelled one; there is no fraud
 *     data behind it, only the judgement that a mid-size payment is where "refuse rather than
 *     guess" starts being worth a legitimate customer's failed confirm.
 * @param breakerSlidingWindowSize how many of the most recent calls the breaker's failure rate is
 *     computed over.
 * @param breakerFailureRateThreshold the percentage of those calls that must fail before the
 *     breaker opens.
 * @param breakerWaitDurationInOpenState how long the breaker stays open before allowing a trial
 *     call through (half-open).
 */
@Validated
@ConfigurationProperties("paymesh.risk")
public record RiskClientProperties(

    @NotBlank
    String mode,

    String evaluateUrl,

    String evaluateKey,

    Duration connectTimeout,

    Duration readTimeout,

    @Positive
    long fallbackBlockAtOrAboveMinor,

    @Positive
    int breakerSlidingWindowSize,

    @Positive
    float breakerFailureRateThreshold,

    Duration breakerWaitDurationInOpenState
) {
}
