package edu.mit.csail.jasongao.roadrunner;

public class Globals {
	/** Adhoc parameters */
	static final int ADHOC_MAX_PACKET_SIZE = 32768; // bytes
	static final int ADHOC_ANNOUNCE_PORT = 4200;
	static final long ADHOC_ANNOUNCE_PERIOD = 2000 * 1;
	static boolean RELAY_ENABLED = false;

	/** Adhoc UDP + TCP token transfers */
	// static final boolean ADHOC_UDP_ONLY = false;
	// static final boolean ADHOC_UDP_ONLY = true;
	// static final String ADHOC_IFACE_NAME = "eth0";
	// final static public String ADHOC_BROADCAST_ADDRESS = "192.168.42.255";

	/** DSRC / Adhoc UDP-only switch */
	static final boolean ADHOC_UDP_ONLY = true;
	static final String ADHOC_IFACE_NAME = "rndis0"; // tethered DSRC radio
	final static public String ADHOC_BROADCAST_ADDRESS = "192.168.42.130";

	/** Cloud parameters */
	static final String CLOUD_HOST = "128.30.87.195";
	static final int CLOUD_PORT = 50000;
	static final int CLOUD_SOCKET_TIMEOUT = 3000;
	static final byte[] CLOUD_PUBLIC_KEY = null; // TODO
	static final byte[] MY_PRIVATE_KEY = null; // TODO

	/** Relay logic */
	static int REQUEST_PENALTY_VALID_PERIOD = 600000; // 10 min
	static int REQUEST_PENALTY_CHECK_PERIOD = 60000; // 1 min
	static int REQUEST_DEADLINE_CHECK_PERIOD = 500;
	static int REQUEST_DIRECT_PUT_DEADLINE_FROM_NOW = 10 * 60 * 1000; // TODO
	static int REQUEST_DIRECT_GET_DEADLINE_FROM_NOW = 10 * 60 * 1000; // TODO
	static int REQUEST_RELAY_GET_DEADLINE_FROM_NOW = 3000; // deprecated

	static final int LAST_DATA_ACTIVITY_THRESHOLD = 8000;

	/** Automated experiment progression */
	static long EXPT_START_DELAY = 1 * 15 * 1000;
	static long RESET_SERVER_DELAY = 1 * 15 * 1000;
	static long EXPT_LENGTH = 10 * 60 * 1000; // TODO now set in MainActivity

	/** Times */
	/* DEBUG */
	// static final boolean DEBUG = true;
	// static final long START_TIME_1 = 1336085662L * 1000L + 30000;

	static final boolean DEBUG = false;
	static final long START_TIME_1 = 1336158480L * 1000L - 3600 * 1000; // GMT-4

	static final long START_TIME_2 = 1335544500L * 1000L; // GMT-4

	/** Navigation */
	static final boolean NAV_SPEECH = false;
	static final boolean NAV_REQUESTS = false;
	static final boolean SUPER_DENSE_REQUESTS = true;
}
