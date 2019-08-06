package com.hpe.ossm.jcluster.messages;

import lombok.Getter;
import java.io.Serializable;

public class Pong implements Serializable {
    private static final long serialVersionUID = 1L;
    @Getter
    final private long ts=System.currentTimeMillis();
}
