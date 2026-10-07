package com.ticketflow.config;

import com.rabbitmq.client.*;
import com.ticketflow.model.MqLabMessage;
import com.ticketflow.model.MqLabPublishOutcome;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

public class MqLabBroker {
    private final String user, password, vhost;
    private static final JsonMapper JSON=JsonMapper.builder().build();
    public MqLabBroker(String user,String password,String vhost) { this.user=user;this.password=password;this.vhost=vhost; }
    public Connection connect() throws Exception { return connect(15673); }
    public Connection connect(int port) throws Exception {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Network inside DB transaction");
        var factory=new ConnectionFactory(); factory.setHost("127.0.0.1"); factory.setPort(port);
        factory.setUsername(user);factory.setPassword(password);factory.setVirtualHost(vhost);
        factory.setConnectionTimeout(5000);factory.setHandshakeTimeout(5000);factory.setRequestedHeartbeat(10);
        factory.setAutomaticRecoveryEnabled(false);factory.setTopologyRecoveryEnabled(false);
        return factory.newConnection("ticketflow-lab");
    }
    public static MqLabPublishOutcome publish(Channel channel,String exchange,String route,MqLabMessage msg) throws Exception {
        return publish(channel,exchange,route,JSON.writeValueAsBytes(msg),msg.eventId(),5000);
    }
    public static MqLabPublishOutcome publish(Channel channel,String exchange,String route,byte[] body,String event,int timeout) throws Exception {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Publish inside DB transaction");
        var returned=new AtomicBoolean();
        ReturnListener listener=(code,text,ex,key,properties,payload)->returned.set(true);
        channel.addReturnListener(listener);channel.confirmSelect();
        try {
            channel.basicPublish(exchange,route,true,new AMQP.BasicProperties.Builder()
                    .messageId(event).contentType("application/json").deliveryMode(2).build(),body);
            boolean ack=channel.waitForConfirms(timeout);
            return returned.get()?MqLabPublishOutcome.RETURNED:(ack?MqLabPublishOutcome.CONFIRMED:MqLabPublishOutcome.NACKED);
        } catch (TimeoutException | IOException uncertain) { return MqLabPublishOutcome.UNKNOWN; }
        finally { channel.removeReturnListener(listener); }
    }
    public static MqLabMessage decode(GetResponse delivery) {
        return decode(delivery.getProps(),delivery.getBody());
    }
    public static MqLabMessage decode(AMQP.BasicProperties properties,byte[] body) {
        var message=JSON.readValue(body,MqLabMessage.class);
        if (!"application/json".equals(properties.getContentType()) || !message.eventId().equals(properties.getMessageId()))
            throw new IllegalArgumentException("Invalid lab envelope");
        return message;
    }
    public static GetResponse receive(Channel channel,String queue) throws Exception {
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        do {
            var delivery=channel.basicGet(queue,false);if(delivery!=null)return delivery;
            Thread.sleep(25);
        } while(System.nanoTime()<deadline);
        throw new IllegalStateException("Lab delivery timed out");
    }
    public static void ack(Channel channel,String queue,GetResponse delivery) throws Exception {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("ACK before commit");
        channel.basicAck(delivery.getEnvelope().getDeliveryTag(),false);
        channel.queueDeclarePassive(queue); // Protocol barrier: prior ACK processed.
    }
}
