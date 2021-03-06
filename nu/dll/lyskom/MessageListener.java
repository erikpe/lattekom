/**! -*- Mode: Java; c-basic-offset: 4 -*-
 *
 * Copyright (c) 1999 by Rasmus Sten <rasmus@sno.pp.se>
 *
 */
package nu.dll.lyskom;

import java.net.ProtocolException;
import java.util.List;
import java.util.LinkedList;
import java.util.Map;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Arrays;
import java.io.*;

/**
 * This class implements the reader thread.
 * 
 * It uses the sessions KomTokenReader to parse any incoming data, and then
 * passes the parsed result to registered RPC and Asynch listeners.
 * 
 */
class MessageListener implements Runnable {

    Map<Integer, Integer> streamReceivers = new HashMap<Integer, Integer>();

    HollerithStream pendingStream = null;

    Exception exception = null;
    List<RpcReplyReceiver> rpcReceivers = new LinkedList<RpcReplyReceiver>();
    List<AsynchMessageReceiver> asynchReceivers = new LinkedList<AsynchMessageReceiver>();
    Thread thread = null;
    Session session;
    boolean disconnect = false;
    boolean disconnected = false;

    boolean asynch = false;

    public MessageListener(Session session) {
        this.session = session;
    }

    static int threadCount = 0;

    public void setAsynch(boolean wantAsynch) {
        if (wantAsynch) {
            if (!asynch) {
                asynch = true;
                thread = new Thread(this, "MessageListener-" + threadCount++);
                thread.setDaemon(true);
                thread.start();
            }
        } else {
            // TODO: change from synchronous to asynch mode and vice versa?

            // this
            asynch = false;
        }
    }

    public Thread getThread() {
        return thread;
    }

    public void disconnect() throws IOException {
        disconnect = true;
        session.getKomTokenReader().close();
    }

    protected void addHollerithStreamReceiver(int id, int limit) {
        synchronized (streamReceivers) {
            streamReceivers.put(new Integer(id), new Integer(limit));
        }
    }

    private boolean waitingForStream() {
        synchronized (streamReceivers) {
            return streamReceivers.size() > 0;
        }
    }

    private boolean waitingForStream(int rpcId) {
        synchronized (streamReceivers) {
            return streamReceivers.containsKey(new Integer(rpcId));
        }
    }

    private int hollerithLimitForStreamReceiver(int rpcId) {
        synchronized (streamReceivers) {
            return ((Integer) streamReceivers.get(new Integer(rpcId)))
                    .intValue();
        }
    }

    private void purgeStreamReceiver(int rpcId) {
        synchronized (streamReceivers) {
            streamReceivers.remove(new Integer(rpcId));
        }
    }

    protected Exception getException() {
        return exception;
    }

