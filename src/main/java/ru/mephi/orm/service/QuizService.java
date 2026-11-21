package ru.mephi.orm.service;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.mephi.orm.entity.AnswerOption;
import ru.mephi.orm.entity.Question;
import ru.mephi.orm.entity.QuestionType;
import ru.mephi.orm.entity.Quiz;
import ru.mephi.orm.entity.QuizSubmission;
import ru.mephi.orm.entity.Role;
import ru.mephi.orm.repository.CourseRepository;
import ru.mephi.orm.repository.QuizRepository;
import ru.mephi.orm.repository.QuizSubmissionRepository;
import ru.mephi.orm.repository.UserAccountRepository;
import ru.mephi.orm.security.Actor;

@Service
@Transactional
public class QuizService {

    private final QuizRepository quizzes;
    private final CourseRepository courses;
    private final QuizSubmissionRepository results;
    private final UserAccountRepository users;
    private final CourseAccess access;
    private final ContentDeletion deletion;

    public QuizService(QuizRepository quizzes, CourseRepository courses, QuizSubmissionRepository results,
                       UserAccountRepository users, CourseAccess access, ContentDeletion deletion) {
        this.quizzes = quizzes;
        this.courses = courses;
        this.results = results;
        this.users = users;
        this.access = access;
        this.deletion = deletion;
    }

    public QuizView create(Actor actor, long courseId, QuizInput input) {
        var course = ApiFailure.found(courses.findById(courseId));
        access.teacher(actor, course);
        var quiz = new Quiz();
        quiz.setCourse(course);
        change(quiz, input);
        return view(quizzes.saveAndFlush(quiz), true);
    }

    public QuizView update(Actor actor, long id, QuizInput input) {
        var quiz = locked(id);
        access.teacher(actor, quiz.getCourse());
        change(quiz, input);
        quizzes.flush();
        return view(quiz, true);
    }

    public void delete(Actor actor, long id) {
        var quiz = locked(id);
        access.teacher(actor, quiz.getCourse());
        deletion.quiz(quiz);
    }

    @Transactional(readOnly = true)
    public QuizView read(Actor actor, long id) {
        var quiz = get(id);
        access.participant(actor, quiz.getCourse());
        return view(quiz, actor.role() == Role.TEACHER);
    }

    @Transactional(readOnly = true)
    public List<QuizSummary> list(Actor actor, long courseId) {
        var course = ApiFailure.found(courses.findById(courseId));
        access.participant(actor, course);
        return quizzes.findByCourseIdOrderById(courseId).stream()
                .map(quiz -> new QuizSummary(quiz.getId(), quiz.getTitle())).toList();
    }

    public Result submit(Actor actor, long id, Map<Long, Set<Long>> answers) {
        var quiz = locked(id);
        access.lockActiveStudent(actor, quiz.getCourse());
        Set<Long> questions = new HashSet<>();
        int score = 0;
        for (var question : quiz.getQuestions()) {
            questions.add(question.getId());
            score += score(question, answers.get(question.getId()));
        }
        if (!answers.keySet().equals(questions)) {
            throw ApiFailure.badInput("Submit answers for exactly the quiz questions");
        }
        var result = new QuizSubmission();
        result.setQuiz(quiz);
        result.setStudent(ApiFailure.found(users.findById(actor.id())));
        result.setScore(score);
        result.setTotal(questions.size());
        result.setTakenAt(Instant.now());
        return view(results.save(result));
    }

    @Transactional(readOnly = true)
    public List<Result> results(Actor actor, long quizId) {
        var quiz = get(quizId);
        if (actor.role() == Role.STUDENT) {
            return results.findByQuizIdAndStudentIdOrderById(quizId, actor.id()).stream()
                    .map(QuizService::view).toList();
        }
        access.teacher(actor, quiz.getCourse());
        return results.findByQuizIdOrderById(quizId).stream().map(QuizService::view).toList();
    }

    @Transactional(readOnly = true)
    public Result result(Actor actor, long id) {
        var result = ApiFailure.found(results.findById(id));
        if (actor.role() == Role.STUDENT) {
            actor.requireOwner(result.getStudent().getId());
        } else {
            access.teacher(actor, result.getQuiz().getCourse());
        }
        return view(result);
    }

    private Quiz get(long id) {
        return ApiFailure.found(quizzes.findById(id));
    }

    private Quiz locked(long id) {
        return ApiFailure.found(quizzes.findLockedById(id));
    }

    private static int score(Question question, Set<Long> selected) {
        if (selected == null || selected.isEmpty()
                || (question.getType() == QuestionType.SINGLE_CHOICE && selected.size() != 1)) {
            throw ApiFailure.badInput("Invalid selections for question " + question.getId());
        }
        var valid = question.getOptions().stream().map(AnswerOption::getId).collect(Collectors.toSet());
        if (!valid.containsAll(selected)) {
            throw ApiFailure.badInput("An option does not belong to question " + question.getId());
        }
        var correct = question.getOptions().stream().filter(AnswerOption::isCorrect)
                .map(AnswerOption::getId).collect(Collectors.toSet());
        return correct.equals(selected) ? 1 : 0;
    }

    private static void change(Quiz quiz, QuizInput input) {
        quiz.setTitle(input.title());
        quiz.getQuestions().clear();
        for (var definition : input.questions()) {
            var question = new Question();
            question.setQuiz(quiz);
            question.setType(definition.type());
            question.setPrompt(definition.prompt());
            long correct = definition.options().stream().filter(OptionInput::correct).count();
            if (correct == 0 || (definition.type() == QuestionType.SINGLE_CHOICE && correct != 1)) {
                throw ApiFailure.badInput("Invalid number of correct options");
            }
            for (var value : definition.options()) {
                var option = new AnswerOption();
                option.setQuestion(question);
                option.setText(value.text());
                option.setCorrect(value.correct());
                question.getOptions().add(option);
            }
            quiz.getQuestions().add(question);
        }
    }

    private static QuizView view(Quiz quiz, boolean showAnswers) {
        var questions = quiz.getQuestions().stream().map(question -> new QuestionView(
                question.getId(), question.getPrompt(), question.getType(), question.getOptions().stream()
                .map(option -> new OptionView(option.getId(), option.getText(), showAnswers ? option.isCorrect() : null))
                .toList())).toList();
        return new QuizView(quiz.getId(), quiz.getCourse().getId(), quiz.getTitle(), questions);
    }

    private static Result view(QuizSubmission result) {
        return new Result(result.getId(), result.getQuiz().getId(), result.getStudent().getId(),
                result.getScore(), result.getTotal(), result.getTakenAt());
    }

    public record QuizInput(String title, List<QuestionInput> questions) {}
    public record QuestionInput(String prompt, QuestionType type, List<OptionInput> options) {}
    public record OptionInput(String text, boolean correct) {}

    public record QuizSummary(long id, String title) {}
    public record QuizView(long id, long courseId, String title, List<QuestionView> questions) {}
    public record QuestionView(long id, String prompt, QuestionType type, List<OptionView> options) {}
    public record OptionView(long id, String text, Boolean correct) {}

    public record Result(long id, long quizId, long studentId,
                         int score, int total, Instant takenAt) {
    }
}
