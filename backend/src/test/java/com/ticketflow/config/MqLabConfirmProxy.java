package com.ticketflow.config;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;

/** Real AMQP transport fault: drops one server Basic.Ack, forwards all other frames. */
public final class MqLabConfirmProxy implements AutoCloseable {
    private final ServerSocket listener=new ServerSocket(0,1,InetAddress.getLoopbackAddress());
    private final ExecutorService workers=Executors.newFixedThreadPool(3);
    private final CountDownLatch dropped=new CountDownLatch(1);
    private volatile Socket client, upstream;
    public MqLabConfirmProxy() throws IOException {
        workers.submit(()->{
            try {
                client=listener.accept(); upstream=new Socket("127.0.0.1",15673);
                workers.submit(()->copy(client,upstream));
                workers.submit(this::filter);
            } catch(IOException stopped) { /* Closing the proxy ends accept. */ }
        });
    }
    public int port() { return listener.getLocalPort(); }
    public boolean droppedAck() throws InterruptedException { return dropped.await(2,TimeUnit.SECONDS); }
    private void copy(Socket from,Socket to) {
        try { from.getInputStream().transferTo(to.getOutputStream()); }
        catch(IOException disconnected) { /* Expected when the test closes the connection. */ }
    }
    private void filter() {
        try {
            var input=new DataInputStream(upstream.getInputStream()); var output=new DataOutputStream(client.getOutputStream());
            while (!Thread.currentThread().isInterrupted()) {
                int type=input.readUnsignedByte(), channel=input.readUnsignedShort(), length=input.readInt();
                if(length<0 || length>16*1024*1024) throw new IOException("Invalid AMQP frame size");
                byte[] payload=input.readNBytes(length); int end=input.readUnsignedByte();
                if(payload.length!=length || end!=206) throw new IOException("Invalid AMQP frame");
                boolean ack=type==1 && length>=4 && payload[0]==0 && payload[1]==60 && payload[2]==0 && payload[3]==80;
                if(ack && dropped.getCount()>0) { dropped.countDown(); continue; }
                output.writeByte(type);output.writeShort(channel);output.writeInt(length);output.write(payload);output.writeByte(end);output.flush();
            }
        } catch(IOException disconnected) { /* Expected after fault injection or connection close. */ }
    }
    @Override public void close() throws IOException {
        listener.close(); if(client!=null)client.close();if(upstream!=null)upstream.close(); workers.shutdownNow();
    }
}
