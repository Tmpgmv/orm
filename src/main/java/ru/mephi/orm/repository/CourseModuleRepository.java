package ru.mephi.orm.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.mephi.orm.entity.CourseModule;

public interface CourseModuleRepository extends JpaRepository<CourseModule, Long> {

}
