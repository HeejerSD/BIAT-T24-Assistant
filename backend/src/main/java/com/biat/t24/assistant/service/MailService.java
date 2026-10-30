package com.biat.t24.assistant.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/** Envoi d'emails best-effort (ex: nouveau mot de passe) : un échec SMTP ne doit jamais bloquer l'action métier. */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final JavaMailSender mailSender;
    private final String from;

    public MailService(JavaMailSender mailSender, @Value("${biat.mail.from}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    /** @return true si l'email a été transmis au serveur SMTP sans erreur, false sinon (voir logs pour la cause). */
    public boolean send(String to, String subject, String body) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
            return true;
        } catch (Exception e) {
            log.warn("Échec de l'envoi d'email à {} (serveur SMTP indisponible ou mal configuré ?) : {}", to, e.getMessage());
            return false;
        }
    }
}
