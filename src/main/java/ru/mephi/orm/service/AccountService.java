package ru.mephi.orm.service;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.mephi.orm.entity.Profile;
import ru.mephi.orm.entity.Role;
import ru.mephi.orm.entity.UserAccount;
import ru.mephi.orm.repository.ProfileRepository;
import ru.mephi.orm.repository.UserAccountRepository;
import ru.mephi.orm.security.Actor;

@Service
@Transactional
public class AccountService {

    private final UserAccountRepository accounts;
    private final ProfileRepository profiles;
    private final PasswordEncoder passwords;

    public AccountService(UserAccountRepository accounts, ProfileRepository profiles, PasswordEncoder passwords) {
        this.accounts = accounts;
        this.profiles = profiles;
        this.passwords = passwords;
    }

    public Account create(Actor actor, String email, String name, String password, Role role) {
        actor.require(Role.ADMIN);
        var account = new UserAccount();
        account.setRole(role);
        change(account, email, name, password);
        return Account.from(accounts.save(account));
    }

    public Account update(Actor actor, long id, String email, String name, String password, Role role) {
        actor.require(Role.ADMIN);
        var account = ApiFailure.found(accounts.findById(id));
        if (account.getRole() != role) {
            throw ApiFailure.conflict("Account role is fixed after creation");
        }
        change(account, email, name, password);
        return Account.from(account);
    }

    @Transactional(readOnly = true)
    public Account get(Actor actor, long id) {
        actor.requireSelfOrAdmin(id);
        return Account.from(ApiFailure.found(accounts.findById(id)));
    }

    public void delete(Actor actor, long id) {
        actor.require(Role.ADMIN);
        var account = ApiFailure.found(accounts.findById(id));
        profiles.findByUserId(id).ifPresent(profiles::delete);
        accounts.delete(account);
    }

    @Transactional(readOnly = true)
    public ProfileView profile(Actor actor, long id) {
        actor.requireSelfOrAdmin(id);
        return ProfileView.from(ApiFailure.found(profiles.findByUserId(id)));
    }

    public ProfileView updateProfile(Actor actor, long id, String bio, String avatarUrl) {
        actor.requireSelfOrAdmin(id);
        var account = ApiFailure.found(accounts.findById(id));
        var profile = profiles.findByUserId(id).orElseGet(Profile::new);
        profile.setUser(account);
        profile.setBio(bio);
        profile.setAvatarUrl(avatarUrl);
        return ProfileView.from(profiles.save(profile));
    }

    public void deleteProfile(Actor actor, long id) {
        actor.requireSelfOrAdmin(id);
        profiles.delete(ApiFailure.found(profiles.findByUserId(id)));
    }

    private void change(UserAccount account, String email, String name, String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw ApiFailure.badInput("Password must be at most 72 UTF-8 bytes");
        }
        account.setEmail(email.strip().toLowerCase(Locale.ROOT));
        account.setName(name.strip());
        account.setPasswordHash(passwords.encode(password));
    }

    public record Account(long id, String email, String name, Role role) {
        static Account from(UserAccount account) {
            return new Account(account.getId(), account.getEmail(), account.getName(), account.getRole());
        }
    }

    public record ProfileView(long userId, String bio, String avatarUrl) {
        static ProfileView from(Profile profile) {
            return new ProfileView(profile.getUser().getId(), profile.getBio(), profile.getAvatarUrl());
        }
    }
}
