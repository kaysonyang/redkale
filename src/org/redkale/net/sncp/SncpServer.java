/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redkale.net.sncp;

import java.nio.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.redkale.convert.bson.*;
import org.redkale.net.*;
import org.redkale.util.*;
import org.redkale.watch.*;

/**
 * Service Node Communicate Protocol
 *
 * <p> 详情见: http://www.redkale.org
 * @author zhangjx
 */
public final class SncpServer extends Server {

    public SncpServer() {
        this(System.currentTimeMillis(), null);
    }

    public SncpServer(long serverStartTime, final WatchFactory watch) {
        super(serverStartTime, "TCP", new SncpPrepareServlet(), watch);
    }

    @Override
    public void init(AnyValue config) throws Exception {
        super.init(config);
    }

    public void addService(ServiceWrapper entry) {
        ((SncpPrepareServlet) this.prepare).addSncpServlet(new SncpDynServlet(BsonFactory.root().getConvert(), entry.getName(), entry.getType(), entry.getService(), entry.getConf()));
    }

    public List<SncpServlet> getSncpServlets() {
        return ((SncpPrepareServlet) this.prepare).getSncpServlets();
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Context createContext() {
        final int port = this.address.getPort();
        AtomicLong createBufferCounter = watch == null ? new AtomicLong() : watch.createWatchNumber("SNCP_" + port + ".Buffer.creatCounter");
        AtomicLong cycleBufferCounter = watch == null ? new AtomicLong() : watch.createWatchNumber("SNCP_" + port + ".Buffer.cycleCounter");
        final int rcapacity = Math.max(this.bufferCapacity, 4 * 1024);
        ObjectPool<ByteBuffer> bufferPool = new ObjectPool<>(createBufferCounter, cycleBufferCounter, this.bufferPoolSize,
                (Object... params) -> ByteBuffer.allocateDirect(rcapacity), null, (e) -> {
                    if (e == null || e.isReadOnly() || e.capacity() != rcapacity) return false;
                    e.clear();
                    return true;
                });
        AtomicLong createResponseCounter = watch == null ? new AtomicLong() : watch.createWatchNumber("SNCP_" + port + ".Response.creatCounter");
        AtomicLong cycleResponseCounter = watch == null ? new AtomicLong() : watch.createWatchNumber("SNCP_" + port + ".Response.cycleCounter");
        ObjectPool<Response> responsePool = SncpResponse.createPool(createResponseCounter, cycleResponseCounter, this.responsePoolSize, null);
        Context sncpcontext = new Context(this.serverStartTime, this.logger, executor, rcapacity, bufferPool, responsePool,
                this.maxbody, this.charset, this.address, this.prepare, this.watch, this.readTimeoutSecond, this.writeTimeoutSecond);
        responsePool.setCreator((Object... params) -> new SncpResponse(sncpcontext, new SncpRequest(sncpcontext)));
        return sncpcontext;
    }

}
