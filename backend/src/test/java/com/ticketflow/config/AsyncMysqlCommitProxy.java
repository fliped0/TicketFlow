package com.ticketflow.config;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Loopback, plaintext test JDBC only. Never records authentication packets or query contents. */
public final class AsyncMysqlCommitProxy implements AutoCloseable {
    private final ServerSocket server=new ServerSocket(0,20,InetAddress.getLoopbackAddress());
    private final ExecutorService workers=Executors.newCachedThreadPool();
    private final java.util.List<Socket> sockets=new CopyOnWriteArrayList<>();
    private final AtomicBoolean used=new AtomicBoolean();
    private final CountDownLatch hit=new CountDownLatch(1);
    private final boolean afterCommit;
    public AsyncMysqlCommitProxy(boolean afterCommit) throws IOException {
        this.afterCommit=afterCommit;
        workers.submit(()->{while(!server.isClosed())try {connect(server.accept());}catch(IOException closed){break;}});
    }
    public int port(){return server.getLocalPort();}
    public boolean awaitFault()throws InterruptedException{return hit.await(20,TimeUnit.SECONDS);}
    private static byte[] packet(InputStream in)throws IOException {
        byte[] header=in.readNBytes(4);if(header.length!=4)throw new EOFException();
        int length=(header[0]&255)|((header[1]&255)<<8)|((header[2]&255)<<16);
        byte[] body=in.readNBytes(length);if(body.length!=length)throw new EOFException();
        byte[] all=new byte[length+4];System.arraycopy(header,0,all,0,4);System.arraycopy(body,0,all,4,length);return all;
    }
    private void connect(Socket client)throws IOException {
        var upstream=new Socket("127.0.0.1",3306);client.setTcpNoDelay(true);upstream.setTcpNoDelay(true);sockets.add(client);sockets.add(upstream);
        var armed=new AtomicBoolean();var pendingAck=new AtomicBoolean();
        workers.submit(()->{
            try {
                while(!client.isClosed()) {
                    byte[] bytes=packet(client.getInputStream());
                    if(bytes.length>5 && bytes[4]==3) {
                        String query=new String(bytes,5,bytes.length-5,StandardCharsets.UTF_8).trim().toLowerCase(java.util.Locale.ROOT);
                        if(query.startsWith("insert into tf_async_request"))armed.set(true);
                        if(query.equals("commit") && armed.get() && used.compareAndSet(false,true)) {
                            if(!afterCommit){upstream.close();client.close();hit.countDown();return;}
                            pendingAck.set(true);
                        }
                    }
                    upstream.getOutputStream().write(bytes);upstream.getOutputStream().flush();
                }
            } catch(IOException closed) {closePair(client,upstream);}
        });
        workers.submit(()->{
            try {
                while(!upstream.isClosed()) {
                    byte[] bytes=packet(upstream.getInputStream());
                    if(pendingAck.getAndSet(false)) {
                        if(bytes.length>4 && bytes[4]==0){upstream.close();client.close();hit.countDown();return;}
                        throw new IOException("Expected COMMIT OK packet");
                    }
                    client.getOutputStream().write(bytes);client.getOutputStream().flush();
                }
            }catch(IOException closed){closePair(client,upstream);}
        });
    }
    private static void closePair(Socket one,Socket two){try{one.close();}catch(IOException ignored){}try{two.close();}catch(IOException ignored){}}
    @Override public void close()throws IOException{server.close();for(var socket:sockets)socket.close();workers.shutdownNow();}
}
