package org.apache.storm.alarm.email;

import java.util.List;

import org.apache.commons.mail.Email;
import org.apache.commons.mail.EmailException;
import org.apache.commons.mail.HtmlEmail;

public class EmailSender {
    
    private Email email;
    
    public EmailSender(String host, String user, String password) {
        email = new HtmlEmail();
        email.setCharset("UTF-8");
        email.setHostName(host);
        email.setAuthentication(user, password);
    }

    public void sendEmail(EmailUser from, List<EmailUser> to, List<EmailUser> cc, String subject, String msg) throws EmailException{
        email.setFrom(from.getEmail(), from.getName());
        
        for(EmailUser user : to){
            email.addTo(user.getEmail(), user.getName());
        }
       
        for(EmailUser user : to){
            email.addCc(user.getEmail(), user.getName());
        }
        
        email.setSubject(subject);
        email.setMsg(msg);
        
        email.send();
    }
}
