package ru.mephi.orm.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.mephi.orm.entity.Submission;

public interface SubmissionRepository extends JpaRepository<Submission, Long> {
    List<Submission> findByAssignmentIdOrderById(long assignmentId);
}
