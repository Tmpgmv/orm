package ru.mephi.orm.notification;

public interface EmailSender {

    void send(Message message);

    record Message(String recipient, String subject, String text) {}
}
