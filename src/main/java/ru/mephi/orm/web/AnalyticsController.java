package ru.mephi.orm.web;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.mephi.orm.repository.AnalyticsQueries.QuizStatistics;
import ru.mephi.orm.repository.AnalyticsQueries.RankingEntry;
import ru.mephi.orm.security.Actor;
import ru.mephi.orm.service.AnalyticsService;

@RestController
@RequestMapping("/api/analytics")
@SecurityRequirement(name = "bearerAuth")
public class AnalyticsController {

    private final AnalyticsService analytics;

    public AnalyticsController(AnalyticsService analytics) {
        this.analytics = analytics;
    }

    @GetMapping("/me")
    public AnalyticsService.StudentPerformance performance(@AuthenticationPrincipal Jwt jwt) {
        return analytics.performance(Actor.from(jwt));
    }

    @GetMapping("/courses/{courseId}/ranking")
    public List<RankingEntry> ranking(@AuthenticationPrincipal Jwt jwt, @PathVariable long courseId) {
        return analytics.ranking(Actor.from(jwt), courseId);
    }

    @GetMapping("/quizzes/{quizId}/statistics")
    public QuizStatistics quiz(@AuthenticationPrincipal Jwt jwt, @PathVariable long quizId) {
        return analytics.quiz(Actor.from(jwt), quizId);
    }
}
