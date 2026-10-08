package com.ticketflow;

import com.ticketflow.config.*;
import com.ticketflow.mapper.*;
import com.ticketflow.model.dto.CreateOrderDTO;
import com.ticketflow.model.entity.AsyncSnapshot;
import com.ticketflow.model.vo.PurchaseOutcome;
import com.ticketflow.service.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Test-only subprocess; every fault hook is excluded from the application jar. */
public class AsyncCrashWorker {
    static String mode,target,key;
    static long user,tier,session;
    static Path folder;
    static ConfigurableApplicationContext context;
    static void window() throws Exception {
        Files.writeString(folder.resolve("window"),mode);
        while(true)Thread.sleep(1000);
    }
    public static void main(String[] args) throws Exception {
        if(!"ticketflow_test".equals(System.getenv("TF_DB_NAME")) || !"tf_test".equals(System.getenv("TF_DB_USER")))throw new IllegalStateException("Test database required");
        mode=args[0];folder=Path.of(args[1]);target=args[2];session=Long.parseLong(args[3]);user=Long.parseLong(args[4]);tier=Long.parseLong(args[5]);key=args[6];
        context=SpringApplication.run(new Class<?>[]{TicketFlowApplication.class,Faults.class},java.util.Arrays.copyOfRange(args,7,args.length));
        // Establish the cold client before taking the DB timestamp used for clock-skew checking.
        context.getBean(org.springframework.data.redis.core.StringRedisTemplate.class).execute((org.springframework.data.redis.core.RedisCallback<String>)connection->connection.ping());
        Files.writeString(folder.resolve("ready"),mode);
        if(mode.startsWith("admission"))context.getBean(PurchaseRequestService.class).submit(user,key,new CreateOrderDTO(Long.toString(tier),1));
        else if(mode.startsWith("rebuild"))context.getBean(AsyncRecoveryService.class).rebuild(session);
        else {var broker=context.getBean(AsyncBrokerGateway.class);broker.topology();broker.startConsumers();}
        while(true)Thread.sleep(1000);
    }
    @TestConfiguration(proxyBeanMethods=false)
    public static class Faults {
        @Bean static BeanPostProcessor asyncFaults() {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean,String name) {
                    if(bean instanceof OutboxMapper original && mode.equals("admission-before")) {
                        var proxy=spy((OutboxMapper)org.springframework.test.util.AopTestUtils.getUltimateTargetObject(original));
                        doAnswer(call->{Object result=call.callRealMethod();if("ACTIVATE".equals(call.getArgument(2)))window();return result;})
                            .when(proxy).insert(anyString(),anyString(),anyString(),anyString(),anyLong(),anyLong(),anyLong(),nullable(Long.class),anyString(),any(LocalDateTime.class));return proxy;
                    }
                    if(bean instanceof AsyncRequestMapper original && mode.equals("order-before")) {
                        var proxy=spy((AsyncRequestMapper)org.springframework.test.util.AopTestUtils.getUltimateTargetObject(original));
                        doAnswer(call->{Object result=call.callRealMethod();if(target.equals(((com.ticketflow.model.entity.AsyncRequest)call.getArgument(0)).id()))window();return result;})
                            .when(proxy).complete(any(),notNull(),eq("OK"),any());return proxy;
                    }
                    if(bean instanceof AsyncTransactions original && (mode.equals("admission-after") || mode.equals("order-after"))) {
                        var proxy=spy(original);
                        doAnswer(call->{
                            Object result=call.callRealMethod();
                            if(mode.equals("admission-after") && result instanceof PurchaseOutcome outcome && outcome.httpStatus()==202)window();
                            if(mode.equals("order-after") && context!=null && "SUCCEEDED".equals(context.getBean(AsyncRequestMapper.class).get(target,false).state()))window();
                            return result;
                        }).when(proxy).execute(any());return proxy;
                    }
                    if(bean instanceof AsyncRedisGateway original && mode.startsWith("rebuild")) {
                        var proxy=spy(original);
                        if(mode.equals("rebuild-paused"))doAnswer(call->{window();return call.callRealMethod();}).when(proxy).freeze(anyLong(),anyLong());
                        if(mode.equals("rebuild-frozen"))doAnswer(call->{Object r=call.callRealMethod();window();return r;}).when(proxy).freeze(anyLong(),anyLong());
                        if(mode.equals("rebuild-snapshot"))doAnswer(call->{window();return call.callRealMethod();}).when(proxy).restore(any(AsyncSnapshot.class));
                        if(mode.equals("rebuild-restored"))doAnswer(call->{call.callRealMethod();window();return null;}).when(proxy).restore(any(AsyncSnapshot.class));
                        if(mode.equals("rebuild-db-ready"))doAnswer(call->{window();return call.callRealMethod();}).when(proxy).ready(anyLong(),anyLong());
                        if(mode.equals("rebuild-ready"))doAnswer(call->{call.callRealMethod();window();return null;}).when(proxy).ready(anyLong(),anyLong());
                        return proxy;
                    }
                    return bean;
                }
            };
        }
    }
}
