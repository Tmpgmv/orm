package ru.mephi.orm.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.mephi.orm.entity.Question;

public interface QuestionRepository extends JpaRepository<Question, Long> {

}
