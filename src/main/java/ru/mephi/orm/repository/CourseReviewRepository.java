package ru.mephi.orm.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.mephi.orm.entity.CourseReview;

public interface CourseReviewRepository extends JpaRepository<CourseReview, Long> {

    @Modifying
    @Query("delete from CourseReview item where item.course.id = :courseId")
    void deleteForCourse(@Param("courseId") long courseId);
}
