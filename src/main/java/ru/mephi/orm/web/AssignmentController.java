package ru.mephi.orm.web;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import ru.mephi.orm.security.Actor;
import ru.mephi.orm.service.AssignmentService;

@RestController
@RequestMapping("/api")
@SecurityRequirement(name = "bearerAuth")
public class AssignmentController {

    private final AssignmentService service;

    public AssignmentController(AssignmentService service) {
        this.service = service;
    }

    @PostMapping("/lessons/{lessonId}/assignments")
    @ResponseStatus(HttpStatus.CREATED)
    public AssignmentService.AssignmentView create(@AuthenticationPrincipal Jwt jwt, @PathVariable long lessonId, @Valid @RequestBody AssignmentRequest input) {
        return service.create(Actor.from(jwt), lessonId, input.toInput());
    }

    @GetMapping("/lessons/{lessonId}/assignments")
    public List<AssignmentService.AssignmentView> list(@AuthenticationPrincipal Jwt jwt, @PathVariable long lessonId) {
        return service.list(Actor.from(jwt), lessonId);
    }

    @GetMapping("/assignments/{id}")
    public AssignmentService.AssignmentView read(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return service.read(Actor.from(jwt), id);
    }

    @PutMapping("/assignments/{id}")
    public AssignmentService.AssignmentView update(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @Valid @RequestBody AssignmentRequest input) {
        return service.update(Actor.from(jwt), id, input.toInput());
    }

    @DeleteMapping("/assignments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        service.delete(Actor.from(jwt), id);
    }


    @GetMapping("/assignments/{id}/submissions")
    public List<AssignmentService.SubmissionView> submissions(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return service.submissions(Actor.from(jwt), id);
    }

    @PostMapping("/assignments/{id}/submissions")
    @ResponseStatus(HttpStatus.CREATED)
    public AssignmentService.SubmissionView submit(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @Valid @RequestBody Work input) {
        return service.submit(Actor.from(jwt), id, input.work());
    }

    @GetMapping("/submissions/{id}")
    public AssignmentService.SubmissionView submission(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return service.submission(Actor.from(jwt), id);
    }

    @PutMapping("/submissions/{id}/grade")
    public AssignmentService.SubmissionView grade(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @Valid @RequestBody Grade input) {
        return service.grade(Actor.from(jwt), id, input.score());
    }

    public record AssignmentRequest(@NotBlank @Size(max = 255) String title,
                                    @NotBlank @Size(max = 12000) String instructions,
                                    Instant deadline, @Positive int maxScore) {
        AssignmentService.AssignmentInput toInput() {
            return new AssignmentService.AssignmentInput(title, instructions, deadline, maxScore);
        }
    }
    public record Work(@NotBlank @Size(max = 12000) String work) {}

    public record Grade(@NotNull @PositiveOrZero Integer score) {}
}
