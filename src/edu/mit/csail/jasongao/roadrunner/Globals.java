package edu.mit.csail.jasongao.roadrunner;

public class Globals {
	/** Adhoc radio */
	// static final String ADHOC_IFACE_NAME = "rndis0"; // tethered DSRC radio
	static final String ADHOC_IFACE_NAME = "eth0";
	// final static public String ADHOC_BROADCAST_ADDRESS = "192.168.42.130";
	final static public String ADHOC_BROADCAST_ADDRESS = "192.168.42.255";
	static final int ADHOC_MAX_PACKET_SIZE = 32768; // bytes
	static final int ADHOC_PORT = 4200;
	static final long ADHOC_ANNOUNCE_PERIOD = 2000 * 1;

	/** Cloud parameters */
	static final String CLOUD_HOST = "128.30.87.195";
	// static final String CLOUD_HOST = "24.128.49.120";
	static final int CLOUD_PORT = 50000;
	static final int CLOUD_SOCKET_TIMEOUT = 5000;
	static final byte[] CLOUD_PUBLIC_KEY = null; // TODO
	static final byte[] MY_PRIVATE_KEY = null; // TODO
	// static final long CLOUD_HEARTBEAT_INTERVAL = 1000 * 60 * 3;

	/** Relay logic */
	static final int REQUEST_DEADLINE_CHECK_PERIOD = 500;
	static final int REQUEST_DIRECT_PUT_DEADLINE_FROM_NOW = 20000;
	static final int REQUEST_DIRECT_GET_DEADLINE_FROM_NOW = 10000;
	static final int REQUEST_RELAY_GET_DEADLINE_FROM_NOW = 1000;
	// static final int LINK_LIFETIME_THRESHOLD = 2000; // milliseconds
	// static final int LINK_DISTANCE_THRESHOLD = 50; // 50 meters
}
