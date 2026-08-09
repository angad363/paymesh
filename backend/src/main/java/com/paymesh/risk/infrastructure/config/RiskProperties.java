package com.paymesh.risk.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * How Risk is tuned.
 * <p>
 * ONLY THE WINDOW IS CONFIGURABLE, and the thresholds are not. That is deliberate rather than
 * unfinished: a threshold change alters what the rules DO, and {@code RiskRuleset.VERSION} has to
 * be bumped alongside it or every stored assessment starts claiming a version whose behaviour it
 * cannot reproduce (SDD §14.6). A threshold in a properties file can be changed by an operator who
 * has never heard of the version constant, which quietly breaks the one guarantee this capability
 * makes about its own history.
 * <p>
 * The window is different: it is an input to the feature snapshot, so a change to it is visible in
 * every affected row rather than invisible in all of them.
 *
 * @param velocityWindow how far back the intent count reaches. Long enough to catch a burst, short
 *     enough that ordinary repeat business does not look like one.
 */
@Validated
@ConfigurationProperties("paymesh.risk")
public record RiskProperties(Duration velocityWindow) {
}
