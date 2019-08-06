package com.hpe.ossm.jmonitor.test;

import com.hpe.ossm.scala.lang.util.Util;
import com.hpe.ossm.scluster.messges.KPIRecord;
import com.hpe.ossm.scluster.messges.KPIValueType;
import com.hpe.ossm.scluster.selfMonitor.Collector;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Serializable;
import scala.collection.JavaConverters;
import scala.collection.immutable.List;

import java.util.Arrays;
import java.util.HashMap;

import com.typesafe.config.Config;

public class CollectorInJava extends Collector {
    @Override
    public Logger LOGGER() {
        return LoggerFactory.getLogger(CollectorInJava.class);
    }

    private String host;
    private String name;
    private int interval;
    private String desc;
    private String unit;

    @Override
    public void initCollector() {
        String path = "ossm.monitor.collector.test";
        try {
            Config c = conf().getConfig(path);
            host = c.getString("host");
            name = c.getString("name");
            interval = c.getInt("interval");
            Util.ignoreError(() -> {
                desc = c.getString("desc");
                return null;
            });
            Util.ignoreError(() -> {
                unit = c.getString("unit");
                return null;
            });

            /*
            important! to start timer
             */
            setTimer(interval);

        } catch (Exception e) {
            LOGGER().error("Failed to load config $path, {}", e.getMessage());
            stopSelf();
        }
    }

    @Override
    public List<String> kpiNames() {
        java.util.List<String> l = Arrays.asList("J1", "J2");
        return JavaConverters.asScalaIterator(l.iterator()).toList();
    }

    private int i = 0;

    @Override
    public List<KPIRecord> collect() {
        i++;
        java.util.HashMap<String , java.io.Serializable> map=new HashMap<>();
        map.put("v",i);
        map.put("v1","k+i");
        java.util.List<KPIRecord> l = Arrays.asList(
                new KPIRecord(host, host, "J1", ""+ i, KPIValueType.SINGLE_OBJECT(), "s", System.currentTimeMillis()),
                new KPIRecord(host, host, "J2", new JSONObject(map).toString(), KPIValueType.JSON_OBJECT(), "NA", System.currentTimeMillis())
        );
        return JavaConverters.asScalaIterator(l.iterator()).toList();
    }
}