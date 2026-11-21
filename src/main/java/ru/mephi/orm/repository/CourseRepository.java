package ru.mephi.orm.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.mephi.orm.entity.Course;

public interface CourseRepository extends JpaRepository<Course, Long> {

    Optional<Course> findByDemoKey(String demoKey);
}
