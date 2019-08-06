package com.hpe.ossm.jcluster.messages;

import akka.actor.ActorRef;
import lombok.Getter;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.Serializable;
import java.util.HashMap;

@Getter
public class LookingForService implements Serializable {
    private static final long serialVersionUID = 1L;
    private final String serviceName;
    private final ActorRef seeker;
    private final String seekerPath;

    public LookingForService(String se, ActorRef r) {
        serviceName = se;
        seeker = r;
        seekerPath = null;
    }

    public LookingForService(String se, String p) {
        serviceName = se;
        seekerPath = p;
        seeker = null;
    }

    public String getServiceName() {
        return serviceName;
    }

    public ActorRef getSeeker() {
        return seeker;
    }

    public String getSeekerPath(){
        return seekerPath;
    }

    public String toString() {
        HashMap<String, Serializable> map = new HashMap<>();
        map.put("serviceName", serviceName);
        map.put("seekerPath", seekerPath);
        return new JSONObject(map).toString();
    }

    public static LookingForService fromString(String obj) throws JSONException {
        JSONObject o = new JSONObject(obj);
        return new LookingForService(
                o.getString("serviceName"),
                o.getString("seekerPath")
        );
    }
}
