package ru.mephi.orm.security;

import org.springframework.security.oauth2.jwt.Jwt;
import ru.mephi.orm.entity.Role;
import ru.mephi.orm.service.ApiFailure;

public record Actor(long id, Role role) {
    public static Actor from(Jwt jwt) {
        return new Actor(Long.parseLong(jwt.getSubject()), Role.valueOf(jwt.getClaimAsString("role")));
    }

    public void require(Role expected) {
        if (role != expected) {
            throw ApiFailure.forbidden();
        }
    }

    public void requireOwner(long ownerId) {
        if (id != ownerId) {
            throw ApiFailure.forbidden();
        }
    }


    public void requireSelfOrAdmin(long userId) {
        if (id != userId && role != Role.ADMIN) {
            throw ApiFailure.forbidden();
        }
    }
}
