package app.gtdial.lab;

/** Stable BLE connection facts, kept separate from the most recent operation result. */
final class ConnectionSnapshot {
    private final boolean ready;
    private final boolean connected;
    private final String connectionMessage;
    private final String operationMessage;

    private ConnectionSnapshot(boolean connected,boolean ready, String connectionMessage, String operationMessage) {
        this.connected=connected;
        this.ready = ready;
        this.connectionMessage = connectionMessage;
        this.operationMessage = operationMessage;
    }

    static ConnectionSnapshot ready(String message) {
        return new ConnectionSnapshot(true,true, message, null);
    }

    static ConnectionSnapshot notReady(String message) {
        return new ConnectionSnapshot(false,false, message, null);
    }

    boolean isReady() { return ready; }
    boolean isConnected(){return connected;}
    static ConnectionSnapshot connected(String message){return new ConnectionSnapshot(true,false,message,null);}
    ConnectionSnapshot withReady(boolean value){return new ConnectionSnapshot(connected,connected&&value,connectionMessage,operationMessage);}
    String connectionMessage() { return connectionMessage; }
    String operationMessage() { return operationMessage; }

    ConnectionSnapshot withOperationMessage(String message) {
        return new ConnectionSnapshot(connected,ready, connectionMessage, message);
    }

    ConnectionSnapshot disconnected(String message) {
        return notReady(message);
    }
}
