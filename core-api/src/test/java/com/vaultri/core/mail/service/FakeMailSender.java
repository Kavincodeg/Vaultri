package com.vaultri.core.mail.service;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Primary
@Profile("test")
public class FakeMailSender implements MailSender {

    public record SentMail(String to, String subject, String body) {}

    private final List<SentMail> sentMails = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger sendCallCount = new AtomicInteger(0);
    private volatile boolean shouldFail = false;
    private final AtomicInteger remainingFailures = new AtomicInteger(0);

    @Override
    public void sendEmail(String to, String subject, String body) {
        sendCallCount.incrementAndGet();

        if (shouldFail || remainingFailures.getAndDecrement() > 0) {
            throw new RuntimeException("Simulated SMTP dispatch failure");
        }

        sentMails.add(new SentMail(to, subject, body));
    }

    public List<SentMail> getSentMails() {
        return new ArrayList<>(sentMails);
    }

    public int getSendCallCount() {
        return sendCallCount.get();
    }

    public void reset() {
        sentMails.clear();
        sendCallCount.set(0);
        shouldFail = false;
        remainingFailures.set(0);
    }

    public void setShouldFail(boolean fail) {
        this.shouldFail = fail;
    }

    public void setFailTimes(int count) {
        this.remainingFailures.set(count);
    }
}
