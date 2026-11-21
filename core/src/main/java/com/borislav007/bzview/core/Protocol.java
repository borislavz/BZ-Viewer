package com.borislav007.bzview.core;

/** Constants shared by the camera and viewer apps. */
public final class Protocol {

    private Protocol() {
    }

    /** TCP port the camera phone listens on. */
    public static final int DEFAULT_PORT = 8765;

    /** Multipart boundary used for the MJPEG stream. */
    public static final String BOUNDARY = "bzviewframe";

    /** Scheme of the pairing address, e.g. bzview://192.168.1.20:8765/ab12cd34 */
    public static final String SCHEME = "bzview";

    public static final String PATH_STREAM = "/stream";
    public static final String PATH_COMMAND = "/cmd";

    public static final String CMD_STATUS = "status";
    /** Select a camera by id: /cmd?...&name=select&arg=ID */
    public static final String CMD_SELECT_CAMERA = "select";
    public static final String CMD_TORCH = "torch";

    public static final String ARG_TOGGLE = "toggle";
}
