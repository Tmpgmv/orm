package ru.mephi.orm.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.mephi.orm.entity.Lesson;

public interface LessonRepository extends JpaRepository<Lesson, Long> {

}