    // cannot throw exceptions from here, use callback error handling?
    public void run() {

        while (asynch && !disconnect) {
            KomToken[] row = {};
            Throwable readError = null;
            boolean rowCompleted = false;
            KomTokenReader reader = session.getKomTokenReader();
            boolean isRpcReply = false, isAsynchMessage = false, good = false;
            int id = 0;
            try {
                if (pendingStream != null) {
                    synchronized (pendingStream) {
                        while (!pendingStream.isExhausted()) {
                            Debug.println("Blocking further reads until the pending HollerithStream is exhausted...");
                            pendingStream.wait(5000);
                        }
                    }
                    pendingStream = null;
                }
                LinkedList<KomToken> tokens = new LinkedList<KomToken>();
                KomToken first = reader.readToken();
                tokens.add(first);
                byte[] descriptor = first.getContents();
                isRpcReply = descriptor[0] == '=' || descriptor[0] == '%';
                isAsynchMessage = descriptor[0] == ':';
                if (isRpcReply) {
                    if (descriptor[0] == '%' && descriptor[1] == '%') {
                        while (!reader.lastByteWasEol) {
                            tokens.add(reader.readToken());
                        }
                        throw new KomProtocolException(tokens.toString());
                    }

                    good = descriptor[0] == '=';
                    try {
                        id = Integer.parseInt(new String(descriptor, 1,
                                descriptor.length - 1));
                    } catch (NumberFormatException ex1) {
                        throw new KomProtocolException("Bad RPC ID: "
                                + ex1.getMessage());
                    }
                    if (waitingForStream()) {
                        if (waitingForStream(id)) {
                            int limit = hollerithLimitForStreamReceiver(id);
                            KomToken token = null;
                            boolean stopReading = false;
                            while (!reader.lastByteWasEol && !stopReading) {
                                token = reader.readToken(limit);
                                tokens.add(token);
                                if (token instanceof HollerithStream)
                                    stopReading = true;
                                if (Debug.ENABLED)
                                    Debug.println("Read token: " + token);
                            }
                            purgeStreamReceiver(id);
                            if (token instanceof HollerithStream) {
                                pendingStream = (HollerithStream) token;
                                Debug.println("HollerithStream received.");
                            } else {
                                Debug.println("HollerithStream NOT received.");
                            }
                            rowCompleted = true;
                        }
                    }
                }

                // asynch message data read in here as well.
                if (!rowCompleted) {
                    while (!reader.lastByteWasEol) {
                        tokens.add(reader.readToken());
                    }
                }

                row = new KomToken[tokens.size()];
                Iterator<KomToken> ti = tokens.iterator();
                for (int i = 0; i < tokens.size(); i++) {
                    row[i] = (KomToken) ti.next();
                }
            } catch (ProtocolException ex) {
                Debug.println("ProtocolException: " + ex.getClass().getName()
                        + ": " + ex.getMessage());
                exception = (Exception) (readError = ex);

            } catch (IOException ex) {
                Debug.println("IOException: " + ex.getClass().getName() + ": "
                        + ex.getMessage());
                exception = (Exception) (readError = ex);
            } catch (Exception ex) {
                ex.printStackTrace();
                exception = (Exception) (readError = ex);
                disconnect = true;
            }
            if (readError != null) {

                try {
                    session.connection.close();
                } catch (IOException ex1) {
                } catch (NullPointerException ex2) {
                }

                // sends a null to all receivers to notify about the
                // disconnection
                // this is not very clean, we should probably extend the
                // interface
                // or add an interface with a callback like
                // listenerException(Exception)
                // for notifying about fatal listener errors.
                for (Iterator<RpcReplyReceiver> i = rpcReceivers.iterator(); i
                        .hasNext();)
                    ((RpcReplyReceiver) i.next()).rpcReply(null);

                disconnected = true;
                if (disconnect) {
                    if (Debug.ENABLED)
                        Debug.println("Disconnected (" + readError + ").");

                    continue;
                } else {
                    disconnected = true;                	
                    //throw new RuntimeException(
                    //        "Fatal read error occured, read thread exiting",
                    //        readError);
                }

            }
            if (row.length == 0) {
                Debug.println("Got: Empty row, skipping");
                continue;
            }
            if (isRpcReply) {
                KomToken[] params = new KomToken[row.length - 1];
                System.arraycopy(row, 1, params, 0, params.length);

                // notify listeners...
                synchronized (rpcReceivers) {
                    for (Iterator<RpcReplyReceiver> i = rpcReceivers.iterator(); i
                            .hasNext();)
                        ((RpcReplyReceiver) i.next()).rpcReply(new RpcReply(
                                good, id, params));
                }
            } else if (isAsynchMessage) { // Asynch message
                synchronized (asynchReceivers) {
                    for (Iterator<AsynchMessageReceiver> i = asynchReceivers
                            .iterator(); i.hasNext();) {
                        AsynchMessageReceiver rcvr = (AsynchMessageReceiver) i
                                .next();
                        if (Debug.ENABLED) {
                            Debug.println("dispatching asynch message {"
                                    + Arrays.asList(row) + "} to " + rcvr);
                        }
                        rcvr.asynchMessage(new AsynchMessage(row));
                    }
                }
            }
        }
        Debug.println("MessageListener.run() finishing");
    }

    protected boolean isConnected() {
        return !disconnected;
    }

    /** add/remove event receivers **/
    public boolean removeRpcReplyReceiver(RpcReplyReceiver r) {
        synchronized (rpcReceivers) {
            return rpcReceivers.remove(r);
        }
    }

    public void addRpcReplyReceiver(RpcReplyReceiver r) {
        synchronized (rpcReceivers) {
            rpcReceivers.add(r);
        }
    }

    public void addAsynchMessageReceiver(AsynchMessageReceiver a) {
        synchronized (asynchReceivers) {
            asynchReceivers.add(a);
        }
    }

    public boolean removeAsynchMessageReceiver(AsynchMessageReceiver a) {
        synchronized (asynchReceivers) {
            return asynchReceivers.remove(a);
        }
    }

}
