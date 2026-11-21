package ru.mephi.orm.repository;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.mephi.orm.entity.Quiz;

public interface QuizRepository extends JpaRepository<Quiz, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from Quiz q where q.id = :id")
    Optional<Quiz> findLockedById(@Param("id") long id);

    List<Quiz> findByCourseIdOrderById(long courseId);
}
