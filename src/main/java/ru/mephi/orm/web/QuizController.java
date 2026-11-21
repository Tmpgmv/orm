package ru.mephi.orm.web;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import ru.mephi.orm.entity.QuestionType;
import ru.mephi.orm.security.Actor;
import ru.mephi.orm.service.QuizService;

@RestController
@RequestMapping("/api")
@SecurityRequirement(name = "bearerAuth")
public class QuizController {

    private final QuizService service;

    public QuizController(QuizService service) {
        this.service = service;
    }

    @PostMapping("/courses/{courseId}/quizzes")
    @ResponseStatus(HttpStatus.CREATED)
    public QuizService.QuizView create(@AuthenticationPrincipal Jwt jwt, @PathVariable long courseId, @Valid @RequestBody QuizRequest input) {
        return service.create(Actor.from(jwt), courseId, input.toInput());
    }

    @GetMapping("/courses/{courseId}/quizzes")
    public List<QuizService.QuizSummary> list(@AuthenticationPrincipal Jwt jwt, @PathVariable long courseId) {
        return service.list(Actor.from(jwt), courseId);
    }

    @GetMapping("/quizzes/{id}")
    public QuizService.QuizView read(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return service.read(Actor.from(jwt), id);
    }

    @PutMapping("/quizzes/{id}")
    public QuizService.QuizView update(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @Valid @RequestBody QuizRequest input) {
        return service.update(Actor.from(jwt), id, input.toInput());
    }

    @DeleteMapping("/quizzes/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        service.delete(Actor.from(jwt), id);
    }

    @PostMapping("/quizzes/{id}/attempts")
    @ResponseStatus(HttpStatus.CREATED)
    public QuizService.Result submit(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @Valid @RequestBody Answers input) {
        return service.submit(Actor.from(jwt), id, input.answers());
    }

    @GetMapping("/quizzes/{id}/attempts")
    public List<QuizService.Result> results(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return service.results(Actor.from(jwt), id);
    }

    @GetMapping("/quiz-attempts/{id}")
    public QuizService.Result result(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return service.result(Actor.from(jwt), id);
    }

    public record QuizRequest(@NotBlank @Size(max = 255) String title,
                              @NotEmpty @Size(max = 100) List<@NotNull @Valid QuestionRequest> questions) {
        QuizService.QuizInput toInput() {
            return new QuizService.QuizInput(title, questions.stream().map(QuestionRequest::toInput).toList());
        }
    }
    public record QuestionRequest(@NotBlank @Size(max = 4000) String prompt, @NotNull QuestionType type,
                                  @NotNull @Size(min = 2, max = 30) List<@NotNull @Valid OptionRequest> options) {
        QuizService.QuestionInput toInput() {
            return new QuizService.QuestionInput(prompt, type, options.stream().map(OptionRequest::toInput).toList());
        }
    }
    public record OptionRequest(@NotBlank @Size(max = 2000) String text, @NotNull Boolean correct) {
        QuizService.OptionInput toInput() {
            return new QuizService.OptionInput(text, correct);
        }
    }
    public record Answers(@NotEmpty @Size(max = 100) Map<@NotNull @Positive Long,
            @NotEmpty @Size(max = 30) Set<@NotNull @Positive Long>> answers) {}
}
