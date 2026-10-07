package com.ticketflow.model;

import com.rabbitmq.client.Channel;
import java.util.Map;

public record MqLabTopology(String exchange,String work,String deadExchange,String dead,String retryExchange) {
    public static MqLabTopology declare(Channel channel,String base,int retryMillis) throws Exception {
        var topology=new MqLabTopology(base+".x",base+".work",base+".dead.x",base+".dead",base+".retry.x");
        channel.exchangeDeclare(topology.exchange(),"direct",true);
        channel.exchangeDeclare(topology.deadExchange(),"direct",true);
        channel.exchangeDeclare(topology.retryExchange(),"direct",true);
        channel.queueDeclare(topology.dead(),true,false,false,Map.of("x-queue-type","quorum","x-max-length",1000));
        channel.queueBind(topology.dead(),topology.deadExchange(),"dead");
        channel.queueDeclare(topology.work(),true,false,false,Map.of("x-queue-type","quorum","x-max-length",1000,
                "x-dead-letter-exchange",topology.deadExchange(),"x-dead-letter-routing-key","dead"));
        channel.queueBind(topology.work(),topology.exchange(),"work");
        for(int attempt=1;attempt<=3;attempt++) {
            String queue=base+".retry."+attempt;
            channel.queueDeclare(queue,true,false,false,Map.of("x-queue-type","quorum","x-max-length",1000,
                    "x-message-ttl",retryMillis*(1<<(attempt-1)),"x-dead-letter-exchange",topology.exchange(),"x-dead-letter-routing-key","work"));
            channel.queueBind(queue,topology.retryExchange(),"retry."+attempt);
        }
        return topology;
    }
}
