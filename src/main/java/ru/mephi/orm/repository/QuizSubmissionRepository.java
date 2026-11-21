package ru.mephi.orm.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.mephi.orm.entity.QuizSubmission;

public interface QuizSubmissionRepository extends JpaRepository<QuizSubmission, Long> {
    List<QuizSubmission> findByQuizIdAndStudentIdOrderById(long quizId, long studentId);
    List<QuizSubmission> findByQuizIdOrderById(long quizId);
}
