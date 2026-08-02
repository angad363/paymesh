package com.paymesh.refund.infrastructure.persistence.jpa;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Which constraint a violation names.
 *
 * <h2>THE TRIGGERS ARE NOT CONSTRAINTS AND CANNOT BE MATCHED BY NAME</h2>
 *
 * {@code tr_refunds_within_captured} raises with {@code ERRCODE = 'check_violation'} and no
 * constraint name -- PostgreSQL attaches a name to a declared CHECK, not to a {@code RAISE} inside
 * a trigger function. So a unique-index violation is recognised by name and a trigger's refusal is
 * recognised by its message, which is the only handle it offers. The message text is therefore part
 * of the contract between V16 and this class, and a test pins it.
 */
final class RefundConstraintViolations {

    private RefundConstraintViolations() {
    }

    static boolean violates(DataIntegrityViolationException exception, String constraintName) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return constraintName.equals(violation.getConstraintName());
            }
        }

        return false;
    }

    /** True when the over-refund trigger refused the write. Matched on the message; see above. */
    static boolean isOverRefund(RuntimeException exception) {
        return messageChain(exception).contains("exceeds the");
    }

    /** True when the currency-match trigger refused the write. */
    static boolean isCurrencyMismatch(RuntimeException exception) {
        return messageChain(exception).contains("but payment intent");
    }

    /** True when the over-refund trigger found no such payment intent. */
    static boolean isUnknownPaymentIntent(RuntimeException exception) {
        return messageChain(exception).contains("which does not exist");
    }

    private static String messageChain(Throwable exception) {
        StringBuilder messages = new StringBuilder();

        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null) {
                messages.append(cause.getMessage()).append('\n');
            }

            if (cause.getCause() == cause) {
                break;
            }
        }

        return messages.toString();
    }
}
