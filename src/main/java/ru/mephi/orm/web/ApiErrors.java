package ru.mephi.orm.web;

import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.mephi.orm.service.ApiFailure;

@RestControllerAdvice
@Order(-1)
public class ApiErrors {

    @ExceptionHandler(ApiFailure.class)
    ProblemDetail expected(ApiFailure failure) {
        return ProblemDetail.forStatusAndDetail(failure.status(), failure.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail constraint() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "The change conflicts with existing data");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException failure) {
        var detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
        detail.setProperty("fields", failure.getBindingResult().getFieldErrors().stream()
                .map(error -> new InvalidField(error.getField(), error.getDefaultMessage())).toList());
        return detail;
    }

    record InvalidField(String field, String message) {}
}
