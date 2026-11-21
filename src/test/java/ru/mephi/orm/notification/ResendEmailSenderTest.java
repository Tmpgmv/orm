package ru.mephi.orm.notification;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ResendEmailSenderTest {

    /**
     * Описание: Проверяет, как отправитель формирует запрос к Resend.
     *
     * Входные данные: Передаём адрес студента, адрес отправителя, тему, текст письма и тестовый ключ.
     * Ожидаемый результат: Подставной сервер получает POST с заголовком авторизации и ожидаемыми полями JSON.
     */
    @Test
    void submitsExpectedRequestToProvider() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var sender = new ResendEmailSender("test-key", "courses@example.test", builder);
        server.expect(requestTo("https://api.resend.com/emails"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(content().json("""
                        {"from":"courses@example.test","to":["student@example.test"],
                         "subject":"Новое задание","text":"Задание опубликовано."}
                        """))
                .andRespond(withSuccess());

        sender.send(new EmailSender.Message("student@example.test", "Новое задание", "Задание опубликовано."));

        server.verify();
    }
}
