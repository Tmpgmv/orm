package ru.mephi.orm.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.mephi.orm.entity.UserAccount;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {
    Optional<UserAccount> findByEmail(String email);
    boolean existsByEmail(String email);
}
