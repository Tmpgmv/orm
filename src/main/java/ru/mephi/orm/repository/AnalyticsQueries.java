package ru.mephi.orm.repository;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AnalyticsQueries {

    private static final String PERFORMANCE = """
            SELECT c.id AS course_id, c.title, e.student_id, u.name, e.active,
                   COUNT(s.score) AS graded,
                   COALESCE(SUM(s.score), 0) AS earned,
                   COALESCE(SUM(a.max_score) FILTER (WHERE s.score IS NOT NULL), 0) AS possible
            FROM enrollment e
            JOIN course c ON c.id = e.course_id
            JOIN user_account u ON u.id = e.student_id
            LEFT JOIN course_module m ON m.course_id = c.id
            LEFT JOIN lesson l ON l.module_id = m.id
            LEFT JOIN assignment a ON a.lesson_id = l.id
            LEFT JOIN submission s ON s.assignment_id = a.id AND s.student_id = e.student_id
            WHERE %s
            GROUP BY c.id, c.title, e.student_id, u.name, e.active
            """;

    private final JdbcClient jdbc;

    public AnalyticsQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<CoursePerformance> student(long studentId) {
        return jdbc.sql(PERFORMANCE.formatted("e.student_id = :id") + " ORDER BY c.id")
                .param("id", studentId)
                .query((row, index) -> new CoursePerformance(row.getLong("course_id"), row.getString("title"),
                        row.getBoolean("active"), row.getLong("graded"), row.getLong("earned"), row.getLong("possible")))
                .list();
    }

    public List<RankingEntry> ranking(long courseId) {
        return jdbc.sql("""
                SELECT DENSE_RANK() OVER (ORDER BY ratio DESC NULLS LAST) AS rank,
                       student_id, name, graded, earned, possible
                FROM (SELECT p.*, CAST(earned AS numeric) / NULLIF(possible, 0) AS ratio
                      FROM (%s) p) ranked
                ORDER BY rank, student_id
                """.formatted(PERFORMANCE.formatted("e.course_id = :id AND e.active")))
                .param("id", courseId)
                .query((row, index) -> new RankingEntry(row.getLong("rank"), row.getLong("student_id"),
                        row.getString("name"), row.getLong("graded"), row.getLong("earned"), row.getLong("possible")))
                .list();
    }

    public QuizStatistics quiz(long quizId) {
        return jdbc.sql("""
                SELECT COUNT(*) AS attempts, COUNT(DISTINCT student_id) AS participants,
                       ROUND(AVG(percentage), 2) AS average_percentage,
                       ROUND(MIN(percentage), 2) AS min_percentage,
                       ROUND(MAX(percentage), 2) AS max_percentage
                FROM (SELECT student_id, 100.0 * score / NULLIF(total, 0) AS percentage
                      FROM quiz_submission WHERE quiz_id = :id) results
                """)
                .param("id", quizId)
                .query((row, index) -> new QuizStatistics(row.getLong("attempts"), row.getLong("participants"),
                        row.getBigDecimal("average_percentage"), row.getBigDecimal("min_percentage"),
                        row.getBigDecimal("max_percentage")))
                .single();
    }

    public record CoursePerformance(long courseId, String title, boolean active, long gradedAssignments,
                                    long earnedPoints, long possiblePoints) {}

    public record RankingEntry(long rank, long studentId, String name, long gradedAssignments,
                               long earnedPoints, long possiblePoints) {}

    public record QuizStatistics(long attempts, long participants, BigDecimal averagePercentage,
                                 BigDecimal minPercentage, BigDecimal maxPercentage) {}
}
