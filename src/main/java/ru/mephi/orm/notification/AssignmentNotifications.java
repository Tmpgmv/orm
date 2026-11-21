package ru.mephi.orm.notification;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class AssignmentNotifications {

    private static final Logger log = LoggerFactory.getLogger(AssignmentNotifications.class);

    private final ObjectProvider<EmailSender> sender;

    public AssignmentNotifications(ObjectProvider<EmailSender> sender) {
        this.sender = sender;
    }

    @TransactionalEventListener
    void afterCommit(AssignmentEmailEvent event) {
        sender.ifAvailable(transport -> {
            for (var message : event.messages()) {
                try {
                    transport.send(message);
                } catch (RuntimeException failure) {
                    log.warn("Не удалось передать уведомление провайдеру ({}).",
                            failure.getClass().getSimpleName());
                }
            }
        });
    }

    public record AssignmentEmailEvent(List<EmailSender.Message> messages) {
        public AssignmentEmailEvent {
            messages = List.copyOf(messages);
        }
    }
}
