package ru.mephi.orm.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.mephi.orm.entity.AnswerOption;

public interface AnswerOptionRepository extends JpaRepository<AnswerOption, Long> {

}
