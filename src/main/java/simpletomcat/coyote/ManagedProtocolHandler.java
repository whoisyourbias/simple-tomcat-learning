package simpletomcat.coyote;

import java.io.IOException;
import java.time.Duration;

public interface ManagedProtocolHandler extends ProtocolHandler {
    void stopAccepting() throws IOException;

    boolean awaitTermination(Duration timeout) throws InterruptedException;
}
