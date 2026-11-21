package ru.mephi.orm.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.mephi.orm.entity.Tag;

public interface TagRepository extends JpaRepository<Tag, Long> {
    Optional<Tag> findByName(String name);
}
