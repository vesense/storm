package org.apache.storm.alarm.email;

import org.apache.storm.alarm.Alarm;
import org.apache.storm.alarm.AlarmEvent;

public class EmailAlarm implements Alarm {
    
    private EmailSender emailSender;

    @Override
    public void alert(AlarmEvent event) {
        
    }

}
