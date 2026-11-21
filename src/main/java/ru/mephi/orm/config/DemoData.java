package ru.mephi.orm.config;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.mephi.orm.entity.Assignment;
import ru.mephi.orm.entity.Category;
import ru.mephi.orm.entity.QuestionType;
import ru.mephi.orm.entity.Role;
import ru.mephi.orm.entity.UserAccount;
import ru.mephi.orm.repository.AssignmentRepository;
import ru.mephi.orm.repository.CategoryRepository;
import ru.mephi.orm.repository.CourseRepository;
import ru.mephi.orm.repository.LessonRepository;
import ru.mephi.orm.repository.UserAccountRepository;
import ru.mephi.orm.security.Actor;
import ru.mephi.orm.service.CourseService;
import ru.mephi.orm.service.EnrollmentService;
import ru.mephi.orm.service.QuizService;

@Component
@Profile("demo")
public class DemoData implements ApplicationRunner {

    private static final String COURSE_KEY = "hibernate-basics";

    private final UserAccountRepository users;
    private final CategoryRepository categories;
    private final CourseRepository courses;
    private final PasswordEncoder passwords;
    private final CourseService content;
    private final EnrollmentService enrollments;
    private final AssignmentRepository assignments;
    private final LessonRepository lessons;
    private final QuizService quizzes;
    private final String password;

    public DemoData(UserAccountRepository users, CategoryRepository categories, CourseRepository courses,
                    PasswordEncoder passwords, CourseService content, EnrollmentService enrollments,
                    AssignmentRepository assignments, LessonRepository lessons, QuizService quizzes,
                    @Value("${app.demo.password}") String password) {
        this.users = users;
        this.categories = categories;
        this.courses = courses;
        this.passwords = passwords;
        this.content = content;
        this.enrollments = enrollments;
        this.assignments = assignments;
        this.lessons = lessons;
        this.quizzes = quizzes;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        if (password.length() < 8 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("DEMO_PASSWORD must contain at least 8 characters and at most 72 bytes");
        }
        account("admin@example.test", "Администратор", Role.ADMIN);
        var teacher = account("teacher@example.test", "Преподаватель", Role.TEACHER);
        var student = account("student@example.test", "Студент", Role.STUDENT);
        if (courses.findByDemoKey(COURSE_KEY).isPresent()) {
            return;
        }
        var owner = new Actor(teacher.getId(), Role.TEACHER);
        var participant = new Actor(student.getId(), Role.STUDENT);
        long category = categories.findByName("Программирование").orElseGet(this::category).getId();
        long course = content.create(owner, new CourseService.CourseInput(
                "Основы Hibernate", "Практика JPA и Hibernate", category, null)).course().id();
        courses.findById(course).orElseThrow().setDemoKey(COURSE_KEY);
        long module = content.createModule(owner, course, new CourseService.ModuleInput("Связи", 0)).id();
        long lesson = content.createLesson(owner, course, module, new CourseService.LessonInput(
                "Ленивая загрузка", "Пример работы с ленивой коллекцией", null, 0, null)).id();
        long nextModule = content.createModule(owner, course, new CourseService.ModuleInput("Транзакции", 1)).id();
        content.createLesson(owner, course, nextModule, new CourseService.LessonInput(
                "Границы транзакции", "Управление контекстом персистентности", null, 0, null));
        content.addTag(owner, course, "Hibernate");
        enrollments.enroll(participant, course);
        var assignment = new Assignment();
        assignment.setLesson(lessons.getReferenceById(lesson));
        assignment.setTitle("Задание по JPA");
        assignment.setInstructions("Опишите границу транзакции");
        assignment.setMaxScore(100);
        assignments.save(assignment);
        quizzes.create(owner, course, new QuizService.QuizInput("Проверка знаний", List.of(
                new QuizService.QuestionInput("Когда доступна ленивая коллекция?", QuestionType.SINGLE_CHOICE,
                        List.of(new QuizService.OptionInput("В открытой сессии", true),
                                new QuizService.OptionInput("Только после завершения сессии", false))))));
    }

    private UserAccount account(String email, String name, Role role) {
        return users.findByEmail(email).orElseGet(() -> {
            var user = new UserAccount();
            user.setEmail(email);
            user.setName(name);
            user.setRole(role);
            user.setPasswordHash(passwords.encode(password));
            return users.save(user);
        });
    }

    private Category category() {
        var category = new Category();
        category.setName("Программирование");
        return categories.save(category);
    }
}
