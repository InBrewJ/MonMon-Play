package models;

import java.time.LocalDate;

public class CalendarEvent {
    private String id;
    private String title;
    private LocalDate date;
    private String description;

    public CalendarEvent() {}

    public CalendarEvent(String id, String title, LocalDate date, String description) {
        this.id = id;
        this.title = title;
        this.date = date;
        this.description = description;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
