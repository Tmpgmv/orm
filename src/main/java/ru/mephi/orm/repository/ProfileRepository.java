package ru.mephi.orm.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.mephi.orm.entity.Profile;

public interface ProfileRepository extends JpaRepository<Profile, Long> {
    Optional<Profile> findByUserId(long userId);
}
