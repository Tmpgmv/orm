package ru.mephi.orm.notification;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnProperty(name = "app.email.enabled", havingValue = "true")
public class ResendEmailSender implements EmailSender {

    private final RestClient client;
    private final String from;

    @Autowired
    public ResendEmailSender(@Value("${app.email.api-key}") String apiKey, @Value("${app.email.from}") String from) {
        this(apiKey, from, defaultBuilder());
    }

    ResendEmailSender(String apiKey, String from, RestClient.Builder builder) {
        if (apiKey.isBlank() || from.isBlank()) {
            throw new IllegalArgumentException("EMAIL_API_KEY и EMAIL_FROM обязательны для отправки почты");
        }
        this.from = from;
        client = builder.baseUrl("https://api.resend.com")
                .defaultHeaders(headers -> headers.setBearerAuth(apiKey))
                .build();
    }

    private static RestClient.Builder defaultBuilder() {
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        var requests = new JdkClientHttpRequestFactory(http);
        requests.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().requestFactory(requests);
    }

    @Override
    public void send(Message message) {
        client.post().uri("/emails").contentType(MediaType.APPLICATION_JSON)
                .body(new Payload(from, List.of(message.recipient()), message.subject(), message.text()))
                .retrieve().toBodilessEntity();
    }

    record Payload(String from, List<String> to, String subject, String text) {}
}
