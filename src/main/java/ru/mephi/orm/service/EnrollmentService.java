package ru.mephi.orm.service;

import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.mephi.orm.entity.Course;
import ru.mephi.orm.entity.Enrollment;
import ru.mephi.orm.entity.Role;
import ru.mephi.orm.repository.CourseRepository;
import ru.mephi.orm.repository.EnrollmentRepository;
import ru.mephi.orm.repository.UserAccountRepository;
import ru.mephi.orm.security.Actor;

@Service
@Transactional
public class EnrollmentService {

    private final EnrollmentRepository enrollments;
    private final CourseRepository courses;
    private final UserAccountRepository users;
    private final CourseAccess access;

    public EnrollmentService(EnrollmentRepository enrollments, CourseRepository courses,
                             UserAccountRepository users, CourseAccess access) {
        this.enrollments = enrollments;
        this.courses = courses;
        this.users = users;
        this.access = access;
    }

    public Membership enroll(Actor actor, long courseId) {
        actor.require(Role.STUDENT);
        var course = ApiFailure.found(courses.findById(courseId));
        var membership = enrollments.findByStudentIdAndCourseId(actor.id(), courseId)
                .orElseGet(() -> newEnrollment(actor, course));
        if (membership.isActive()) {
            throw ApiFailure.conflict("Enrollment is already active");
        }
        membership.setActive(true);
        return view(enrollments.saveAndFlush(membership));
    }

    public void withdraw(Actor actor, long courseId) {
        actor.require(Role.STUDENT);
        var membership = ApiFailure.found(enrollments.findByStudentIdAndCourseId(actor.id(), courseId));
        membership.setActive(false);
    }

    @Transactional(readOnly = true)
    public List<Membership> mine(Actor actor) {
        actor.require(Role.STUDENT);
        return enrollments.findByStudentIdAndActiveTrueOrderById(actor.id()).stream()
                .map(EnrollmentService::view).toList();
    }

    @Transactional(readOnly = true)
    public List<Membership> roster(Actor actor, long courseId) {
        var course = ApiFailure.found(courses.findById(courseId));
        access.teacher(actor, course);
        return enrollments.findByCourseIdAndActiveTrueOrderById(courseId).stream()
                .map(EnrollmentService::view).toList();
    }

    private Enrollment newEnrollment(Actor actor, Course course) {
        var membership = new Enrollment();
        membership.setCourse(course);
        membership.setStudent(ApiFailure.found(users.findById(actor.id())));
        membership.setEnrolledAt(Instant.now());
        return membership;
    }

    private static Membership view(Enrollment enrollment) {
        return new Membership(enrollment.getId(), enrollment.getStudent().getId(), enrollment.getCourse().getId(),
                enrollment.isActive(), enrollment.getEnrolledAt());
    }

    public record Membership(long id, long studentId, long courseId, boolean active, Instant enrolledAt) {}
}
