package ru.mephi.orm.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.mephi.orm.entity.Enrollment;

public interface EnrollmentRepository extends JpaRepository<Enrollment, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Enrollment> findByStudentIdAndCourseId(long studentId, long courseId);

    boolean existsByStudentIdAndCourseIdAndActiveTrue(long studentId, long courseId);
    List<Enrollment> findByStudentIdAndActiveTrueOrderById(long studentId);
    List<Enrollment> findByCourseIdAndActiveTrueOrderById(long courseId);
    @Modifying
    @Query("delete from Enrollment item where item.course.id = :courseId")
    void deleteForCourse(@Param("courseId") long courseId);
}
