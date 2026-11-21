package ru.mephi.orm.web;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import ru.mephi.orm.entity.Role;
import ru.mephi.orm.security.Actor;
import ru.mephi.orm.service.AccountService;

@RestController
@RequestMapping("/api")
@SecurityRequirement(name = "bearerAuth")
public class AccountController {

    private final AccountService service;

    public AccountController(AccountService service) {
        this.service = service;
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public AccountService.Account create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AccountInput input) {
        return service.create(Actor.from(jwt), input.email(), input.name(), input.password(), input.role());
    }

    @GetMapping("/users/{id}")
    public AccountService.Account get(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return service.get(Actor.from(jwt), id);
    }

    @PutMapping("/users/{id}")
    public AccountService.Account update(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @Valid @RequestBody AccountInput input) {
        return service.update(Actor.from(jwt), id, input.email(), input.name(), input.password(), input.role());
    }

    @DeleteMapping("/users/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        service.delete(Actor.from(jwt), id);
    }

    @GetMapping("/users/{id}/profile")
    public AccountService.ProfileView profile(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return service.profile(Actor.from(jwt), id);
    }

    @PutMapping("/users/{id}/profile")
    public AccountService.ProfileView updateProfile(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @Valid @RequestBody ProfileInput input) {
        return service.updateProfile(Actor.from(jwt), id, input.bio(), input.avatarUrl());
    }

    @DeleteMapping("/users/{id}/profile")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProfile(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        service.deleteProfile(Actor.from(jwt), id);
    }

    public record AccountInput(@NotBlank @Email @Size(max = 254) String email, @NotBlank @Size(max = 255) String name,
                               @NotBlank @Size(min = 8, max = 72) String password, @NotNull Role role) {}
    public record ProfileInput(@Size(max = 4000) String bio, @Size(max = 255) String avatarUrl) {}
}
