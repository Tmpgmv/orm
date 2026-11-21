package ru.mephi.orm.web;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import ru.mephi.orm.security.Actor;
import ru.mephi.orm.service.EnrollmentService;

@RestController
@RequestMapping("/api")
@SecurityRequirement(name = "bearerAuth")
public class EnrollmentController {

    private final EnrollmentService service;

    public EnrollmentController(EnrollmentService service) {
        this.service = service;
    }

    @PostMapping("/courses/{courseId}/enrollments")
    @ResponseStatus(HttpStatus.CREATED)
    public EnrollmentService.Membership enroll(@AuthenticationPrincipal Jwt jwt, @PathVariable long courseId) {
        return service.enroll(Actor.from(jwt), courseId);
    }


    @DeleteMapping("/courses/{courseId}/enrollments/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdraw(@AuthenticationPrincipal Jwt jwt, @PathVariable long courseId) {
        service.withdraw(Actor.from(jwt), courseId);
    }

    @GetMapping("/enrollments/me")
    public List<EnrollmentService.Membership> mine(@AuthenticationPrincipal Jwt jwt) {
        return service.mine(Actor.from(jwt));
    }

    @GetMapping("/courses/{courseId}/enrollments")
    public List<EnrollmentService.Membership> roster(@AuthenticationPrincipal Jwt jwt,
                                                     @PathVariable long courseId) {
        return service.roster(Actor.from(jwt), courseId);
    }
}
