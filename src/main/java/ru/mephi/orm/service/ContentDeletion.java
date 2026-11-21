package ru.mephi.orm.service;

import org.springframework.stereotype.Component;
import ru.mephi.orm.entity.Assignment;
import ru.mephi.orm.entity.Course;
import ru.mephi.orm.entity.CourseModule;
import ru.mephi.orm.entity.Lesson;
import ru.mephi.orm.entity.Quiz;
import ru.mephi.orm.repository.*;

@Component
class ContentDeletion {

    private final CourseRepository courses;
    private final CourseModuleRepository modules;
    private final LessonRepository lessons;
    private final AssignmentRepository assignments;
    private final QuizRepository quizzes;
    private final EnrollmentRepository enrollments;
    private final CourseReviewRepository reviews;

    ContentDeletion(CourseRepository courses, CourseModuleRepository modules, LessonRepository lessons,
                    AssignmentRepository assignments, QuizRepository quizzes, EnrollmentRepository enrollments,
                    CourseReviewRepository reviews) {
        this.courses = courses;
        this.modules = modules;
        this.lessons = lessons;
        this.assignments = assignments;
        this.quizzes = quizzes;
        this.enrollments = enrollments;
        this.reviews = reviews;
    }

    void course(Course course) {
        quizzes.findByCourseIdOrderById(course.getId()).forEach(this::quiz);
        course.getModules().stream().toList().forEach(this::module);
        enrollments.deleteForCourse(course.getId());
        reviews.deleteForCourse(course.getId());
        courses.delete(course);
        courses.flush();
    }

    void module(CourseModule module) {
        module.getLessons().stream().toList().forEach(this::lesson);
        module.getCourse().getModules().remove(module);
        modules.delete(module);
        modules.flush();
    }

    void lesson(Lesson lesson) {
        assignments.findByLessonIdOrderById(lesson.getId()).forEach(this::assignment);
        lesson.getModule().getLessons().remove(lesson);
        lessons.delete(lesson);
        lessons.flush();
    }

    void assignment(Assignment assignment) {
        assignments.delete(assignment);
        assignments.flush();
    }

    void quiz(Quiz quiz) {
        quizzes.delete(quiz);
        quizzes.flush();
    }
}
