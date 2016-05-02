package org.apache.storm.alarm;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ServiceLoader;

public class AlarmManager {
    private List<Alarm> alarms;

    public AlarmManager() {
        this.alarms = new LinkedList<Alarm>();
    }

    public void addAlarm(Alarm alarm){
        this.alarms.add(alarm);
    }

    public List<Alarm> getAlarms(){
        return this.alarms;
    }

    public void loadFromSPI(){
        ServiceLoader<Alarm> alarms = ServiceLoader.load(Alarm.class);
        Iterator<Alarm> iter = alarms.iterator();
        while(iter.hasNext()){
            this.alarms.add(iter.next());
        }
    }
}
