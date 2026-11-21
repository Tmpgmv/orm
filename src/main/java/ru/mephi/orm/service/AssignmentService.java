package ru.mephi.orm.service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.mephi.orm.entity.Assignment;
import ru.mephi.orm.entity.Course;
import ru.mephi.orm.entity.Role;
import ru.mephi.orm.entity.Submission;
import ru.mephi.orm.notification.AssignmentNotifications.AssignmentEmailEvent;
import ru.mephi.orm.notification.EmailSender.Message;
import ru.mephi.orm.repository.AssignmentRepository;
import ru.mephi.orm.repository.EnrollmentRepository;
import ru.mephi.orm.repository.LessonRepository;
import ru.mephi.orm.repository.SubmissionRepository;
import ru.mephi.orm.repository.UserAccountRepository;
import ru.mephi.orm.security.Actor;

@Service
@Transactional
public class AssignmentService {

    private final AssignmentRepository assignments;
    private final LessonRepository lessons;
    private final SubmissionRepository submissions;
    private final UserAccountRepository users;
    private final CourseAccess access;
    private final ContentDeletion deletion;
    private final EnrollmentRepository enrollments;
    private final ApplicationEventPublisher events;

    public AssignmentService(AssignmentRepository assignments, LessonRepository lessons,
                             SubmissionRepository submissions, UserAccountRepository users,
                             CourseAccess access, ContentDeletion deletion, EnrollmentRepository enrollments,
                             ApplicationEventPublisher events) {
        this.assignments = assignments;
        this.lessons = lessons;
        this.submissions = submissions;
        this.users = users;
        this.access = access;
        this.deletion = deletion;
        this.enrollments = enrollments;
        this.events = events;
    }

    public AssignmentView create(Actor actor, long lessonId, AssignmentInput input) {
        var lesson = ApiFailure.found(lessons.findById(lessonId));
        access.teacher(actor, lesson.getModule().getCourse());
        var assignment = new Assignment();
        assignment.setLesson(lesson);
        change(assignment, input);
        assignments.save(assignment);
        var messages = enrollments.findByCourseIdAndActiveTrueOrderById(course(assignment).getId()).stream()
                .map(enrollment -> new Message(enrollment.getStudent().getEmail(), "Новое задание",
                        "В курсе «%s» добавлено задание «%s».".formatted(course(assignment).getTitle(), assignment.getTitle())))
                .toList();
        if (!messages.isEmpty()) {
            events.publishEvent(new AssignmentEmailEvent(messages));
        }
        return view(assignment);
    }

    public AssignmentView update(Actor actor, long id, AssignmentInput input) {
        var assignment = locked(id);
        access.teacher(actor, course(assignment));
        if (submissions.findByAssignmentIdOrderById(id).stream()
                .anyMatch(item -> item.getScore() != null && item.getScore() > input.maxScore())) {
            throw ApiFailure.conflict("Maximum score falls below an existing grade");
        }
        change(assignment, input);
        return view(assignment);
    }

    public void delete(Actor actor, long id) {
        var assignment = locked(id);
        access.teacher(actor, course(assignment));
        deletion.assignment(assignment);
    }

    @Transactional(readOnly = true)
    public AssignmentView read(Actor actor, long id) {
        var assignment = get(id);
        access.participant(actor, course(assignment));
        return view(assignment);
    }

    @Transactional(readOnly = true)
    public List<AssignmentView> list(Actor actor, long lessonId) {
        var lesson = ApiFailure.found(lessons.findById(lessonId));
        access.participant(actor, lesson.getModule().getCourse());
        return assignments.findByLessonIdOrderById(lessonId).stream().map(AssignmentService::view).toList();
    }

    public SubmissionView submit(Actor actor, long assignmentId, String work) {
        var assignment = locked(assignmentId);
        access.lockActiveStudent(actor, course(assignment));
        var now = Instant.now();
        if (assignment.getDeadline() != null && now.isAfter(assignment.getDeadline())) {
            throw ApiFailure.conflict("Assignment deadline has passed");
        }
        var submission = new Submission();
        submission.setAssignment(assignment);
        submission.setStudent(ApiFailure.found(users.findById(actor.id())));
        submission.setWork(work);
        submission.setSubmittedAt(now);
        return view(submissions.saveAndFlush(submission));
    }

    public SubmissionView grade(Actor actor, long id, int score) {
        var submission = ApiFailure.found(submissions.findById(id));
        var assignment = locked(submission.getAssignment().getId());
        access.teacher(actor, course(assignment));
        if (score < 0 || score > assignment.getMaxScore()) {
            throw ApiFailure.badInput("Grade must be between zero and the assignment maximum");
        }
        if (!Objects.equals(submission.getScore(), score)) {
            submission.setScore(score);
            events.publishEvent(new AssignmentEmailEvent(List.of(new Message(submission.getStudent().getEmail(), "Работа оценена",
                    "Задание «%s»: %d из %d баллов.".formatted(assignment.getTitle(), score, assignment.getMaxScore())))));
        }
        return view(submission);
    }

    @Transactional(readOnly = true)
    public SubmissionView submission(Actor actor, long id) {
        var submission = ApiFailure.found(submissions.findById(id));
        if (actor.role() == Role.STUDENT) {
            actor.requireOwner(submission.getStudent().getId());
        } else {
            access.teacher(actor, course(submission.getAssignment()));
        }
        return view(submission);
    }

    @Transactional(readOnly = true)
    public List<SubmissionView> submissions(Actor actor, long assignmentId) {
        access.teacher(actor, course(get(assignmentId)));
        return submissions.findByAssignmentIdOrderById(assignmentId).stream().map(AssignmentService::view).toList();
    }

    private Assignment locked(long id) {
        return ApiFailure.found(assignments.findLockedById(id));
    }

    private Assignment get(long id) {
        return ApiFailure.found(assignments.findById(id));
    }

    private static Course course(Assignment assignment) {
        return assignment.getLesson().getModule().getCourse();
    }

    private static void change(Assignment target, AssignmentInput input) {
        target.setTitle(input.title());
        target.setInstructions(input.instructions());
        target.setDeadline(input.deadline());
        target.setMaxScore(input.maxScore());
    }

    private static AssignmentView view(Assignment item) {
        return new AssignmentView(item.getId(), item.getLesson().getId(), item.getTitle(),
                item.getInstructions(), item.getDeadline(), item.getMaxScore());
    }

    private static SubmissionView view(Submission item) {
        return new SubmissionView(item.getId(), item.getAssignment().getId(), item.getStudent().getId(),
                item.getWork(), item.getSubmittedAt(), item.getScore());
    }

    public record AssignmentInput(String title, String instructions, Instant deadline, int maxScore) {}
    public record AssignmentView(long id, long lessonId, String title, String instructions, Instant deadline, int maxScore) {}
    public record SubmissionView(long id, long assignmentId, long studentId, String work, Instant submittedAt, Integer score) {}
}
