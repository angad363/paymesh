package com.paymesh.identity.infrastructure.persistence.jpa;

import com.paymesh.identity.domain.Role;
import com.paymesh.identity.domain.RoleAssignment;
import com.paymesh.identity.domain.User;
import com.paymesh.identity.domain.UserId;
import com.paymesh.identity.domain.UserStatus;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Translates between the domain aggregate and the persistence row, in both
 * directions (ADR-004). Explicit and field-by-field so a schema change surfaces
 * as a compile error, not as lost data.
 */
public final class UserJpaMapper {

    private UserJpaMapper() {
    }

    public static UserJpaEntity toEntity(User user) {
        Set<UserRoleEmbeddable> roles = new LinkedHashSet<>();

        for (RoleAssignment assignment : user.roles()) {
            roles.add(new UserRoleEmbeddable(assignment.role().name(), assignment.merchantId()));
        }

        return new UserJpaEntity(
            user.userId().value(),
            user.email(),
            user.passwordHash(),
            user.status().name(),
            roles,
            user.createdAt(),
            user.updatedAt()
        );
    }

    public static User toDomain(UserJpaEntity entity) {
        return User.reconstitute(
            UserId.from(entity.userId()),
            entity.email(),
            entity.passwordHash(),
            UserStatus.valueOf(entity.status()),
            entity.roles().stream()
                .map(role -> new RoleAssignment(
                    Role.valueOf(role.role()),
                    role.merchantId()
                ))
                .toList(),
            entity.createdAt(),
            entity.updatedAt()
        );
    }
}
