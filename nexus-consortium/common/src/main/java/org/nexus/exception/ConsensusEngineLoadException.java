package org.nexus.exception;

public class ConsensusEngineLoadException extends Exception{
    public ConsensusEngineLoadException() {
    }

    public ConsensusEngineLoadException(String message) {
        super(message);
    }

    public ConsensusEngineLoadException(String message, Throwable cause) {
        super(message, cause);
    }

    public ConsensusEngineLoadException(Throwable cause) {
        super(cause);
    }
}
