package com.paymesh.payment.infrastructure.persistence.jpa;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Reading which database constraint refused a write.
 * <p>
 * Shared by this package's adapters because both of them translate a violation into a business
 * exception and neither may guess. It was written twice before the second adapter existed; a third
 * copy arrives with provider callbacks, which is one more than a duplicated cause-chain walk is
 * worth.
 */
final class ConstraintViolations {

    private ConstraintViolations() {
    }

    /**
     * Whether this integrity violation came from the named constraint.
     * <p>
     * Spring wraps Hibernate's exception, which is the only layer that knows the constraint's name,
     * so the cause chain has to be walked. NARROWING BY NAME IS THE POINT: an adapter that guesses
     * from the aggregate's shape will eventually report a foreign-key failure as a duplicate, and
     * "you already have one of these" said about another tenant's row is both wrong and a hint. A
     * violation whose name cannot be read is treated as "not this one", because mislabelling an
     * unknown failure as a known conflict is worse than a 500 that admits something went wrong.
     */
    static boolean violates(DataIntegrityViolationException exception, String constraintName) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return constraintName.equals(violation.getConstraintName());
            }
        }

        return false;
    }
}
