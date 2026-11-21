package ru.mephi.orm.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.mephi.orm.entity.Assignment;

public interface AssignmentRepository extends JpaRepository<Assignment, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Assignment a where a.id = :id")
    Optional<Assignment> findLockedById(@Param("id") long id);

    List<Assignment> findByLessonIdOrderById(long lessonId);
}
