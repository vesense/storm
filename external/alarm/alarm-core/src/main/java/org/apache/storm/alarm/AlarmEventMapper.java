package org.apache.storm.alarm;

import org.apache.storm.tuple.Tuple;

public interface AlarmEventMapper {

    AlarmEvent toAlarmEvent(Tuple tuple);

}
