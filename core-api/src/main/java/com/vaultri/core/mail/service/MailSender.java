package com.vaultri.core.mail.service;

public interface MailSender {
    void sendEmail(String to, String subject, String body);
}
