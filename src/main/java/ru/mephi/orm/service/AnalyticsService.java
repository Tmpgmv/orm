package ru.mephi.orm.service;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.mephi.orm.entity.Role;
import ru.mephi.orm.repository.AnalyticsQueries;
import ru.mephi.orm.repository.AnalyticsQueries.CoursePerformance;
import ru.mephi.orm.repository.AnalyticsQueries.QuizStatistics;
import ru.mephi.orm.repository.AnalyticsQueries.RankingEntry;
import ru.mephi.orm.repository.CourseRepository;
import ru.mephi.orm.repository.QuizRepository;
import ru.mephi.orm.security.Actor;

@Service
@Transactional(readOnly = true)
public class AnalyticsService {

    private final AnalyticsQueries queries;
    private final CourseRepository courses;
    private final QuizRepository quizzes;
    private final CourseAccess access;

    public AnalyticsService(AnalyticsQueries queries, CourseRepository courses, QuizRepository quizzes,
                            CourseAccess access) {
        this.queries = queries;
        this.courses = courses;
        this.quizzes = quizzes;
        this.access = access;
    }

    public StudentPerformance performance(Actor actor) {
        actor.require(Role.STUDENT);
        var results = queries.student(actor.id());
        long earned = results.stream().mapToLong(CoursePerformance::earnedPoints).sum();
        long possible = results.stream().mapToLong(CoursePerformance::possiblePoints).sum();
        return new StudentPerformance(earned, possible, results);
    }

    public List<RankingEntry> ranking(Actor actor, long courseId) {
        access.teacher(actor, ApiFailure.found(courses.findById(courseId)));
        return queries.ranking(courseId);
    }

    public QuizStatistics quiz(Actor actor, long quizId) {
        var quiz = ApiFailure.found(quizzes.findById(quizId));
        access.teacher(actor, quiz.getCourse());
        return queries.quiz(quizId);
    }

    public record StudentPerformance(long earnedPoints, long possiblePoints, List<CoursePerformance> courses) {}
}
