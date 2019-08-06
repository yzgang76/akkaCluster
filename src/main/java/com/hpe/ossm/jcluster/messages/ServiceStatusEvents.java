package com.hpe.ossm.jcluster.messages;

import akka.actor.ActorRef;
import lombok.Data;

import java.io.Serializable;
import java.util.HashMap;

import org.json.*;

@Data
public class ServiceStatusEvents implements Serializable {
    private static final long serialVersionUID = 1L;
    public static final String STATUS_AVAILABLE = "Available";
    public static final String STATUS_PENDING = "Pending";
    public static final String STATUS_UNAVAILABLE = "Unavailable";

    private final long ts;

    private final String status;
    private final String serviceName;
    private final ActorRef actorRef;
    private final String actorPath;
    private final String host;

    public ServiceStatusEvents(String st, String se, ActorRef r, String h) {
        status = st;
        serviceName = se;
        actorRef = r;
        host = h;
        actorPath = null;
        ts = System.currentTimeMillis();
    }

    public ServiceStatusEvents(String st, String se, String r, String h) {
        status = st;
        serviceName = se;
        actorRef = null;
        actorPath = r;
        host = h;
        ts = System.currentTimeMillis();
    }

    private ServiceStatusEvents(String st, String se, ActorRef r, String h, long t) {
        status = st;
        serviceName = se;
        actorRef = r;
        actorPath = null;
        host = h;
        ts = t;
    }

    private ServiceStatusEvents(String st, String se, String r, String h, long t) {
        status = st;
        serviceName = se;
        actorRef = null;
        actorPath = r;
        host = h;
        ts = t;
    }

    public String getStatus() {
        return status;
    }

    public String getServiceName() {
        return serviceName;
    }

    public ActorRef getActorRef() {
        return actorRef;
    }

    public String getHost() {
        return host;
    }

    public String getActorPath() {return actorPath;}

//    public Byte[] toByteArray() {
//       return this.toByteArray();
//    }

    public String toString() {
        HashMap<String, Serializable> map = new HashMap<>();
        map.put("status", status);
        map.put("serviceName", serviceName);
        if(actorPath!=null) map.put("actorPath", actorPath);
        else map.put("actorPath", actorRef.path().toString());
        map.put("host", host);
        map.put("ts", ts);
        return new JSONObject(map).toString();
    }

    public static ServiceStatusEvents fromString(String obj) throws JSONException {
        JSONObject o = new JSONObject(obj);
        return new ServiceStatusEvents(
                o.getString("status"),
                o.getString("serviceName"),
                o.getString("actorPath"),
                o.getString("host"),
                o.getLong("ts")
        );
    }
}
