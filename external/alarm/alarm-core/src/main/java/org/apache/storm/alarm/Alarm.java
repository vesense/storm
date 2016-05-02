package org.apache.storm.alarm;

public interface Alarm {

    void alert(AlarmEvent event);

}
