package org.apache.storm.alarm;

import java.util.List;
import java.util.Map;

import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.base.BaseRichBolt;
import org.apache.storm.tuple.Tuple;

public class AlarmBolt extends BaseRichBolt{

    private OutputCollector collector;
    private AlarmManager alarmManager;
    private AlarmEventMapper mapper;

    @Override
    public void prepare(Map stormConf, TopologyContext context,
            OutputCollector collector) {
        this.collector = collector;
    }

    @Override
    public void execute(Tuple tuple) {
        try{
            List<Alarm> alarms = this.alarmManager.getAlarms();
            for(Alarm alarm : alarms){
                AlarmEvent event = mapper.toAlarmEvent(tuple);
                alarm.alert(event);
            }
            this.collector.ack(tuple);
        }catch(Exception e){
            this.collector.reportError(e);
            this.collector.fail(tuple);
        }
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        // TODO Auto-generated method stub
        
    }

}
