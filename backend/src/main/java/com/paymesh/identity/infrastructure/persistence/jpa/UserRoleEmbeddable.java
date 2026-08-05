package com.paymesh.identity.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.util.Objects;

/**
 * One row of user_roles, owned by {@link UserJpaEntity} as an element collection.
 *
 * <p>A role assignment has no identity or lifecycle of its own -- it is created and
 * destroyed with the user -- so it is an embeddable rather than a second entity
 * with its own repository and mapper.
 *
 * <p>equals/hashCode are required: the collection is a Set, and the migration's two partial unique
 * indexes -- {@code uq_user_roles_merchant_scoped} and {@code uq_user_roles_platform_scoped} (V23)
 * -- say the same thing at the database. {@link java.util.Objects#equals} handles the null
 * merchantId a platform grant carries; a raw {@code .equals} would not.
 */
@Embeddable
public class UserRoleEmbeddable {

    @Column(name = "role", nullable = false, length = 32)
    private String role;

    /**
     * NULL for a platform-wide grant (V23). {@code ddl-auto=validate} checks nullability against
     * the live column, so this annotation and the migration have to agree or startup fails --
     * which is the whole reason it is stated rather than defaulted.
     */
    @Column(name = "merchant_id", length = 40)
    private String merchantId;

    /** Required by JPA. Not for application use. */
    protected UserRoleEmbeddable() {
    }

    public UserRoleEmbeddable(String role, String merchantId) {
        this.role = role;
        this.merchantId = merchantId;
    }

    public String role() {
        return role;
    }

    public String merchantId() {
        return merchantId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        if (!(other instanceof UserRoleEmbeddable that)) {
            return false;
        }

        return Objects.equals(role, that.role)
            && Objects.equals(merchantId, that.merchantId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(role, merchantId);
    }
}
