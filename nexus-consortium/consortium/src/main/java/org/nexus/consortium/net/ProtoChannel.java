package org.nexus.consortium.net;

import lombok.extern.slf4j.Slf4j;
import org.nexus.consortium.proto.Message;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;


// communicating channel with peer
@Slf4j
public class ProtoChannel implements Channel {
    private boolean closed;
    private PeerImpl remote;
    private ChannelOut out;
    private boolean pinged;
    private List<ChannelListener> listeners = new ArrayList<>();

    public ProtoChannel() {
    }

    public void setOut(ChannelOut out) {
        this.out = out;
    }

    @Override
    public void message(Message message) {
        if(closed) return;
        handlePing(message);
        if(listeners == null) return;
        for(ChannelListener listener: listeners){
            if(closed) return;
            listener.onMessage(message, this);
        }
    }

    private void handlePing(Message message) {
        if (pinged) return;
        Optional<PeerImpl> o = PeerImpl.parse(message.getRemotePeer());
        if (!o.isPresent()) {
            close();
            return;
        }
        pinged = true;
        remote = o.get();
        if(listeners == null) return;
        for(ChannelListener listener: listeners){
            if(closed) return;
            listener.onConnect(remote, this);
        }
    }

    @Override
    public void error(Throwable throwable) {
        if(closed || listeners == null) return;
        for(ChannelListener listener: listeners){
            if(closed) return;
            listener.onError(throwable, this);
        }
    }


    public void close() {
        if(closed) return;
        closed = true;
        if(listeners == null) return;
        listeners.forEach(l -> l.onClose(this));
        listeners = null;
        try{
            out.close();
        }catch (Exception ignore){}
    }

    public void write(Message message) {
        if (closed) {
            log.error("the channel is closed");
            return;
        }
        try {
            out.write(message);
        } catch (Throwable e) {
            e.printStackTrace();
            log.error(e.getMessage());
            if(listeners == null) return;
            error(e);
        }
    }

    public Optional<PeerImpl> getRemote() {
        return Optional.ofNullable(remote);
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void addListener(ChannelListener... listeners) {
        if(listeners == null) return;
        this.listeners.addAll(Arrays.asList(listeners));
    }
}
