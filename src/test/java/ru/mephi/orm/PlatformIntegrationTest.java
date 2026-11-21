package ru.mephi.orm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.hibernate.LazyInitializationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ru.mephi.orm.entity.Course;
import ru.mephi.orm.entity.CourseModule;
import ru.mephi.orm.entity.QuestionType;
import ru.mephi.orm.entity.Role;
import ru.mephi.orm.notification.EmailSender;
import ru.mephi.orm.repository.CourseRepository;
import ru.mephi.orm.repository.UserAccountRepository;
import ru.mephi.orm.security.Actor;
import ru.mephi.orm.security.AuthService;
import ru.mephi.orm.service.AccountService;
import ru.mephi.orm.service.ApiFailure;
import ru.mephi.orm.service.AssignmentService;
import ru.mephi.orm.service.CourseService;
import ru.mephi.orm.service.EnrollmentService;
import ru.mephi.orm.service.QuizService;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
class PlatformIntegrationTest {

    private static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:17");
    private static final String PASSWORD = "integration-fixture";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry properties) throws Exception {
        DATABASE.start();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keys = generator.generateKeyPair();
        properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
        properties.add("spring.datasource.username", DATABASE::getUsername);
        properties.add("spring.datasource.password", DATABASE::getPassword);
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        properties.add("app.jwt.private-key", () -> Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded()));
        properties.add("app.jwt.public-key", () -> Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()));
        properties.add("app.demo.password", () -> PASSWORD);
        properties.add("app.email.enabled", () -> false);
    }

    @LocalServerPort int port;
    @Autowired RecordingEmailSender mail;
    @Autowired AuthService auth;
    @Autowired AccountService accounts;
    @Autowired UserAccountRepository users;
    @Autowired CourseService content;
    @Autowired CourseRepository courses;
    @Autowired EnrollmentService enrollment;
    @Autowired AssignmentService assignments;
    @Autowired QuizService quizzes;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtEncoder jwtEncoder;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate transactions;

    /**
     * Описание: Проверяет, что при запуске приложения регистрируются все сущности и загружается демо-курс.
     *
     * Входные данные: Приложение запускается с профилем {@code demo} и отдельной базой PostgreSQL.
     * Ожидаемый результат: В метамодели JPA ровно 15 сущностей, а в списке курсов есть «Основы Hibernate».
     */
    @Test
    void mapsAllFifteenEntitiesAndSeedsDemo() {
        assertThat(entityManager.getMetamodel().getEntities()).hasSize(15);
        assertThat(content.list()).anyMatch(course -> course.title().equals("Основы Hibernate"));
    }

    /**
     * Описание: Проверяет, что документация API и вход доступны без токена.
     *
     * Входные данные: Без заголовка авторизации запрашиваем Swagger UI и OpenAPI, затем отправляем email и пароль демо-студента на {@code POST /api/auth/login}.
     * Ожидаемый результат: Swagger UI и OpenAPI отвечают HTTP 200, в спецификации есть схема bearerAuth и маршруты входа и сдачи теста, а в ответе на вход есть {@code accessToken}.
     */
    @Test
    void swaggerAndLoginAreReachableWithoutBearer() throws Exception {
        assertThat(request("GET", "/swagger-ui/index.html", null, null).statusCode()).isEqualTo(200);
        var api = request("GET", "/v3/api-docs", null, null);
        assertThat(api.statusCode()).isEqualTo(200);
        assertThat(api.body()).contains("bearerAuth", "/api/auth/login", "/api/quizzes/{id}/attempts");
        assertThat(request("POST", "/api/auth/login", null,
                """
                {"email":"student@example.test","password":"integration-fixture"}
                """).body()).contains("accessToken");
    }

    /**
     * Описание: Проверяет, что студент может войти с email в верхнем регистре и использовать выданный токен.
     *
     * Входные данные: Передаём в сервис входа email демо-студента в верхнем регистре и верный пароль, затем вызываем {@code GET /api/auth/me} с выданным токеном.
     * Ожидаемый результат: Токен действует 1800 секунд, эндпоинт отвечает HTTP 200 и возвращает роль STUDENT, а в базе хранится хеш, соответствующий паролю.
     */
    @Test
    void loginIssuesBearerForTheAuthenticatedAccount() throws Exception {
        var token = auth.login("STUDENT@example.test", PASSWORD);
        assertThat(token.expiresIn()).isEqualTo(1800);
        var response = request("GET", "/api/auth/me", token.accessToken(), null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("STUDENT");
        assertThat(users.findByEmail("student@example.test").orElseThrow().getPasswordHash())
                .isNotEqualTo(PASSWORD).satisfies(hash -> assertThat(passwords.matches(PASSWORD, hash)).isTrue());
    }

    /**
     * Описание: Проверяет, что защищённый эндпоинт отклоняет запросы без токена и с некорректным токеном.
     *
     * Входные данные: Вызываем {@code GET /api/auth/me} сначала без заголовка авторизации, затем с некорректным bearer-токеном.
     * Ожидаемый результат: Оба запроса получают HTTP 401, а для некорректного токена ответ имеет тип {@code application/problem+json}.
     */
    @Test
    void securityRejectsMissingOrInvalidTokens() throws Exception {
        assertThat(request("GET", "/api/auth/me", null, null).statusCode()).isEqualTo(401);
        var invalid = request("GET", "/api/auth/me", "bad.token.value", null);
        assertThat(invalid.statusCode()).isEqualTo(401);
        assertThat(invalid.headers().firstValue("Content-Type")).hasValueSatisfying(
                value -> assertThat(value).startsWith("application/problem+json"));
    }

    /**
     * Описание: Проверяет, что корректная подпись JWT не компенсирует ошибки в его содержимом.
     *
     * Входные данные: Вызываем {@code GET /api/auth/me} от имени демо-студента с четырьмя подписанными токенами: с неверным издателем, получателем, истёкшим сроком действия или некорректным идентификатором пользователя.
     * Ожидаемый результат: Каждый из четырёх запросов получает HTTP 401.
     */
    @Test
    void signedTokensWithInvalidClaimsAreRejected() throws Exception {
        var student = seeded(Role.STUDENT);
        String subject = Long.toString(student.id());
        Instant future = Instant.now().plusSeconds(1800);
        for (var token : List.of(
                token("wrong-issuer", "orm-course-api", subject, future),
                token("orm-course-platform", "wrong-audience", subject, future),
                token("orm-course-platform", "orm-course-api", subject, Instant.now().minusSeconds(120)),
                token("orm-course-platform", "orm-course-api", "invalid-id", future))) {
            assertThat(request("GET", "/api/auth/me", token, null).statusCode()).isEqualTo(401);
        }
    }

    /**
     * Описание: Проверяет отказ во входе при неверном пароле.
     *
     * Входные данные: Отправляем email существующего демо-студента и неверный пароль на {@code POST /api/auth/login}.
     * Ожидаемый результат: Сервер отвечает HTTP 401.
     */
    @Test
    void wrongPasswordReturnsUnauthorized() throws Exception {
        var response = request("POST", "/api/auth/login", null,
                """
                {"email":"student@example.test","password":"incorrect"}
                """);
        assertThat(response.statusCode()).isEqualTo(401);
    }

    /**
     * Описание: Проверяет работу с учётной записью студента и его профилем через сервисы.
     *
     * Входные данные: Администратор создаёт студента, студент сохраняет биографию, администратор читает профиль и обновляет учётную запись, затем студент удаляет профиль, а администратор удаляет учётную запись.
     * Ожидаемый результат: Администратор видит сохранённую биографию, студент видит обновлённое имя, чтение удалённого профиля завершается ошибкой, а учётной записи больше нет в базе.
     */
    @Test
    void administratorCanManageAccountAndProfile() {
        var admin = seeded(Role.ADMIN);
        var user = newStudent();
        accounts.updateProfile(user, user.id(), "Bio", null);
        assertThat(accounts.profile(admin, user.id()).bio()).isEqualTo("Bio");
        accounts.update(admin, user.id(), email(), "Updated", PASSWORD, Role.STUDENT);
        assertThat(accounts.get(user, user.id()).name()).isEqualTo("Updated");
        accounts.deleteProfile(user, user.id());
        assertThatThrownBy(() -> accounts.profile(admin, user.id())).isInstanceOf(ApiFailure.class);
        accounts.delete(admin, user.id());
        assertThat(users.existsById(user.id())).isFalse();
    }

    /**
     * Описание: Проверяет, что токен удалённого пользователя больше не даёт доступа к API.
     *
     * Входные данные: Создаём студента, получаем токен через сервис входа, удаляем учётную запись от имени администратора и вызываем {@code GET /api/auth/me} со старым токеном.
     * Ожидаемый результат: Сервер отвечает HTTP 401.
     */
    @Test
    void deletedAccountCannotReuseBearerToken() throws Exception {
        var actor = newStudent();
        var account = accounts.get(actor, actor.id());
        var token = auth.login(account.email(), PASSWORD);
        accounts.delete(seeded(Role.ADMIN), actor.id());
        assertThat(request("GET", "/api/auth/me", token.accessToken(), null).statusCode()).isEqualTo(401);
    }

    /**
     * Описание: Проверяет, что преподаватель не может прочитать чужой профиль.
     *
     * Входные данные: Студент сохраняет биографию в своём профиле, затем преподаватель запрашивает этот профиль через сервис.
     * Ожидаемый результат: Сервис отклоняет чтение с ошибкой, соответствующей HTTP 403.
     */
    @Test
    void privateProfileRequiresOwnerOrAdmin() {
        var student = newStudent();
        accounts.updateProfile(student, student.id(), "Private", null);
        expectFailure(HttpStatus.FORBIDDEN, () -> accounts.profile(seeded(Role.TEACHER), student.id()));
    }

    /**
     * Описание: Проверяет обновление и удаление курса, а также проверку принадлежности модуля курсу.
     *
     * Входные данные: Преподаватель меняет название своего курса и пытается обновить модуль другого курса, указав идентификатор первого. Затем записываем студента и удаляем первый курс.
     * Ожидаемый результат: Новое название сохраняется, попытка изменить модуль другого курса возвращает ошибку со статусом 404, а после удаления курс исчезает из базы и из списка записей студента.
     */
    @Test
    void courseCrudAndNestedOwnership() {
        var fixture = fixture();
        content.update(fixture.teacher(), fixture.course(), new CourseService.CourseInput(
                "Updated title", null, fixture.category(), null));
        assertThat(content.get(fixture.course()).course().title()).isEqualTo("Updated title");
        var other = fixture();
        expectFailure(HttpStatus.NOT_FOUND, () -> content.updateModule(fixture.teacher(), fixture.course(),
                other.module(), new CourseService.ModuleInput("Other", 0)));
        enrollment.enroll(fixture.student(), fixture.course());
        content.delete(fixture.teacher(), fixture.course());
        assertThat(enrollment.mine(fixture.student())).isEmpty();
        assertThat(courses.existsById(fixture.course())).isFalse();
    }

    /**
     * Описание: Проверяет сохранение расписания и обработку неверного формата даты.
     *
     * Входные данные: Преподаватель сохраняет дату начала курса и время занятия через сервис, затем отправляет {@code PUT /api/courses/{id}} со значением {@code invalid} в поле {@code startDate}.
     * Ожидаемый результат: При чтении курса возвращаются сохранённая дата начала и время занятия, а запрос с неверной датой получает HTTP 400.
     */
    @Test
    void scheduleIsPersistedAndMalformedDateIsRejected() throws Exception {
        var fixture = fixture();
        var date = java.time.LocalDate.of(2030, 4, 10);
        var scheduled = Instant.parse("2030-04-11T14:00:00Z");
        content.update(fixture.teacher(), fixture.course(), new CourseService.CourseInput(
                "Scheduled", null, fixture.category(), date));
        content.updateLesson(fixture.teacher(), fixture.course(), fixture.module(), fixture.lesson(),
                new CourseService.LessonInput("Scheduled lesson", null, null, 0, scheduled));
        var view = content.get(fixture.course());
        assertThat(view.course().startDate()).isEqualTo(date);
        assertThat(view.modules().getFirst().lessons().getFirst().scheduledAt()).isEqualTo(scheduled);
        String token = auth.login("teacher@example.test", PASSWORD).accessToken();
        var response = request("PUT", "/api/courses/" + fixture.course(), token, """
                {"title":"Invalid date","categoryId":%d,"startDate":"invalid"}
                """.formatted(fixture.category()));
        assertThat(response.statusCode()).isEqualTo(400);
    }

    /**
     * Описание: Проверяет каскадное сохранение модуля вместе с курсом.
     *
     * Входные данные: В транзакции загружаем курс, добавляем новый модуль в его коллекцию, выставляем обратную ссылку на курс и вызываем {@code flush} без отдельного сохранения модуля.
     * Ожидаемый результат: При следующем чтении курса через сервис новый модуль присутствует в списке.
     */
    @Test
    void cascadePersistsOwnedModule() {
        var fixture = fixture();
        transactions.executeWithoutResult(status -> {
            var course = courses.findById(fixture.course()).orElseThrow();
            var extra = new CourseModule();
            extra.setTitle("Cascade example");
            extra.setPosition(2);
            extra.setCourse(course);
            course.getModules().add(extra);
            courses.flush();
        });
        assertThat(content.get(fixture.course()).modules()).extracting(CourseService.ModuleView::title)
                .contains("Cascade example");
    }

    /**
     * Описание: Проверяет работу ленивой коллекции за пределами транзакции и загрузку модулей через DTO.
     *
     * Входные данные: Сохраняем курс с одним модулем, загружаем сущность курса в транзакции и после её завершения
     * обращаемся к коллекции. Отдельно читаем тот же курс через сервис.
     * Ожидаемый результат: При обращении к незагруженной коллекции возникает
     * {@link LazyInitializationException}, а DTO содержит один модуль.
     */
    @Test
    void detachedLazyAccessFailsButDtoWorks() {
        var fixture = fixture();
        Course detached = transactions.execute(status -> courses.
                findById(fixture.course()).orElseThrow());
        assertThatThrownBy(() -> detached.getModules().size()).isInstanceOf(LazyInitializationException.class);
        assertThat(content.get(fixture.course()).modules()).hasSize(1);
    }

    /**
     * Описание: Проверяет, что удаление тега с курса не затрагивает другой курс с тем же тегом.
     *
     * Входные данные: Преподаватель добавляет тег с одним и тем же названием к двум курсам, затем убирает его с первого курса.
     * Ожидаемый результат: Оба курса изначально ссылаются на тег с одним идентификатором. После удаления у первого курса тегов нет, у второго остаётся один.
     */
    @Test
    void tagRemovalPreservesSharedTag() {
        var first = fixture();
        var second = fixture();
        String name = UUID.randomUUID().toString();
        long tag = content.addTag(first.teacher(), first.course(), name).tags().getFirst().id();
        assertThat(content.addTag(second.teacher(), second.course(), name).tags().getFirst().id()).isEqualTo(tag);
        content.removeTag(first.teacher(), first.course(), tag);
        assertThat(content.get(first.course()).tags()).isEmpty();
        assertThat(content.get(second.course()).tags()).hasSize(1);
    }

    /**
     * Описание: Проверяет, что после отписки повторная запись использует ту же запись о зачислении.
     *
     * Входные данные: Студент записывается на курс, отписывается и записывается снова. Между операциями читаем список курсов студента и список студентов у преподавателя.
     * Ожидаемый результат: После первой записи преподаватель видит одного студента, после отписки оба списка пусты, а повторная запись возвращает прежний идентификатор.
     */
    @Test
    void withdrawAndRejoinUsesSameEnrollment() {
        var fixture = fixture();
        var member = enrollment.enroll(fixture.student(), fixture.course());
        assertThat(enrollment.roster(fixture.teacher(), fixture.course())).hasSize(1);
        enrollment.withdraw(fixture.student(), fixture.course());
        assertThat(enrollment.mine(fixture.student())).isEmpty();
        assertThat(enrollment.roster(fixture.teacher(), fixture.course())).isEmpty();
        assertThat(enrollment.enroll(fixture.student(), fixture.course()).id()).isEqualTo(member.id());
    }

    /**
     * Описание: Проверяет защиту от двойной записи на курс при одновременных запросах.
     *
     * Входные данные: С одним токеном студента одновременно отправляем два запроса {@code POST /api/courses/{courseId}/enrollments} для одного курса.
     * Ожидаемый результат: Один запрос получает HTTP 201, второй — HTTP 409, а преподаватель видит в списке ровно одну запись.
     */
    @Test
    void concurrentEnrollmentPersistsOneMembership() throws Exception {
        var fixture = fixture();
        String email = users.findById(fixture.student().id()).orElseThrow().getEmail();
        String token = auth.login(email, PASSWORD).accessToken();
        CountDownLatch start = new CountDownLatch(1);
        String path = "/api/courses/" + fixture.course() + "/enrollments";
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return request("POST", path, token, null).statusCode(); });
            var second = executor.submit(() -> { start.await(); return request("POST", path, token, null).statusCode(); });
            start.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(201, 409);
        }
        assertThat(enrollment.roster(fixture.teacher(), fixture.course())).hasSize(1);
    }

    /**
     * Описание: Проверяет, что оценённое решение остаётся доступным после отписки и защищает курс от удаления.
     *
     * Входные данные: Записанный на курс студент сдаёт решение, преподаватель выставляет 90 баллов, студент отписывается, а преподаватель пытается удалить курс.
     * Ожидаемый результат: Студент по-прежнему видит оценку 90, а удаление курса завершается ошибкой нарушения целостности данных.
     */
    @Test
    void studentSubmitsTeacherGradesAndWithdrawalPreservesWork() {
        var fixture = fixture();
        enrollment.enroll(fixture.student(), fixture.course());
        long assignment = assignment(fixture, null).id();
        var submission = assignments.submit(fixture.student(), assignment, "ORM solution");
        assertThat(assignments.grade(fixture.teacher(), submission.id(), 90).score()).isEqualTo(90);
        enrollment.withdraw(fixture.student(), fixture.course());
        assertThat(assignments.submission(fixture.student(), submission.id()).score()).isEqualTo(90);
        assertThatThrownBy(() -> content.delete(fixture.teacher(), fixture.course()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    /**
     * Описание: Проверяет запрет на сдачу после дедлайна и на повторную сдачу одного задания.
     *
     * Входные данные: Записанный студент пытается сдать просроченное задание и дважды отправляет разные решения на другое задание без дедлайна.
     * Ожидаемый результат: Просроченная сдача отклоняется с ошибкой, соответствующей HTTP 409. Первая отправка задания без дедлайна проходит, а вторая нарушает ограничение уникальности в базе.
     */
    @Test
    void submissionCannotPassDeadlineOrBeDuplicated() {
        var fixture = fixture();
        enrollment.enroll(fixture.student(), fixture.course());
        long closed = assignment(fixture, Instant.now().minusSeconds(60)).id();
        expectFailure(HttpStatus.CONFLICT, () -> assignments.submit(fixture.student(), closed, "Late"));
        long open = assignment(fixture, null).id();
        assignments.submit(fixture.student(), open, "First");
        assertThatThrownBy(() -> assignments.submit(fixture.student(), open, "Second"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    /**
     * Описание: Проверяет, что студент не может выставить оценку своему решению через REST API.
     *
     * Входные данные: Студент сдаёт решение и со своим токеном отправляет {@code PUT /api/submissions/{id}/grade} с оценкой 10.
     * Ожидаемый результат: Сервер отвечает HTTP 403, поле оценки у решения остаётся пустым.
     */
    @Test
    void studentCannotGradeThroughRest() throws Exception {
        var fixture = fixture();
        enrollment.enroll(fixture.student(), fixture.course());
        long id = assignments.submit(fixture.student(), assignment(fixture, null).id(), "Work").id();
        String email = users.findById(fixture.student().id()).orElseThrow().getEmail();
        String token = auth.login(email, PASSWORD).accessToken();
        assertThat(request("PUT", "/api/submissions/" + id + "/grade", token, "{\"score\":10}").statusCode())
                .isEqualTo(403);
        assertThat(assignments.submission(fixture.student(), id).score()).isNull();
    }

    /**
     * Описание: Проверяет подсчёт баллов за точное совпадение ответов и сохранение повторных попыток.
     *
     * Входные данные: Преподаватель создаёт вопрос с двумя правильными вариантами. Записанный студент открывает тест, отправляет оба правильных варианта, затем только один и отписывается от курса.
     * Ожидаемый результат: Студенческий DTO скрывает признаки правильности. Попытки получают разные идентификаторы, а после отписки студент видит обе оценки в порядке сдачи: 1 и 0.
     */
    @Test
    void quizScoresExactMatchesAndRetainsRepeatAttempts() {
        var fixture = fixture();
        enrollment.enroll(fixture.student(), fixture.course());
        var quiz = quiz(fixture);
        var hidden = quizzes.read(fixture.student(), quiz.id());
        assertThat(hidden.questions().getFirst().options()).allSatisfy(option -> assertThat(option.correct()).isNull());
        var question = quiz.questions().getFirst();
        var correct = Set.of(question.options().get(0).id(), question.options().get(1).id());
        long id = quizzes.submit(fixture.student(), quiz.id(), Map.of(question.id(), correct)).id();
        var second = quizzes.submit(fixture.student(), quiz.id(), Map.of(question.id(), Set.of(question.options().getFirst().id())));
        assertThat(second.id()).isNotEqualTo(id);
        assertThat(second.score()).isZero();
        enrollment.withdraw(fixture.student(), fixture.course());
        assertThat(quizzes.results(fixture.student(), quiz.id())).extracting(QuizService.Result::score)
                .containsExactly(1, 0);
    }

    /**
     * Описание: Проверяет валидацию вариантов ответа и защиту сохранённых результатов теста.
     *
     * Входные данные: Записанный студент сначала передаёт неизвестный идентификатор варианта, затем сдаёт тест с существующим вариантом. Другой студент запрашивает результат, а преподаватель пытается удалить тест.
     * Ожидаемый результат: Сервис отклоняет неизвестный вариант со статусом 400 и чтение чужого результата со статусом 403. Удаление теста с сохранённым результатом нарушает ограничение целостности данных.
     */
    @Test
    void quizRejectsUnknownAnswersAndPrivateResults() {
        var fixture = fixture();
        enrollment.enroll(fixture.student(), fixture.course());
        var quiz = quiz(fixture);
        var question = quiz.questions().getFirst();
        expectFailure(HttpStatus.BAD_REQUEST, () -> quizzes.submit(fixture.student(), quiz.id(),
                Map.of(question.id(), Set.of(Long.MAX_VALUE))));
        var result = quizzes.submit(fixture.student(), quiz.id(),
                Map.of(question.id(), Set.of(question.options().getFirst().id())));
        expectFailure(HttpStatus.FORBIDDEN, () -> quizzes.result(newStudent(), result.id()));
        assertThatThrownBy(() -> quizzes.delete(fixture.teacher(), quiz.id()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    /**
     * Описание: Проверяет валидацию тела запроса на вход.
     *
     * Входные данные: Отправляем некорректный email и пустой пароль без токена.
     * Ожидаемый результат: Сервер отвечает HTTP 400 и указывает оба поля в ответе Problem Details.
     */
    @Test
    void invalidLoginBodyReturnsValidationDetails() throws Exception {
        var response = request("POST", "/api/auth/login", null, """
                {"email":"invalid","password":""}
                """);
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("Request validation failed", "email", "password");
    }

    /**
     * Описание: Проверяет уникальность email без учёта регистра.
     *
     * Входные данные: Администратор пытается создать вторую учётную запись с email существующего студента в верхнем регистре.
     * Ожидаемый результат: REST API отвечает HTTP 409, имя существующего студента остаётся прежним.
     */
    @Test
    void accountCreationRejectsDuplicateEmail() throws Exception {
        var student = accounts.get(seeded(Role.ADMIN), newStudent().id());
        var response = request("POST", "/api/users", bearer(seeded(Role.ADMIN)), """
                {"email":"%s","name":"Duplicate","password":"valid-password","role":"STUDENT"}
                """.formatted(student.email().toUpperCase(java.util.Locale.ROOT)));
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(accounts.get(seeded(Role.ADMIN), student.id()).name()).isEqualTo(student.name());
    }

    /**
     * Описание: Проверяет, что создание пользователей доступно администратору.
     *
     * Входные данные: Студент отправляет корректные данные нового пользователя на POST /api/users.
     * Ожидаемый результат: API отвечает HTTP 403, новый email отсутствует в базе.
     */
    @Test
    void studentCannotCreateAccount() throws Exception {
        String address = email();
        var response = request("POST", "/api/users", bearer(seeded(Role.STUDENT)), """
                {"email":"%s","name":"Unauthorized","password":"valid-password","role":"STUDENT"}
                """.formatted(address));
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(users.findByEmail(address)).isEmpty();
    }

    /**
     * Описание: Проверяет ответ для ещё не созданного профиля.
     *
     * Входные данные: Новый студент читает свой профиль через REST API.
     * Ожидаемый результат: Сервер отвечает HTTP 404.
     */
    @Test
    void missingProfileReturns404() throws Exception {
        var student = newStudent();
        assertThat(request("GET", "/api/users/" + student.id() + "/profile", bearer(student), null)
                .statusCode()).isEqualTo(404);
    }

    /**
     * Описание: Проверяет обновление профиля повторным PUT.
     *
     * Входные данные: Студент дважды сохраняет свой профиль с разными биографиями.
     * Ожидаемый результат: После второго запроса сервис возвращает новую биографию.
     */
    @Test
    void profilePutUpdatesExistingProfile() throws Exception {
        var student = newStudent();
        String path = "/api/users/" + student.id() + "/profile";
        String token = bearer(student);
        assertThat(request("PUT", path, token, "{\"bio\":\"First\"}").statusCode()).isEqualTo(200);
        assertThat(request("PUT", path, token, "{\"bio\":\"Second\"}").statusCode()).isEqualTo(200);
        assertThat(accounts.profile(student, student.id()).bio()).isEqualTo("Second");
    }

    /**
     * Описание: Проверяет создание, переименование и удаление категории.
     *
     * Входные данные: Администратор создаёт категорию с уникальным названием, переименовывает и удаляет её.
     * Ожидаемый результат: Запросы отвечают HTTP 201, 200 и 204, а категория исчезает из списка.
     */
    @Test
    void categoryCrudThroughRest() throws Exception {
        String name = "Category " + UUID.randomUUID();
        String token = bearer(seeded(Role.ADMIN));
        assertThat(request("POST", "/api/categories", token, """
                {"name":"%s"}
                """.formatted(name)).statusCode()).isEqualTo(201);
        long id = content.categories().stream().filter(item -> item.name().equals(name)).findFirst().orElseThrow().id();
        String path = "/api/categories/" + id;
        assertThat(request("PUT", path, token, "{\"name\":\"Renamed " + name + "\"}").statusCode()).isEqualTo(200);
        assertThat(content.categories()).anyMatch(item -> item.id() == id && item.name().equals("Renamed " + name));
        assertThat(request("DELETE", path, token, null).statusCode()).isEqualTo(204);
        assertThat(content.categories()).noneMatch(item -> item.id() == id);
    }

    /**
     * Описание: Проверяет защиту категории, используемой курсом.
     *
     * Входные данные: Администратор удаляет категорию существующего курса через REST API.
     * Ожидаемый результат: Сервер отвечает HTTP 409, курс по-прежнему ссылается на свою категорию.
     */
    @Test
    void categoryReferencedByCourseCannotBeDeleted() throws Exception {
        var fixture = fixture();
        assertThat(request("DELETE", "/api/categories/" + fixture.category(), bearer(seeded(Role.ADMIN)), null)
                .statusCode()).isEqualTo(409);
        assertThat(content.get(fixture.course()).course().category().id()).isEqualTo(fixture.category());
    }

    /**
     * Описание: Проверяет чтение несуществующего курса.
     *
     * Входные данные: Авторизованный студент запрашивает курс с отсутствующим идентификатором.
     * Ожидаемый результат: Сервер отвечает HTTP 404.
     */
    @Test
    void unknownCourseReturns404() throws Exception {
        assertThat(request("GET", "/api/courses/" + Long.MAX_VALUE, bearer(seeded(Role.STUDENT)), null)
                .statusCode()).isEqualTo(404);
    }

    /**
     * Описание: Проверяет, что роль преподавателя сама по себе не даёт права редактировать чужой курс.
     *
     * Входные данные: Второй преподаватель пытается изменить название курса первого преподавателя.
     * Ожидаемый результат: Сервер отвечает HTTP 403, название курса остаётся прежним.
     */
    @Test
    void foreignTeacherCannotUpdateCourse() throws Exception {
        var fixture = fixture();
        String title = content.get(fixture.course()).course().title();
        assertThat(request("PUT", "/api/courses/" + fixture.course(), bearer(newTeacher()), """
                {"title":"Changed","categoryId":%d}
                """.formatted(fixture.category())).statusCode()).isEqualTo(403);
        assertThat(content.get(fixture.course()).course().title()).isEqualTo(title);
    }

    /**
     * Описание: Проверяет сортировку модулей и уроков при чтении курса.
     *
     * Входные данные: Преподаватель создаёт модули и уроки в порядке, отличном от их позиций.
     * Ожидаемый результат: Сервис возвращает модули и уроки по возрастанию позиции.
     */
    @Test
    void courseDetailReturnsModulesAndLessonsInPositionOrder() throws Exception {
        var fixture = fixture();
        var late = content.createModule(fixture.teacher(), fixture.course(), new CourseService.ModuleInput("Late", 5));
        var early = content.createModule(fixture.teacher(), fixture.course(), new CourseService.ModuleInput("Early", 2));
        content.createLesson(fixture.teacher(), fixture.course(), early.id(),
                new CourseService.LessonInput("Later lesson", null, null, 9, null));
        content.createLesson(fixture.teacher(), fixture.course(), early.id(),
                new CourseService.LessonInput("Earlier lesson", null, null, 1, null));
        var modules = content.get(fixture.course()).modules();
        assertThat(modules).extracting(CourseService.ModuleView::id).containsExactly(fixture.module(), early.id(), late.id());
        assertThat(modules.get(1).lessons()).extracting(CourseService.LessonView::title)
                .containsExactly("Earlier lesson", "Later lesson");
    }

    /**
     * Описание: Проверяет принадлежность урока модулю при обновлении.
     *
     * Входные данные: Преподаватель указывает в пути соседний модуль того же курса и ID существующего урока.
     * Ожидаемый результат: API отвечает HTTP 404, исходный урок сохраняет название.
     */
    @Test
    void lessonUpdateRejectsWrongModule() throws Exception {
        var fixture = fixture();
        var other = content.createModule(fixture.teacher(), fixture.course(), new CourseService.ModuleInput("Other", 1));
        String path = "/api/courses/" + fixture.course() + "/modules/" + other.id() + "/lessons/" + fixture.lesson();
        assertThat(request("PUT", path, bearer(fixture.teacher()), "{\"title\":\"Changed\",\"position\":0}")
                .statusCode()).isEqualTo(404);
        assertThat(content.get(fixture.course()).modules().getFirst().lessons().getFirst().title()).isEqualTo("Lesson");
    }

    /**
     * Описание: Проверяет удаление зависимых уроков и заданий без решений.
     *
     * Входные данные: Преподаватель создаёт задание и удаляет модуль, в котором находится его урок.
     * Ожидаемый результат: API отвечает HTTP 204, в курсе больше нет модуля, а задание недоступно.
     */
    @Test
    void moduleDeletionRemovesLessonAndUnsubmittedAssignment() throws Exception {
        var fixture = fixture();
        long assignmentId = assignment(fixture, null).id();
        String path = "/api/courses/" + fixture.course() + "/modules/" + fixture.module();
        assertThat(request("DELETE", path, bearer(fixture.teacher()), null).statusCode()).isEqualTo(204);
        assertThat(content.get(fixture.course()).modules()).isEmpty();
        expectFailure(HttpStatus.NOT_FOUND, () -> assignments.read(fixture.teacher(), assignmentId));
    }

    /**
     * Описание: Проверяет ограничение роли при записи на курс.
     *
     * Входные данные: Преподаватель отправляет запрос на запись в свой курс.
     * Ожидаемый результат: API отвечает HTTP 403, список студентов курса остаётся пустым.
     */
    @Test
    void teacherCannotEnrollAsStudent() throws Exception {
        var fixture = fixture();
        assertThat(request("POST", "/api/courses/" + fixture.course() + "/enrollments", bearer(fixture.teacher()), null)
                .statusCode()).isEqualTo(403);
        assertThat(enrollment.roster(fixture.teacher(), fixture.course())).isEmpty();
    }

    /**
     * Описание: Проверяет доступ к списку записанных студентов.
     *
     * Входные данные: Студент записывается на курс, затем другой преподаватель запрашивает список участников.
     * Ожидаемый результат: API отвечает HTTP 403, владелец курса по-прежнему видит запись студента.
     */
    @Test
    void foreignTeacherCannotReadRoster() throws Exception {
        var fixture = fixture();
        enrollment.enroll(fixture.student(), fixture.course());
        assertThat(request("GET", "/api/courses/" + fixture.course() + "/enrollments", bearer(newTeacher()), null)
                .statusCode()).isEqualTo(403);
        assertThat(enrollment.roster(fixture.teacher(), fixture.course())).hasSize(1);
    }

    /**
     * Описание: Проверяет доступ к заданию до записи, во время обучения и после отписки.
     *
     * Входные данные: Студент запрашивает задание без записи, затем записывается, читает задание и отписывается.
     * Ожидаемый результат: Запросы получают HTTP 403, 200 и 403 соответственно.
     */
    @Test
    void assignmentAccessEndsAfterWithdrawal() throws Exception {
        var fixture = fixture();
        String path = "/api/assignments/" + assignment(fixture, null).id();
        String token = bearer(fixture.student());
        assertThat(request("GET", path, token, null).statusCode()).isEqualTo(403);
        enrollment.enroll(fixture.student(), fixture.course());
        assertThat(request("GET", path, token, null).statusCode()).isEqualTo(200);
        enrollment.withdraw(fixture.student(), fixture.course());
        assertThat(request("GET", path, token, null).statusCode()).isEqualTo(403);
    }

    /**
     * Описание: Проверяет, что максимальная оценка задания должна быть положительной.
     *
     * Входные данные: Преподаватель отправляет корректное название и описание задания с maxScore равным нулю.
     * Ожидаемый результат: API отвечает HTTP 400, у урока не появляется задание.
     */
    @Test
    void invalidAssignmentMaximumReturns400() throws Exception {
        var fixture = fixture();
        assertThat(request("POST", "/api/lessons/" + fixture.lesson() + "/assignments", bearer(fixture.teacher()), """
                {"title":"Invalid","instructions":"Instructions","maxScore":0}
                """).statusCode()).isEqualTo(400);
        assertThat(assignments.list(fixture.teacher(), fixture.lesson())).isEmpty();
    }

    /**
     * Описание: Проверяет сохранение прежней оценки при попытке выставить балл выше максимума.
     *
     * Входные данные: Преподаватель ставит 50 баллов за работу, затем отправляет через REST оценку 101 при максимуме 100.
     * Ожидаемый результат: API отвечает HTTP 400, у решения остаётся 50 баллов.
     */
    @Test
    void gradeAboveMaximumLeavesExistingGrade() throws Exception {
        var fixture = fixture();
        enrollment.enroll(fixture.student(), fixture.course());
        long id = assignments.submit(fixture.student(), assignment(fixture, null).id(), "Work").id();
        assignments.grade(fixture.teacher(), id, 50);
        assertThat(request("PUT", "/api/submissions/" + id + "/grade", bearer(fixture.teacher()),
                "{\"score\":101}").statusCode()).isEqualTo(400);
        assertThat(assignments.submission(fixture.student(), id).score()).isEqualTo(50);
    }

    /**
     * Описание: Проверяет защиту уже выставленных оценок при изменении задания.
     *
     * Входные данные: Преподаватель выставляет 90 баллов и пытается снизить максимум задания со 100 до 80.
     * Ожидаемый результат: Сервис отклоняет изменение со статусом 409, максимум задания остаётся равным 100.
     */
    @Test
    void maximumCannotBeReducedBelowExistingGrade() throws Exception {
        var fixture = fixture();
        enrollment.enroll(fixture.student(), fixture.course());
        long assignmentId = assignment(fixture, null).id();
        long submissionId = assignments.submit(fixture.student(), assignmentId, "Work").id();
        assignments.grade(fixture.teacher(), submissionId, 90);
        expectFailure(HttpStatus.CONFLICT, () -> assignments.update(fixture.teacher(), assignmentId,
                new AssignmentService.AssignmentInput("Updated", "Instructions", null, 80)));
        assertThat(assignments.read(fixture.teacher(), assignmentId).maxScore()).isEqualTo(100);
    }

    /**
     * Описание: Проверяет приватность решения студента.
     *
     * Входные данные: После сдачи задания другой студент запрашивает решение по ID через REST API.
     * Ожидаемый результат: Сервер отвечает HTTP 403, автор и преподаватель могут прочитать решение.
     */
    @Test
    void anotherStudentCannotReadSubmission() throws Exception {
        var fixture = fixture();
        enrollment.enroll(fixture.student(), fixture.course());
        long id = assignments.submit(fixture.student(), assignment(fixture, null).id(), "Private work").id();
        String path = "/api/submissions/" + id;
        assertThat(request("GET", path, bearer(newStudent()), null).statusCode()).isEqualTo(403);
        assertThat(request("GET", path, bearer(fixture.student()), null).body()).contains("Private work");
        assertThat(request("GET", path, bearer(fixture.teacher()), null).statusCode()).isEqualTo(200);
    }

    /**
     * Описание: Проверяет HTTP-ответ на повторную сдачу задания.
     *
     * Входные данные: Студент дважды отправляет разные решения одного и того же задания через REST API.
     * Ожидаемый результат: Первый запрос получает HTTP 201, второй — HTTP 409. У преподавателя отображается только первое решение.
     */
    @Test
    void duplicateSubmissionReturns409ThroughRest() throws Exception {
        var fixture = fixture();
        enrollment.enroll(fixture.student(), fixture.course());
        long id = assignment(fixture, null).id();
        String path = "/api/assignments/" + id + "/submissions";
        String token = bearer(fixture.student());
        assertThat(request("POST", path, token, "{\"work\":\"First\"}").statusCode()).isEqualTo(201);
        assertThat(request("POST", path, token, "{\"work\":\"Second\"}").statusCode()).isEqualTo(409);
        assertThat(assignments.submissions(fixture.teacher(), id))
                .extracting(AssignmentService.SubmissionView::work).containsExactly("First");
    }

    /**
     * Описание: Проверяет правила создания вопроса с одним правильным ответом.
     *
     * Входные данные: Преподаватель отправляет вопрос SINGLE_CHOICE с двумя вариантами, оба отмечены правильными.
     * Ожидаемый результат: API отвечает HTTP 400, в списке тестов курса новая запись не появляется.
     */
    @Test
    void quizWithMultipleCorrectSingleChoiceOptionsIsRejected() throws Exception {
        var fixture = fixture();
        var response = request("POST", "/api/courses/" + fixture.course() + "/quizzes", bearer(fixture.teacher()), """
                {"title":"Invalid","questions":[{"prompt":"Choose","type":"SINGLE_CHOICE","options":[
                {"text":"One","correct":true},{"text":"Two","correct":true}]}]}
                """);
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(quizzes.list(fixture.teacher(), fixture.course())).isEmpty();
    }

    /**
     * Описание: Проверяет обновление и удаление теста без сохранённых попыток.
     *
     * Входные данные: Преподаватель создаёт тест, меняет название и варианты ответа, затем удаляет тест через REST API.
     * Ожидаемый результат: Сервис возвращает обновлённый тест. Удаление получает HTTP 204, последующее чтение возвращает HTTP 404.
     */
    @Test
    void quizUpdateAndDeletionWithoutResults() throws Exception {
        var fixture = fixture();
        long id = quiz(fixture).id();
        String path = "/api/quizzes/" + id;
        String token = bearer(fixture.teacher());
        var response = request("PUT", path, token, """
                {"title":"Revised quiz","questions":[{"prompt":"Revised question","type":"SINGLE_CHOICE","options":[
                {"text":"Yes","correct":true},{"text":"No","correct":false}]}]}
                """);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(quizzes.read(fixture.teacher(), id).questions().getFirst().prompt()).isEqualTo("Revised question");
        assertThat(request("DELETE", path, token, null).statusCode()).isEqualTo(204);
        assertThat(request("GET", path, token, null).statusCode()).isEqualTo(404);
    }

    /**
     * Описание: Проверяет отказ при неполном наборе ответов на тест.
     *
     * Входные данные: Преподаватель создаёт тест с двумя вопросами, студент отправляет вариант только для первого.
     * Ожидаемый результат: Сервис отклоняет попытку со статусом 400. В базе не появляется результат.
     */
    @Test
    void quizRequiresAnAnswerForEveryQuestion() throws Exception {
        var fixture = fixture();
        enrollment.enroll(fixture.student(), fixture.course());
        var options = List.of(new QuizService.OptionInput("Yes", true), new QuizService.OptionInput("No", false));
        var quiz = quizzes.create(fixture.teacher(), fixture.course(), new QuizService.QuizInput("Two questions", List.of(
                new QuizService.QuestionInput("First", QuestionType.SINGLE_CHOICE, options),
                new QuizService.QuestionInput("Second", QuestionType.SINGLE_CHOICE, options))));
        var first = quiz.questions().getFirst();
        expectFailure(HttpStatus.BAD_REQUEST, () -> quizzes.submit(fixture.student(), quiz.id(),
                Map.of(first.id(), Set.of(first.options().getFirst().id()))));
        assertThat(quizzes.results(fixture.teacher(), quiz.id())).isEmpty();
    }

    /**
     * Описание: Проверяет фильтрацию списка попыток по роли и владельцу.
     *
     * Входные данные: Два записанных студента сдают один тест, затем каждый запрашивает результаты. Преподаватель также читает список.
     * Ожидаемый результат: Каждый студент получает только свою попытку, преподаватель видит обе.
     */
    @Test
    void quizResultsAreFilteredByStudent() throws Exception {
        var fixture = fixture();
        var second = newStudent();
        enrollment.enroll(fixture.student(), fixture.course());
        enrollment.enroll(second, fixture.course());
        var quiz = quiz(fixture);
        var question = quiz.questions().getFirst();
        var answers = Map.of(question.id(), Set.of(question.options().getFirst().id()));
        long firstId = quizzes.submit(fixture.student(), quiz.id(), answers).id();
        long secondId = quizzes.submit(second, quiz.id(), answers).id();
        assertThat(quizzes.results(fixture.student(), quiz.id())).extracting(QuizService.Result::id).containsExactly(firstId);
        assertThat(quizzes.results(second, quiz.id())).extracting(QuizService.Result::id).containsExactly(secondId);
        assertThat(quizzes.results(fixture.teacher(), quiz.id())).extracting(QuizService.Result::id)
                .containsExactly(firstId, secondId);
    }

    /**
     * Описание: Проверяет, что после отписки студент не может сдать новую попытку.
     *
     * Входные данные: Студент записывается, отписывается и отправляет допустимый вариант ответа через REST API.
     * Ожидаемый результат: Сервер отвечает HTTP 403, преподаватель не видит новых попыток.
     */
    @Test
    void withdrawnStudentCannotSubmitQuizAttempt() throws Exception {
        var fixture = fixture();
        enrollment.enroll(fixture.student(), fixture.course());
        var quiz = quiz(fixture);
        var question = quiz.questions().getFirst();
        enrollment.withdraw(fixture.student(), fixture.course());
        var response = request("POST", "/api/quizzes/" + quiz.id() + "/attempts", bearer(fixture.student()), """
                {"answers":{"%d":[%d]}}
                """.formatted(question.id(), question.options().getFirst().id()));
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(quizzes.results(fixture.teacher(), quiz.id())).isEmpty();
    }

    /**
     * Описание: Проверяет подсчёт успеваемости по нескольким курсам с учётом истории обучения.
     *
     * Входные данные: Студент получает 80 из 100 и 40 из 50 на двух курсах, отписывается от второго. На первом остаётся ещё одна работа без оценки.
     * Ожидаемый результат: API отвечает HTTP 200. Итог составляет 120 из 150, оба курса остаются в ответе, а работа без оценки не увеличивает максимум.
     */
    @Test
    void studentAnalyticsCombinesGradedWorkAcrossCourses() throws Exception {
        var first = fixture();
        var second = fixture();
        var student = first.student();
        enrollment.enroll(student, first.course());
        enrollment.enroll(student, second.course());
        long firstAssignment = assignment(first, null).id();
        long secondAssignment = assignments.create(second.teacher(), second.lesson(),
                new AssignmentService.AssignmentInput("Second", "Instructions", null, 50)).id();
        assignments.grade(first.teacher(), assignments.submit(student, firstAssignment, "First").id(), 80);
        assignments.grade(second.teacher(), assignments.submit(student, secondAssignment, "Second").id(), 40);
        assignments.submit(student, assignment(first, null).id(), "Ungraded");
        enrollment.withdraw(student, second.course());
        var response = request("GET", "/api/analytics/me", bearer(student), null);
        assertThat(response.statusCode()).isEqualTo(200);
        var data = JsonPath.parse(response.body());
        assertThat(data.<Integer>read("$.earnedPoints")).isEqualTo(120);
        assertThat(data.<Integer>read("$.possiblePoints")).isEqualTo(150);
        assertThat(data.<List<Integer>>read("$.courses[*].gradedAssignments")).containsExactly(1, 1);
        assertThat(data.<List<Boolean>>read("$.courses[*].active")).containsExactly(true, false);
    }

    /**
     * Описание: Проверяет одинаковые места при равных результатах и исключение отписавшихся студентов.
     *
     * Входные данные: Четыре студента записываются на курс. Двое получают по 80 баллов, один остаётся без оценки, а студент со 100 баллами отписывается.
     * Ожидаемый результат: API отвечает HTTP 200. Оценённые студенты делят первое место, студент без оценки занимает второе, а отписавшегося в рейтинге нет.
     */
    @Test
    void courseRankingSharesRanksAndExcludesWithdrawnStudents() throws Exception {
        var fixture = fixture();
        var tied = newStudent();
        var ungraded = newStudent();
        var withdrawn = newStudent();
        for (var student : List.of(fixture.student(), tied, ungraded, withdrawn)) {
            enrollment.enroll(student, fixture.course());
        }
        long id = assignment(fixture, null).id();
        assignments.grade(fixture.teacher(), assignments.submit(fixture.student(), id, "First").id(), 80);
        assignments.grade(fixture.teacher(), assignments.submit(tied, id, "Tied").id(), 80);
        assignments.grade(fixture.teacher(), assignments.submit(withdrawn, id, "Withdrawn").id(), 100);
        enrollment.withdraw(withdrawn, fixture.course());
        var response = request("GET", "/api/analytics/courses/" + fixture.course() + "/ranking",
                bearer(fixture.teacher()), null);
        assertThat(response.statusCode()).isEqualTo(200);
        var data = JsonPath.parse(response.body());
        assertThat(data.<List<Integer>>read("$[*].rank")).containsExactly(1, 1, 2);
        assertThat(data.<List<Integer>>read("$[*].studentId")).containsExactly(
                Math.toIntExact(fixture.student().id()), Math.toIntExact(tied.id()), Math.toIntExact(ungraded.id()));
    }

    /**
     * Описание: Проверяет статистику теста при повторных попытках и при отсутствии результатов.
     *
     * Входные данные: Два студента сдают тест три раза суммарно. Две попытки набирают 100 процентов, одна — ноль. До сдачи преподаватель запрашивает пустую статистику.
     * Ожидаемый результат: Пустой ответ содержит ноль попыток и null вместо среднего. После сдачи видны три попытки, два участника, средний результат 66,67 процента и диапазон от нуля до ста.
     */
    @Test
    void quizStatisticsCountsAttemptsAndDistinctStudents() throws Exception {
        var fixture = fixture();
        var second = newStudent();
        enrollment.enroll(fixture.student(), fixture.course());
        enrollment.enroll(second, fixture.course());
        var quiz = quiz(fixture);
        String path = "/api/analytics/quizzes/" + quiz.id() + "/statistics";
        String teacher = bearer(fixture.teacher());
        var empty = request("GET", path, teacher, null);
        assertThat(empty.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(empty.body(), "$.attempts")).isZero();
        assertThat((Object) JsonPath.read(empty.body(), "$.averagePercentage")).isNull();
        var question = quiz.questions().getFirst();
        var firstOption = question.options().getFirst().id();
        var correct = Set.of(firstOption, question.options().get(1).id());
        quizzes.submit(fixture.student(), quiz.id(), Map.of(question.id(), correct));
        quizzes.submit(fixture.student(), quiz.id(), Map.of(question.id(), Set.of(firstOption)));
        quizzes.submit(second, quiz.id(), Map.of(question.id(), correct));
        var response = request("GET", path, teacher, null);
        assertThat(response.statusCode()).isEqualTo(200);
        var data = JsonPath.parse(response.body());
        assertThat(data.<Integer>read("$.attempts")).isEqualTo(3);
        assertThat(data.<Integer>read("$.participants")).isEqualTo(2);
        assertThat(data.<Number>read("$.averagePercentage").doubleValue()).isEqualTo(66.67);
        assertThat(data.<Number>read("$.minPercentage").doubleValue()).isZero();
        assertThat(data.<Number>read("$.maxPercentage").doubleValue()).isEqualTo(100);
    }

    /**
     * Описание: Проверяет ограничения доступа к аналитике.
     *
     * Входные данные: Без токена запрашиваем личные результаты. Студент запрашивает рейтинг, а чужой преподаватель — статистику теста.
     * Ожидаемый результат: Запрос без токена получает HTTP 401, оба запроса с недостаточными правами получают HTTP 403.
     */
    @Test
    void analyticsRejectsUnauthorizedRolesAndForeignTeachers() throws Exception {
        var fixture = fixture();
        long quizId = quiz(fixture).id();
        assertThat(request("GET", "/api/analytics/me", null, null).statusCode()).isEqualTo(401);
        assertThat(request("GET", "/api/analytics/courses/" + fixture.course() + "/ranking",
                bearer(fixture.student()), null).statusCode()).isEqualTo(403);
        assertThat(request("GET", "/api/analytics/quizzes/" + quizId + "/statistics",
                bearer(newTeacher()), null).statusCode()).isEqualTo(403);
    }

    /**
     * Описание: Проверяет получателей и текст уведомления о новом задании.
     *
     * Входные данные: Преподаватель добавляет задание на курс с активным студентом, отписавшимся студентом и пользователем без записи. Вместо почтового провайдера используется тестовый отправитель.
     * Ожидаемый результат: Активному студенту передаётся одно сообщение с названием задания и курса. Остальные студенты уведомлений не получают.
     */
    @Test
    void assignmentEmailTargetsOnlyActiveStudents() {
        var fixture = fixture();
        var withdrawn = newStudent();
        var outsider = newStudent();
        enrollment.enroll(fixture.student(), fixture.course());
        enrollment.enroll(withdrawn, fixture.course());
        enrollment.withdraw(withdrawn, fixture.course());
        var created = assignment(fixture, null);
        String title = content.get(fixture.course()).course().title();
        assertThat(mail.to(address(fixture.student()))).singleElement().satisfies(message -> {
            assertThat(message.subject()).isEqualTo("Новое задание");
            assertThat(message.text()).contains(created.title(), title);
        });
        assertThat(mail.to(address(withdrawn))).isEmpty();
        assertThat(mail.to(address(outsider))).isEmpty();
    }

    /**
     * Описание: Проверяет уведомление об оценке и повторное сохранение того же балла.
     *
     * Входные данные: Студент отправляет работу, преподаватель дважды сохраняет оценку 75 из 100. Другой студент также записан на курс.
     * Ожидаемый результат: Тестовый отправитель получает одно уведомление об оценке для автора работы. Текст содержит название задания и баллы, другой студент получает только уведомление о задании.
     */
    @Test
    void gradeEmailTargetsAuthorAndDoesNotRepeatOnIdenticalUpdate() {
        var fixture = fixture();
        var other = newStudent();
        enrollment.enroll(fixture.student(), fixture.course());
        enrollment.enroll(other, fixture.course());
        var assignment = assignment(fixture, null);
        long submission = assignments.submit(fixture.student(), assignment.id(), "Work").id();
        assignments.grade(fixture.teacher(), submission, 75);
        assignments.grade(fixture.teacher(), submission, 75);
        assertThat(mail.to(address(fixture.student())).stream().filter(message -> message.subject().equals("Работа оценена")))
                .singleElement().satisfies(message -> assertThat(message.text()).contains(assignment.title(), "75 из 100"));
        assertThat(mail.to(address(other))).extracting(EmailSender.Message::subject).containsExactly("Новое задание");
    }

    /**
     * Описание: Проверяет, что письмо не передаётся отправителю до фиксации транзакции.
     *
     * Входные данные: Преподаватель добавляет задание для курса с записанным студентом, а внешняя транзакция откатывается.
     * Ожидаемый результат: Задание отсутствует в базе, тестовый отправитель не получает сообщение.
     */
    @Test
    void rolledBackAssignmentDoesNotSendEmail() {
        var fixture = fixture();
        enrollment.enroll(fixture.student(), fixture.course());
        transactions.executeWithoutResult(status -> {
            assignment(fixture, null);
            assertThat(mail.to(address(fixture.student()))).isEmpty();
            status.setRollbackOnly();
        });
        assertThat(assignments.list(fixture.teacher(), fixture.lesson())).isEmpty();
        assertThat(mail.to(address(fixture.student()))).isEmpty();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MailTestConfiguration {
        @Bean
        RecordingEmailSender recordingEmailSender() {
            return new RecordingEmailSender();
        }
    }

    static class RecordingEmailSender implements EmailSender {
        private final ConcurrentLinkedQueue<Message> messages = new ConcurrentLinkedQueue<>();

        @Override
        public void send(Message message) {
            messages.add(message);
        }

        List<Message> to(String address) {
            return messages.stream().filter(message -> message.recipient().equals(address)).toList();
        }
    }

    private String address(Actor actor) {
        return users.findById(actor.id()).orElseThrow().getEmail();
    }

    private String bearer(Actor actor) {
        return auth.login(address(actor), PASSWORD).accessToken();
    }

    private Actor newTeacher() {
        var created = accounts.create(seeded(Role.ADMIN), email(), "Teacher", PASSWORD, Role.TEACHER);
        return new Actor(created.id(), Role.TEACHER);
    }

    private String token(String issuer, String audience, String subject, Instant expiration) {
        var header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        var claims = JwtClaimsSet.builder().issuer(issuer).audience(List.of(audience)).subject(subject)
                .issuedAt(expiration.minusSeconds(1800)).expiresAt(expiration).claim("role", "STUDENT").build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private Fixture fixture() {
        var teacher = seeded(Role.TEACHER);
        long category = content.categories().getFirst().id();
        long course = content.create(teacher, new CourseService.CourseInput("Course " + UUID.randomUUID(),
                null, category, null)).course().id();
        long module = content.createModule(teacher, course, new CourseService.ModuleInput("Module", 0)).id();
        long lesson = content.createLesson(teacher, course, module, new CourseService.LessonInput(
                "Lesson", null, null, 0, null)).id();
        return new Fixture(teacher, newStudent(), category, course, module, lesson);
    }

    private AssignmentService.AssignmentView assignment(Fixture fixture, Instant deadline) {
        return assignments.create(fixture.teacher(), fixture.lesson(), new AssignmentService.AssignmentInput(
                "Assignment", "Instructions", deadline, 100));
    }

    private QuizService.QuizView quiz(Fixture fixture) {
        var options = List.of(new QuizService.OptionInput("First", true),
                new QuizService.OptionInput("Second", true), new QuizService.OptionInput("Third", false));
        return quizzes.create(fixture.teacher(), fixture.course(), new QuizService.QuizInput("Quiz",
                List.of(new QuizService.QuestionInput("Choose", QuestionType.MULTIPLE_CHOICE, options))));
    }

    private Actor seeded(Role role) {
        var account = users.findByEmail(role.name().toLowerCase(java.util.Locale.ROOT) + "@example.test").orElseThrow();
        return new Actor(account.getId(), role);
    }

    private Actor newStudent() {
        var created = accounts.create(seeded(Role.ADMIN), email(), "Student", PASSWORD, Role.STUDENT);
        return new Actor(created.id(), Role.STUDENT);
    }

    private static String email() {
        return UUID.randomUUID() + "@example.test";
    }

    private HttpResponse<String> request(String method, String path, String token, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        return HTTP.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void expectFailure(HttpStatus status, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiFailure.class,
                failure -> assertThat(failure.status()).isEqualTo(status));
    }

    private record Fixture(Actor teacher, Actor student, long category, long course, long module, long lesson) {}
}
