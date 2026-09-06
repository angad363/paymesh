package com.paymesh.simulator.infrastructure.persistence.jpa;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Reading which database constraint refused a write.
 * <p>
 * A copy of {@code payment.infrastructure.persistence.jpa.ConstraintViolations}, which is
 * package-private there and therefore not reachable from here. <b>Promoting it to
 * {@code com.paymesh.shared} was considered and rejected:</b> that would put a class every module
 * imports on the extraction path of a module whose whole point is having no shared code with
 * PayMesh, and thirty lines of cause-chain walk is a cheaper duplicate than a shared dependency this
 * module is not supposed to have.
 */
final class SimulatorConstraintViolations {

    private SimulatorConstraintViolations() {
    }

    /**
     * Whether this integrity violation came from the named constraint.
     * <p>
     * Spring wraps Hibernate's exception, which is the only layer that knows the constraint's name,
     * so the cause chain has to be walked. NARROWING BY NAME IS THE POINT: an adapter that guesses
     * from the aggregate's shape will eventually report an amount CHECK as a duplicate key. A
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
