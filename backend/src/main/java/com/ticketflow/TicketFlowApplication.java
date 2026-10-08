package com.ticketflow;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
@SpringBootApplication
public class TicketFlowApplication {
 public static void main(String[] args) throws Exception {
  var context=SpringApplication.run(TicketFlowApplication.class,args);
  String operation=context.getEnvironment().getProperty("ticketflow.async.operation");
  if(operation!=null) {
   try {
    var env=context.getEnvironment();
    Object result=context.getBean(com.ticketflow.service.AsyncOperationsService.class).execute(operation,env.getProperty("ticketflow.async.operation-session",Long.class,0L),env.getProperty("ticketflow.async.operation-event",""));
    System.out.println("ASYNC_OPERATION_RESULT "+context.getBean(tools.jackson.databind.json.JsonMapper.class).writeValueAsString(result));
   } finally {context.close();}
  }
 }
}
