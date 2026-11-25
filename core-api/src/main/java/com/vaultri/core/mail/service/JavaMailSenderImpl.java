package com.vaultri.core.mail.service;

import com.vaultri.core.mail.config.MailProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class JavaMailSenderImpl implements MailSender {

    private final JavaMailSender javaMailSender;
    private final MailProperties mailProperties;

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
            throw new RuntimeException("SMTP email dispatch failed", e);
        }
    }
}
