package com.vaultri.core.mail.service;

import com.vaultri.core.mail.config.MailProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

import java.util.Properties;

@Slf4j
@Service
@RequiredArgsConstructor
public class SmtpMailSender implements MailSender {

    private final JavaMailSender javaMailSender;
    private final MailProperties mailProperties;

    /**
     * Note on row lock duration during dispatch: read and write timeouts apply per individual socket
     * operation. An SMTP send involves multiple sequential round-trip operations (EHLO, STARTTLS,
     * AUTH, MAIL FROM, RCPT, DATA, QUIT), so the database row lock held during dispatch is held
     * for a bounded but not tightly capped time, with no overall deadline.
     */
    @PostConstruct
    public void configureSmtpTimeouts() {
        if (javaMailSender instanceof JavaMailSenderImpl impl) {
            Properties javaMailProperties = impl.getJavaMailProperties();
            int connTimeout = mailProperties.getConnectionTimeout() != null ? mailProperties.getConnectionTimeout() : 5000;
            int timeout = mailProperties.getTimeout() != null ? mailProperties.getTimeout() : 10000;
            int writeTimeout = mailProperties.getWriteTimeout() != null ? mailProperties.getWriteTimeout() : 10000;

            javaMailProperties.put("mail.smtp.connectiontimeout", String.valueOf(connTimeout));
            javaMailProperties.put("mail.smtp.timeout", String.valueOf(timeout));
            javaMailProperties.put("mail.smtp.writetimeout", String.valueOf(writeTimeout));
        }
    }

    @Override
    public void sendEmail(String to, String subject, String body) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            if (mailProperties.getFrom() != null && !mailProperties.getFrom().isBlank()) {
                message.setFrom(mailProperties.getFrom());
            }
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);

            javaMailSender.send(message);
        } catch (Exception e) {
            // Never log recipient addresses, subjects or bodies; log only exception class name
            log.error("Failed to send email via SMTP: {}", e.getClass().getSimpleName());
            // Throw exception without carrying original exception as cause to prevent recipient address leakage in stack traces
            throw new RuntimeException("SMTP email dispatch failed");
        }
    }
}
