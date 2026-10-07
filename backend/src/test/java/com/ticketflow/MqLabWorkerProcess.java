package com.ticketflow;

import com.ticketflow.config.MqLabBroker;
import com.ticketflow.mapper.MqLabMapper;
import com.ticketflow.service.MqLabService;
import java.nio.file.*;
import java.util.concurrent.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Child JVM killed by the lab at an explicit transaction/ACK boundary. */
public class MqLabWorkerProcess {
    public static void main(String[] args) throws Exception {
        if (args.length!=5 || !"ticketflow_test".equals(System.getenv("TF_DB_NAME"))
                || !"tf_test".equals(System.getenv("TF_DB_USER"))) throw new IllegalStateException("Dedicated test DB required");
        var source=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:3306/ticketflow_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
                "tf_test",System.getenv("TF_DB_PASSWORD"));
        var service=new MqLabService(new MqLabMapper(new JdbcTemplate(source),args[0]),new DataSourceTransactionManager(source));
        var broker=new MqLabBroker(System.getenv("TF_MQ_LAB_USER"),System.getenv("TF_MQ_LAB_PASSWORD"),args[1]);
        var gate=new CountDownLatch(1);
        Runnable hold=()->{
            try {
                Files.writeString(Path.of(args[4]),args[3]);
                gate.await(30,TimeUnit.SECONDS);
            } catch(Exception error){throw new IllegalStateException("Lab fault gate failed",error);}
            throw new IllegalStateException("Lab fault gate timed out");
        };
        try(var connection=broker.connect();var channel=connection.createChannel()) {
            channel.basicQos(1);
            var finished=new CountDownLatch(1);
            channel.basicConsume(args[2],false,(tag,delivery)->{
                try {
                    var message=MqLabBroker.decode(delivery.getProperties(),delivery.getBody());
                    if ("before-commit".equals(args[3])) service.apply(message,hold);
                    else if ("after-commit".equals(args[3])) { service.apply(message); hold.run(); }
                    else throw new IllegalArgumentException("Unknown fault boundary");
                    channel.basicAck(delivery.getEnvelope().getDeliveryTag(),false);
                } catch(Exception failure) { System.err.println("Lab child failed before ACK"); }
                finally { finished.countDown(); }
            },tag->finished.countDown());
            if (!finished.await(45,TimeUnit.SECONDS)) throw new IllegalStateException("Lab child timed out");
        }
    }
}
