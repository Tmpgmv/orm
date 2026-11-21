package ru.mephi.orm.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "course_module")
public class CourseModule extends Model {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Course course;

    @Column(nullable = false)
    private String title;

    private int position;

    @OneToMany(mappedBy = "module", cascade = CascadeType.PERSIST, fetch = FetchType.LAZY)
    @OrderBy("position ASC, id ASC")
    private List<Lesson> lessons = new ArrayList<>();

    public Course getCourse() {
        return course;
    }

    public void setCourse(Course course) {
        this.course = course;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public List<Lesson> getLessons() {
        return lessons;
    }
}
