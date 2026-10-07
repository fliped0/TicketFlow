package com.ticketflow.config;
import com.rabbitmq.client.*;
import com.ticketflow.model.entity.*;
import com.ticketflow.service.AsyncOrderService;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;
@Component
public class AsyncBrokerGateway {
    private final AsyncProperties settings;private final JsonMapper json;private final AsyncOrderService service;
    private Connection connection;private boolean declared;private final List<Channel> consumers=new ArrayList<>();
    public AsyncBrokerGateway(AsyncProperties settings,JsonMapper json,AsyncOrderService service) {this.settings=settings;this.json=json;this.service=service;}
    public String exchange() {return settings.brokerPrefix()+".x";}
    public String queue() {return settings.brokerPrefix()+".work";}
    public String deadQueue() {return settings.brokerPrefix()+".dead";}
    private static void outside() {if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Broker in database transaction");}
    private synchronized Connection connected() throws Exception {
        outside();if(connection!=null && connection.isOpen())return connection;
        var f=new ConnectionFactory();f.setHost(settings.host());f.setPort(settings.port());f.setUsername(settings.username());f.setPassword(settings.password());f.setVirtualHost(settings.vhost());
        f.setAutomaticRecoveryEnabled(false);f.setTopologyRecoveryEnabled(false);f.setConnectionTimeout(3000);f.setHandshakeTimeout(3000);f.setRequestedHeartbeat(10);
        connection=f.newConnection("ticketflow-async");declared=false;consumers.clear();return connection;
    }
    public synchronized void topology()throws Exception {
        connected();if(declared)return;
        try(var ch=connected().createChannel()) {
            ch.exchangeDeclare(exchange(),"direct",true);ch.exchangeDeclare(settings.brokerPrefix()+".dead.x","direct",true);
            ch.queueDeclare(deadQueue(),true,false,false,Map.of("x-queue-type","quorum","x-max-length",1000,"x-overflow","reject-publish"));
            ch.queueBind(deadQueue(),settings.brokerPrefix()+".dead.x","dead");
            ch.queueDeclare(queue(),true,false,false,Map.of("x-queue-type","quorum","x-max-length",1000,"x-overflow","reject-publish","x-dead-letter-exchange",settings.brokerPrefix()+".dead.x","x-dead-letter-routing-key","dead"));
            ch.queueBind(queue(),exchange(),"create");
            declared=true;
        }
    }
    public boolean publish(OutboxEvent event)throws Exception {
        outside();try(var ch=connected().createChannel()) {
            var returned=new AtomicBoolean();ch.addReturnListener((code,text,exchange,key,properties,body)->returned.set(true));ch.confirmSelect();
            ch.basicPublish(exchange(),"create",true,new AMQP.BasicProperties.Builder().contentType("application/json").messageId(event.id()).deliveryMode(2).build(),event.payload().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return ch.waitForConfirms(2000) && !returned.get();
        }
    }
    public synchronized void startConsumers()throws Exception {
        outside();connected();consumers.removeIf(ch->!ch.isOpen());
        while(consumers.size()<4) {
            var ch=connection.createChannel();ch.basicQos(8);consumers.add(ch);
            ch.basicConsume(queue(),false,(tag,delivery)->{
                AsyncDisposition result;
                AsyncMessage message=null;
                try {
                    message=json.readValue(delivery.getBody(),AsyncMessage.class);
                    if(!message.eventId().equals(delivery.getProperties().getMessageId()) || !"application/json".equals(delivery.getProperties().getContentType()))message=null;
                } catch(RuntimeException malformed) {message=null;}
                if(message==null){org.slf4j.LoggerFactory.getLogger(getClass()).warn("async_invalid_envelope");result=AsyncDisposition.DEAD_LETTER;}
                else {
                    try {result=service.consume(message);}
                    catch(RuntimeException unavailable) {result=AsyncDisposition.STOP;}
                }
                if(result==AsyncDisposition.ACK)ch.basicAck(delivery.getEnvelope().getDeliveryTag(),false);
                else if(result==AsyncDisposition.DEAD_LETTER)ch.basicNack(delivery.getEnvelope().getDeliveryTag(),false,false);
                else ch.abort();
            },tag->{});
        }
    }
    @PreDestroy public synchronized void close()throws Exception {if(connection!=null && connection.isOpen())connection.close();consumers.clear();}
}
