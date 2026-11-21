package ru.mephi.orm.service;

import java.util.Optional;
import org.springframework.http.HttpStatus;

public class ApiFailure extends RuntimeException {

    private final HttpStatus status;

    private ApiFailure(HttpStatus status, String detail) {
        super(detail);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    public static <T> T found(Optional<T> item) {
        return item.orElseThrow(ApiFailure::missing);
    }

    public static ApiFailure missing() {
        return new ApiFailure(HttpStatus.NOT_FOUND, "Resource not found");
    }

    public static ApiFailure forbidden() {
        return new ApiFailure(HttpStatus.FORBIDDEN, "Access denied");
    }

    public static ApiFailure badInput(String detail) {
        return new ApiFailure(HttpStatus.BAD_REQUEST, detail);
    }

    public static ApiFailure conflict(String detail) {
        return new ApiFailure(HttpStatus.CONFLICT, detail);
    }

    public static ApiFailure unauthorized() {
        return new ApiFailure(HttpStatus.UNAUTHORIZED, "Invalid credentials");
    }
}
