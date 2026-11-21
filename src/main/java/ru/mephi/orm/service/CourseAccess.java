package ru.mephi.orm.service;

import org.springframework.stereotype.Component;
import ru.mephi.orm.entity.Course;
import ru.mephi.orm.entity.Role;
import ru.mephi.orm.repository.EnrollmentRepository;
import ru.mephi.orm.security.Actor;

@Component
class CourseAccess {

    private final EnrollmentRepository enrollments;

    CourseAccess(EnrollmentRepository enrollments) {
        this.enrollments = enrollments;
    }

    void teacher(Actor actor, Course course) {
        actor.require(Role.TEACHER);
        actor.requireOwner(course.getTeacher().getId());
    }

    void student(Actor actor, Course course) {
        actor.require(Role.STUDENT);
        if (!enrollments.existsByStudentIdAndCourseIdAndActiveTrue(actor.id(), course.getId())) {
            throw ApiFailure.forbidden();
        }
    }

    void lockActiveStudent(Actor actor, Course course) {
        actor.require(Role.STUDENT);
        boolean active = enrollments.findByStudentIdAndCourseId(actor.id(), course.getId())
                .map(enrollment -> enrollment.isActive()).orElse(false);
        if (!active) {
            throw ApiFailure.forbidden();
        }
    }

    void participant(Actor actor, Course course) {
        switch (actor.role()) {
            case STUDENT -> student(actor, course);
            case TEACHER -> teacher(actor, course);
            default -> throw ApiFailure.forbidden();
        }
    }
}
