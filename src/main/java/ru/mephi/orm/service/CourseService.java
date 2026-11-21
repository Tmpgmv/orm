package ru.mephi.orm.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.mephi.orm.entity.*;
import ru.mephi.orm.repository.*;
import ru.mephi.orm.security.Actor;

@Service
@Transactional
public class CourseService {

    private final CourseRepository courses;
    private final CategoryRepository categories;
    private final CourseModuleRepository modules;
    private final LessonRepository lessons;
    private final TagRepository tags;
    private final UserAccountRepository users;
    private final CourseAccess access;
    private final ContentDeletion deletion;

    public CourseService(CourseRepository courses, CategoryRepository categories, CourseModuleRepository modules,
                         LessonRepository lessons, TagRepository tags, UserAccountRepository users,
                         CourseAccess access, ContentDeletion deletion) {
        this.courses = courses;
        this.categories = categories;
        this.modules = modules;
        this.lessons = lessons;
        this.tags = tags;
        this.users = users;
        this.access = access;
        this.deletion = deletion;
    }

    public Named createCategory(Actor actor, String name) {
        actor.require(Role.ADMIN);
        var item = new Category();
        item.setName(name);
        return category(categories.save(item));
    }

    public Named updateCategory(Actor actor, long id, String name) {
        actor.require(Role.ADMIN);
        var item = ApiFailure.found(categories.findById(id));
        item.setName(name);
        return category(item);
    }

    @Transactional(readOnly = true)
    public List<Named> categories() {
        return categories.findAll().stream().map(CourseService::category).toList();
    }

    public void deleteCategory(Actor actor, long id) {
        actor.require(Role.ADMIN);
        categories.delete(ApiFailure.found(categories.findById(id)));
    }

    public CourseView create(Actor actor, CourseInput input) {
        actor.require(Role.TEACHER);
        var course = new Course();
        course.setTeacher(ApiFailure.found(users.findById(actor.id())));
        change(course, input);
        return view(courses.save(course));
    }

    public CourseView update(Actor actor, long id, CourseInput input) {
        var course = owned(actor, id);
        change(course, input);
        return view(course);
    }

    @Transactional(readOnly = true)
    public CourseView get(long id) {
        return view(ApiFailure.found(courses.findById(id)));
    }

    @Transactional(readOnly = true)
    public List<CourseSummary> list() {
        return courses.findAll().stream().map(CourseService::summary).toList();
    }

    public void delete(Actor actor, long id) {
        deletion.course(owned(actor, id));
    }

    public ModuleView createModule(Actor actor, long courseId, ModuleInput input) {
        var module = new CourseModule();
        module.setCourse(owned(actor, courseId));
        change(module, input);
        return view(modules.save(module));
    }

    public ModuleView updateModule(Actor actor, long courseId, long moduleId, ModuleInput input) {
        var module = module(actor, courseId, moduleId);
        change(module, input);
        return view(module);
    }

    public void deleteModule(Actor actor, long courseId, long moduleId) {
        deletion.module(module(actor, courseId, moduleId));
    }

    public LessonView createLesson(Actor actor, long courseId, long moduleId, LessonInput input) {
        var lesson = new Lesson();
        lesson.setModule(module(actor, courseId, moduleId));
        change(lesson, input);
        return view(lessons.save(lesson));
    }

    public LessonView updateLesson(Actor actor, long courseId, long moduleId, long lessonId, LessonInput input) {
        var lesson = lesson(actor, courseId, moduleId, lessonId);
        change(lesson, input);
        return view(lesson);
    }

    public void deleteLesson(Actor actor, long courseId, long moduleId, long lessonId) {
        deletion.lesson(lesson(actor, courseId, moduleId, lessonId));
    }

    public CourseView addTag(Actor actor, long courseId, String name) {
        var course = owned(actor, courseId);
        var tag = tags.findByName(name).orElseGet(() -> {
            var newTag = new Tag();
            newTag.setName(name);
            return tags.save(newTag);
        });
        course.getTags().add(tag);
        return view(course);
    }

    public void removeTag(Actor actor, long courseId, long tagId) {
        var course = owned(actor, courseId);
        course.getTags().removeIf(tag -> tag.getId() == tagId);
    }

    private Course owned(Actor actor, long courseId) {
        var course = ApiFailure.found(courses.findById(courseId));
        access.teacher(actor, course);
        return course;
    }

    private CourseModule module(Actor actor, long courseId, long moduleId) {
        owned(actor, courseId);
        var module = ApiFailure.found(modules.findById(moduleId));
        if (module.getCourse().getId() != courseId) {
            throw ApiFailure.missing();
        }
        return module;
    }

    private Lesson lesson(Actor actor, long courseId, long moduleId, long lessonId) {
        module(actor, courseId, moduleId);
        var lesson = ApiFailure.found(lessons.findById(lessonId));
        if (lesson.getModule().getId() != moduleId) {
            throw ApiFailure.missing();
        }
        return lesson;
    }

    private void change(Course course, CourseInput input) {
        course.setTitle(input.title());
        course.setDescription(input.description());
        course.setCategory(ApiFailure.found(categories.findById(input.categoryId())));
        course.setStartDate(input.startDate());
    }

    private static void change(CourseModule module, ModuleInput input) {
        module.setTitle(input.title());
        module.setPosition(input.position());
    }

    private static void change(Lesson lesson, LessonInput input) {
        lesson.setTitle(input.title());
        lesson.setPosition(input.position());
        lesson.setContent(input.content());
        lesson.setMaterialUrl(input.materialUrl());
        lesson.setScheduledAt(input.scheduledAt());
    }

    private static Named category(Category category) {
        return new Named(category.getId(), category.getName());
    }

    private static CourseSummary summary(Course course) {
        return new CourseSummary(course.getId(), course.getTitle(), course.getTeacher().getId(),
                category(course.getCategory()), course.getStartDate());
    }

    private static CourseView view(Course course) {
        return new CourseView(summary(course), course.getDescription(),
                course.getModules().stream().map(CourseService::view).toList(),
                course.getTags().stream().map(tag -> new Named(tag.getId(), tag.getName())).toList());
    }

    private static ModuleView view(CourseModule module) {
        return new ModuleView(module.getId(), module.getTitle(), module.getPosition(),
                module.getLessons().stream().map(CourseService::view).toList());
    }

    private static LessonView view(Lesson lesson) {
        return new LessonView(lesson.getId(), lesson.getTitle(), lesson.getPosition(), lesson.getContent(),
                lesson.getMaterialUrl(), lesson.getScheduledAt());
    }

    public record CourseInput(String title, String description, long categoryId, LocalDate startDate) {}
    public record ModuleInput(String title, int position) {}
    public record LessonInput(String title, String content, String materialUrl, int position, Instant scheduledAt) {}

    public record Named(long id, String name) {}
    public record CourseSummary(long id, String title, long teacherId, Named category, LocalDate startDate) {}
    public record CourseView(
            CourseSummary course, String description,
            List<ModuleView> modules, List<Named> tags) {}
    public record ModuleView(long id, String title, int position, List<LessonView> lessons) {}
    public record LessonView(long id, String title, int position, String content, String materialUrl, Instant scheduledAt) {}
}
