package bolt;

import rx.Observable;

/**
 * Created by keen on 28/02/16.
 */
public interface Server extends Sender {

    /*
    Client connect
    Client disconnect
    Receive data
     */

    Observable<?> bind();


}
