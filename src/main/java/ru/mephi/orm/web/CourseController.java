package ru.mephi.orm.web;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import ru.mephi.orm.security.Actor;
import ru.mephi.orm.service.CourseService;

@RestController
@RequestMapping("/api")
@SecurityRequirement(name = "bearerAuth")
public class CourseController {

    private final CourseService service;

    public CourseController(CourseService service) {
        this.service = service;
    }

    @GetMapping("/categories")
    public List<CourseService.Named> categories() {
        return service.categories();
    }

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    public CourseService.Named createCategory(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Name input) {
        return service.createCategory(Actor.from(jwt), input.name());
    }

    @PutMapping("/categories/{id}")
    public CourseService.Named updateCategory(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @Valid @RequestBody Name input) {
        return service.updateCategory(Actor.from(jwt), id, input.name());
    }

    @DeleteMapping("/categories/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCategory(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        service.deleteCategory(Actor.from(jwt), id);
    }

    @GetMapping("/courses")
    public List<CourseService.CourseSummary> list() {
        return service.list();
    }

    @GetMapping("/courses/{id}")
    public CourseService.CourseView get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping("/courses")
    @ResponseStatus(HttpStatus.CREATED)
    public CourseService.CourseView create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CourseRequest input) {
        return service.create(Actor.from(jwt), input.toInput());
    }

    @PutMapping("/courses/{id}")
    public CourseService.CourseView update(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @Valid @RequestBody CourseRequest input) {
        return service.update(Actor.from(jwt), id, input.toInput());
    }

    @DeleteMapping("/courses/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        service.delete(Actor.from(jwt), id);
    }

    @PostMapping("/courses/{courseId}/modules")
    @ResponseStatus(HttpStatus.CREATED)
    public CourseService.ModuleView createModule(@AuthenticationPrincipal Jwt jwt, @PathVariable long courseId, @Valid @RequestBody ModuleRequest input) {
        return service.createModule(Actor.from(jwt), courseId, input.toInput());
    }

    @PutMapping("/courses/{courseId}/modules/{moduleId}")
    public CourseService.ModuleView updateModule(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long courseId,
            @PathVariable long moduleId,
            @Valid @RequestBody ModuleRequest input) {
        return service.updateModule(Actor.from(jwt), courseId, moduleId, input.toInput());
    }

    @DeleteMapping("/courses/{courseId}/modules/{moduleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteModule(@AuthenticationPrincipal Jwt jwt, @PathVariable long courseId, @PathVariable long moduleId) {
        service.deleteModule(Actor.from(jwt), courseId, moduleId);
    }

    @PostMapping("/courses/{courseId}/modules/{moduleId}/lessons")
    @ResponseStatus(HttpStatus.CREATED)
    public CourseService.LessonView createLesson(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long courseId,
            @PathVariable long moduleId,
            @Valid @RequestBody LessonRequest input) {
        return service.createLesson(Actor.from(jwt), courseId, moduleId, input.toInput());
    }

    @PutMapping("/courses/{courseId}/modules/{moduleId}/lessons/{lessonId}")
    public CourseService.LessonView updateLesson(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long courseId,
            @PathVariable long moduleId,
            @PathVariable long lessonId,
            @Valid @RequestBody LessonRequest input) {
        return service.updateLesson(Actor.from(jwt), courseId, moduleId, lessonId, input.toInput());
    }

    @DeleteMapping("/courses/{courseId}/modules/{moduleId}/lessons/{lessonId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteLesson(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long courseId,
            @PathVariable long moduleId,
            @PathVariable long lessonId) {
        service.deleteLesson(Actor.from(jwt), courseId, moduleId, lessonId);
    }

    @PostMapping("/courses/{courseId}/tags")
    @ResponseStatus(HttpStatus.CREATED)
    public CourseService.CourseView addTag(@AuthenticationPrincipal Jwt jwt, @PathVariable long courseId, @Valid @RequestBody Name input) {
        return service.addTag(Actor.from(jwt), courseId, input.name());
    }

    @DeleteMapping("/courses/{courseId}/tags/{tagId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeTag(@AuthenticationPrincipal Jwt jwt, @PathVariable long courseId, @PathVariable long tagId) {
        service.removeTag(Actor.from(jwt), courseId, tagId);
    }

    public record Name(@NotBlank @Size(max = 255) String name) {}
    public record CourseRequest(@NotBlank @Size(max = 255) String title, @Size(max = 4000) String description,
                                @Positive long categoryId, LocalDate startDate) {
        CourseService.CourseInput toInput() {
            return new CourseService.CourseInput(title, description, categoryId, startDate);
        }
    }
    public record ModuleRequest(@NotBlank @Size(max = 255) String title, @NotNull @PositiveOrZero Integer position) {
        CourseService.ModuleInput toInput() {
            return new CourseService.ModuleInput(title, position);
        }
    }
    public record LessonRequest(@NotBlank @Size(max = 255) String title, @Size(max = 12000) String content,
                                @Size(max = 255) String materialUrl, @NotNull @PositiveOrZero Integer position,
                                Instant scheduledAt) {
        CourseService.LessonInput toInput() {
            return new CourseService.LessonInput(title, content, materialUrl, position, scheduledAt);
        }
    }
}
