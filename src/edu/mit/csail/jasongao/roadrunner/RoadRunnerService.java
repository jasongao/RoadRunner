package edu.mit.csail.jasongao.roadrunner;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.telephony.TelephonyManager;
import android.util.Log;

public class RoadRunnerService extends Service implements LocationListener {
	public static final String TAG = "RoadRunnerService";

	// Android system
	PowerManager.WakeLock wl = null;
	LocationManager lm;
	TelephonyManager tm;

	// Communication threads
	private AdhocPacketThread aat;
	private AdhocServerThread ast;

	/***********************************************
	 * RoadRunner state
	 ***********************************************/

	private boolean adhocEnabled = false;

	/**
	 * When was the cellular data link last active? It goes dormant after 10
	 * seconds of inactivity
	 */
	private long lastDataActivity = 0;

	private List<Region> regions;

	private Location mLoc;
	private String mRegion = "FREE";
	private long mId = -1000;

	/** Reservations we are using/will use. Map regionId to done ResRequest */
	private Map<String, ResRequest> reservationsInUse;

	/** Reservations we can give away. Will be sent to cloud eventually. */
	public Queue<ResRequest> offers;

	/** Pending GET RES_REQUESTS that can be sent to either cloud or to adhoc */
	private Queue<ResRequest> getsPending;

	/********
	 * Helpers
	 ********/

	public static Set<String> queueKeySet(Queue<ResRequest> q) {
		Set<String> keys = new HashSet<String>();

		for (Iterator<ResRequest> it = q.iterator(); it.hasNext();) {
			ResRequest req = it.next();
			keys.add(req.regionId);
		}

		return keys;
	}

	public static ResRequest queuePoll(Queue<ResRequest> q, String rid) {
		for (Iterator<ResRequest> it = q.iterator(); it.hasNext();) {
			ResRequest req = it.next();
			if (req.regionId.equals(rid)) {
				it.remove();
				return req;
			}
		}

		return null;
	}

	/***********************************************
	 * Handle messages from other components and threads
	 ***********************************************/
	protected final static int ADHOC_ANNOUNCE_RECV = 4;
	protected final static int ADHOC_PACKET_RECV = 4;

	/**
	 * Determine whether two vehicle's location fixes indicate that a WiFi TCP
	 * link can be attempted over the next Globals.LINK_LIFETIME_THRESHOLD secs
	 * 
	 * @param v1
	 *            Location of vehicle 1
	 * @param v2
	 *            Location of vehicle 2
	 * @return true is the link is viable, false if not
	 */
	private boolean linkIsViable(Location v1, AdhocPacket other) {
		Location v2 = other.getLocation();

		if (v1 == null || v2 == null) {
			log_nodisplay("Link viable debug: no GPS fix.");
			return true;
		}

		float distance = v1.distanceTo(v2);

		// Too far away (> 70m)
		if (distance > 70) {
			log_nodisplay(String.format(
					"Link not viable: %.1f meters apart. (>70)", distance));
			return false;
		}

		// Quite close together (< 20 m)
		if (v1.distanceTo(v2) < 20) {
			log_nodisplay(String.format(
					"Link viable: %.1f meters apart. (<20m)", distance));
			return false;
		}

		// Both stationary?
		if (v1.hasSpeed() && v1.getSpeed() < 2 && v2.hasSpeed()
				&& v2.getSpeed() < 2) {
			log_nodisplay(String.format(
					"Link viable: %.1f meters apart. (low speed)", distance));
			return true;
		}

		// One stationary and other moving towards it?
		if (v1.hasSpeed()
				&& v1.getSpeed() < 2
				&& v2.hasBearing()
				&& ((Math.abs(v2.bearingTo(v1) - v2.getBearing()) < 45) || (Math
						.abs(v2.bearingTo(v1) - v2.getBearing()) > 360 - 45))) {
			log_nodisplay(String.format(
					"Link viable: %.1f meters apart. (other approaching)",
					distance));
			return true;
		}
		if (v2.hasSpeed()
				&& v2.getSpeed() < 2
				&& v1.hasBearing()
				&& ((Math.abs(v1.bearingTo(v2) - v1.getBearing()) < 45) || (Math
						.abs(v1.bearingTo(v2) - v1.getBearing()) > 360 - 45))) {
			log_nodisplay(String.format(
					"Link viable: %.1f meters apart. (approaching other)",
					distance));
			return true;
		}

		// Both moving towards each other
		if (v1.distanceTo(v2) < 35
				&& v1.hasBearing()
				&& v2.hasBearing()
				&& (Math.abs(v1.bearingTo(v2) - v1.getBearing()) < 15 || Math
						.abs(v1.bearingTo(v2) - v1.getBearing()) > 360 - 15)
				&& (Math.abs(v2.bearingTo(v1) - v2.getBearing()) < 15 || Math
						.abs(v2.bearingTo(v1) - v2.getBearing()) > 360 - 15)) {
			log_nodisplay(String.format(
					"Link viable: %.1f meters apart. (mutual approach)",
					distance));
			return true;
		}

		// Moving together?
		if (v1.distanceTo(v2) < 35
				&& v1.hasBearing()
				&& v2.hasBearing()
				&& v1.hasSpeed()
				&& v2.hasSpeed()
				&& (Math.abs(v1.getBearing() - v2.getBearing()) < 15 || Math
						.abs(v1.getBearing() - v2.getBearing()) > 360 - 15)
				&& Math.abs(v1.getSpeed() - v2.getSpeed()) < 5) {
			log_nodisplay(String.format(
					"Link viable: %.1f meters apart. (moving together)",
					distance));
			return true;
		}

		log_nodisplay(String.format(
				"Link viable: %.1f meters apart. (moving apart)", distance));
		return false;
	}

	public final Handler myHandler = new Handler() {
		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
			case ADHOC_ANNOUNCE_RECV:
				if (!adhocEnabled) {
					break;
				}
				AdhocPacket other = (AdhocPacket) msg.obj;
				long now = getTime();
				log_nodisplay(String.format("Received UDP %s", other));

				if (other.triggerAnnounce) {
					adhocAnnounce(false);
				}

				if (!linkIsViable(mLoc, other)) {
					break;
				}

				for (Iterator<ResRequest> it = getsPending.iterator(); it
						.hasNext();) {
					ResRequest req = it.next();

					// try to get token from other vehicle?
					if (other.tokensOffered.contains(req.regionId)) {
						log(String.format("Other vehicle %d offers %s, GET %s",
								other.src, other.tokensOffered, req.regionId));
						it.remove(); // fix ConcurrentModificationException
						new ResRequestTask().execute(req, "192.168.42."
								+ other.src);
					}

					// try to relay through other vehicle?
					else if (other.dataActivity != TelephonyManager.DATA_ACTIVITY_DORMANT
							&& req.softDeadline < now) {
						log(String
								.format("Request soft deadline %d expired, relaying through vehicle %d to cloud: %s",
										req.softDeadline, other.src, req));
						getsPending.remove(req);
						new ResRequestTask().execute(req, "192.168.42."
								+ other.src);
					}
				}

				break;
			}
		}
	};

	/** Send an ResRequest to a TCP/IP endpoint (whether peer or cloud) */
	public class ResRequestTask extends AsyncTask<Object, Integer, ResRequest> {

		@Override
		protected ResRequest doInBackground(Object... params) {
			ResRequest req = (ResRequest) params[0];
			String mHost = (String) params[1];

			long startTime = getTime();

			Socket s = new Socket();
			try {
				s.connect(new InetSocketAddress(mHost, Globals.CLOUD_PORT),
						Globals.CLOUD_SOCKET_TIMEOUT);

				InputStream in = s.getInputStream();
				OutputStream out = s.getOutputStream();

				BufferedReader reader = new BufferedReader(
						new InputStreamReader(in));
				Writer writer = new OutputStreamWriter(out);

				String response;

				// Dispatch request correctly
				switch (req.type) {
				case ResRequest.RES_GET:
					writer.write(String.format("GET %d %s\r\n", mId,
							req.regionId));
					writer.flush();

					response = reader.readLine();
					log("Response: " + response);
					if ("GET 200 OK".equals(response)) {
						req.tokenString = reader.readLine();
						req.signature = reader.readLine();
						String[] parts = req.tokenString.split(" ");
						req.issued = Long.parseLong(parts[1]);
						req.expires = Long.parseLong(parts[2]);

						log_nodisplay(String
								.format("Received:\nTOKEN %s\nSIG %s\nISSUED: %d\nEXPIRES: %d",
										req.tokenString, req.signature,
										req.issued, req.expires));

						// TODO verify signature
						if (!req.tokenIsValid()) {
							// log("Token signature verification FAILED!");
							// failed to verify token, put back into pending q?
						}

						req.done = true;
					} else {
						log("GET request failed: " + response);
						req.done = false;
					}
					break;
				case ResRequest.RES_PUT:
					writer.write(String.format("PUT %d %s\r\n", mId,
							req.regionId));
					writer.flush();

					response = reader.readLine();
					log("Response: " + response);
					if ("PUT 200 OK".equals(response)) {
						req.done = true;
					} else {
						log("PUT request failed: " + response);
						req.done = false;
					}
					break;
				case ResRequest.DEBUG_RESET:
					writer.write(String.format("DEBUG-RESET %d %s\r\n", mId,
							req.regionId));
					writer.flush();
					log("Response: " + reader.readLine());
					req.done = true;
					break;
				}

			} catch (IOException e) {
				log("Unexpected I/O error: " + e.toString());
			} finally {
				try {
					s.shutdownOutput();
				} catch (IOException e) {
				}

				try {
					s.shutdownInput();
				} catch (IOException e) {
				}

				try {
					s.close();
				} catch (IOException e) {
				}
			}

			long stopTime = getTime();

			if (req.type == ResRequest.RES_GET) {
				log(String
						.format("GET request for %s on %s network access completed in %d ms",
								req.regionId, mHost, stopTime - startTime));
			} else if (req.type == ResRequest.RES_PUT) {
				log(String
						.format("PUT request for %s on %s network access completed in %d ms",
								req.regionId, mHost, stopTime - startTime));
			} else {
				log(String
						.format("OTHER request for %s on %s network access completed in %d ms",
								req.regionId, mHost, stopTime - startTime));
			}

			// Update last cellular access
			myHandler.post(updateLastDataActivity);

			return req;
		}

		protected void onProgressUpdate(Integer... progress) {
		}

		protected void onPostExecute(ResRequest req) {
			/* GET */
			if (req.type == ResRequest.RES_GET) {
				/* GET SUCCESSFUL */
				if (req.done) {
					req.completed = getTime();
					log(String.format(
							"GET request for %s completed after %d ms",
							req.regionId, req.completed - req.created));
					/* Use reservation if we don't have it, otherwise extras */
					if (!reservationsInUse.containsKey(req.regionId)) {
						reservationsInUse.put(req.regionId, req);
						log(String.format("Added to reservationsInUse: %s",
								reservationsInUse));
					} else {
						req.hardDeadline = req.completed
								+ Globals.REQUEST_DIRECT_PUT_DEADLINE_FROM_NOW;
						offers.add(req);
						log(String.format("Added to offers: %s", req.regionId));
					}
				}
				/* GET FAILED */
				else {
					log(String
							.format("GET request on %s failed, adding back to pending queue.",
									req.regionId));
					// reset deadlines
					long now = getTime();
					req.softDeadline = now
							+ Globals.REQUEST_RELAY_GET_DEADLINE_FROM_NOW;
					req.hardDeadline = now
							+ Globals.REQUEST_DIRECT_GET_DEADLINE_FROM_NOW;
					getsPending.add(req);
				}
			}
			/* PUT */
			else if (req.type == ResRequest.RES_PUT) {
				/* PUT SUCCESSFUL */
				if (req.done) {
					// req.completed = getTime();
				}
				/* PUT FAILED */
				else {
					log(String.format(
							"PUT request on %s failed, adding back to offers.",
							req.regionId));
					// Reset request time and type
					long now = getTime();
					req.hardDeadline = now
							+ Globals.REQUEST_DIRECT_PUT_DEADLINE_FROM_NOW;
					req.type = ResRequest.RES_GET;
					offers.add(req);
				}
			}

			updateDisplay();
		}
	}

	/***********************************************
	 * Runnables
	 ***********************************************/

	public Runnable updateLastDataActivity = new Runnable() {
		public void run() {
			lastDataActivity = getTime();
		}
	};

	/** Periodic check for GET requests that need to be sent to the cloud */
	private Runnable cloudDirectGetRequestCheck = new Runnable() {
		public void run() {
			long now = getTime();

			// send expired requests directly to cloud
			// for (ResRequest resRequest : getsPending) {
			for (Iterator<ResRequest> it = getsPending.iterator(); it.hasNext();) {
				ResRequest req = it.next();
				if (req.hardDeadline < now) {
					log(String
							.format("Request hard deadline %d expired, direct to cloud: %s",
									req.hardDeadline, req));
					it.remove();
					new ResRequestTask().execute(req, Globals.CLOUD_HOST);
				}
			}

			myHandler.postDelayed(this, Globals.REQUEST_DEADLINE_CHECK_PERIOD);
		}
	};

	/** Periodic check for PUT that need to be sent to the cloud */
	private Runnable cloudDirectPutRequestCheck = new Runnable() {
		public void run() {
			long now = getTime();

			// send timed-out PUT requests to cloud
			for (Iterator<ResRequest> it = offers.iterator(); it.hasNext();) {
				ResRequest req = it.next();
				if (req.hardDeadline < now) {
					log(String
							.format("PUT request hard deadline %d expired, direct to cloud: %s",
									req.hardDeadline, req));
					it.remove();
					req.type = ResRequest.RES_PUT;
					new ResRequestTask().execute(req, Globals.CLOUD_HOST);
				}
			}

			myHandler.postDelayed(this, Globals.REQUEST_DEADLINE_CHECK_PERIOD);
		}
	};

	private void adhocAnnounce(boolean triggerAnnounce_) {
		if (!this.adhocEnabled) {
			return;
		}

		AdhocPacket p = new AdhocPacket(mId, mLoc);

		// AdhocPacket the state of our data link
		if (this.lastDataActivity + Globals.LAST_DATA_ACTIVITY_THRESHOLD < System
				.currentTimeMillis()) {
			p.dataActivity = tm.getDataActivity();
		} else {
			p.dataActivity = TelephonyManager.DATA_ACTIVITY_DORMANT;
		}

		// AdhocPacket what tokens we are offering
		p.tokensOffered = queueKeySet(this.offers);

		p.triggerAnnounce = triggerAnnounce_;

		new SendPacketsTask().execute(p);
	}

	/** Periodic status announcements over adhoc */
	private Runnable adhocAnnounceR = new Runnable() {
		public void run() {
			adhocAnnounce(false);
			myHandler.postDelayed(this, Globals.ADHOC_ANNOUNCE_PERIOD);
		}
	};

	/***********************************************
	 * Interface to MainActivity
	 ***********************************************/

	// So we can send messages back to the MainActivity
	private Handler mainHandler;

	// Binder given to clients (the MainActivity)
	private final IBinder mBinder = new LocalBinder();

	/** This service runs in the same process as activity, don't need IPC. */
	public class LocalBinder extends Binder {
		RoadRunnerService getService(Handler h) {
			mainHandler = h;

			log("Got main activity handler, returning service instance...");

			// Return this instance so clients can call public methods
			return RoadRunnerService.this;
		}
	}

	@Override
	public IBinder onBind(Intent intent) {
		return mBinder;
	}

	public void log_nodisplay(String message) {
		mainHandler.obtainMessage(MainActivity.LOG_NODISPLAY, message)
				.sendToTarget();
	}

	public void log(String message) {
		mainHandler.obtainMessage(MainActivity.LOG, message).sendToTarget();
	}

	public void updateDisplay() {
		List<String> update = new ArrayList<String>();
		update.add(String.format("%s", this.mRegion));
		update.add(String.format("%s", this.reservationsInUse.keySet()));
		update.add(String.format("%s", queueKeySet(this.offers)));
		mainHandler.obtainMessage(MainActivity.UPDATE_DISPLAY, update)
				.sendToTarget();
	}

	/***********************************************
	 * Activity-Service interface
	 ***********************************************/

	public void makeRequest(ResRequest r1) {
		if (this.adhocEnabled) {
			getsPending.add(r1); // queue up requests
			adhocAnnounce(true); // ask nearby vehicles to announce their offers
		} else {
			// send directly to cloud
			new ResRequestTask().execute(r1, Globals.CLOUD_HOST);
		}
	}

	public void resetCloud() {
		log(String.format("Sending ResRequest for DEBUG-RESET"));
		ResRequest r1 = new ResRequest(mId, ResRequest.DEBUG_RESET, "Vassar-1");
		new ResRequestTask().execute(r1, Globals.CLOUD_HOST);
	}

	public void makeReservationRouteA() {
		log(String.format("Making ResRequests for route A"));
		makeRequest(new ResRequest(mId, ResRequest.RES_GET, "Vassar-1"));
	}

	public void makeReservationRouteA2() {
		log(String.format("Making ResRequests for route A"));
		makeRequest(new ResRequest(mId, ResRequest.RES_GET, "Vassar-1"));
		makeRequest(new ResRequest(mId, ResRequest.RES_GET, "Main-1"));
		makeRequest(new ResRequest(mId, ResRequest.RES_GET, "Main-2"));
		makeRequest(new ResRequest(mId, ResRequest.RES_GET, "Main-3"));
		makeRequest(new ResRequest(mId, ResRequest.RES_GET, "Windsor-1"));
		makeRequest(new ResRequest(mId, ResRequest.RES_GET, "Mass-1"));
		makeRequest(new ResRequest(mId, ResRequest.RES_GET, "Mass-2"));
	}

	public void makeReservationRouteB() {
		log(String.format("Making ResRequests for route B"));
		makeRequest(new ResRequest(mId, ResRequest.RES_GET, "Albany-1"));
		makeRequest(new ResRequest(mId, ResRequest.RES_GET, "Albany-2"));
		makeRequest(new ResRequest(mId, ResRequest.RES_GET, "Vassar-1"));
		makeRequest(new ResRequest(mId, ResRequest.RES_GET, "Main-3"));
		makeRequest(new ResRequest(mId, ResRequest.RES_GET, "Mass-2"));
	}

	public void makeReservationRouteC() {
		makeRequest(new ResRequest(mId, ResRequest.RES_GET, "Albany-1"));
		makeRequest(new ResRequest(mId, ResRequest.RES_GET, "Portland-1"));
		makeRequest(new ResRequest(mId, ResRequest.RES_GET, "Main-2"));
		makeRequest(new ResRequest(mId, ResRequest.RES_GET, "Vassar-1"));
		makeRequest(new ResRequest(mId, ResRequest.RES_GET, "Main-3"));
		makeRequest(new ResRequest(mId, ResRequest.RES_GET, "Mass-2"));
		log(String.format("Adding ResRequests for route C"));

	}

	/***********************************************
	 * Interface to adhoc network
	 ***********************************************/

	/** Asynchronous background task for sending packets to the network */
	public class SendPacketsTask extends
			AsyncTask<AdhocPacket, Integer, Long> {
		@Override
		protected Long doInBackground(AdhocPacket... packets) {
			long count = packets.length;
			long sent = 0;
			for (int i = 0; i < count; i++) {
				AdhocPacket adhocPacket = packets[i];

				ByteArrayOutputStream bos = new ByteArrayOutputStream();
				try {
					ObjectOutput out = new ObjectOutputStream(bos);
					out.writeObject(adhocPacket);
					out.close();
					byte[] data = bos.toByteArray();
					publishProgress((int) i + 1, (int) count);
					aat.sendData(data);
					sent++;
					log_nodisplay(String.format(
							"sent adhoc %d bytes", data.length));
				} catch (IOException e) {
					log("error sending adhoc announcement:" + e.getMessage());
				}
			}
			return sent;
		}

		protected void onProgressUpdate(Integer... progress) {
			// log("Sending " + progress[0] + " of " + progress[1] +
			// " packets...");
		}

		protected void onPostExecute(Long result) {
			// log("Sent " + result + " adhoc UDP packets");
		}
	}

	/***********************************************
	 * Android lifecycle
	 ***********************************************/

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		// log("Received start id " + startId + ", intent=" + intent);

		// We want this service to continue running until explicitly stopped
		return START_STICKY;
	}

	@Override
	public void onCreate() {
		super.onCreate();

		// Get a wakelock to keep everything running
		PowerManager pm = (PowerManager) getApplicationContext()
				.getSystemService(Context.POWER_SERVICE);
		wl = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK
				| PowerManager.ON_AFTER_RELEASE, TAG);
		wl.acquire();

		lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
		lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 1f, this);

		tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
	}

	@Override
	public void onDestroy() {
		log("Service destroyed");

		wl.release();
		lm.removeUpdates(this);

		stop();
	}

	public synchronized void start(boolean adhocEnabled_) {
		this.adhocEnabled = adhocEnabled_;
		log(String.format("Service started with adhocEnabled %b",
				this.adhocEnabled));

		// Start the adhoc UDP announcement thread
		log("Starting adhoc announce thread...");
		aat = new AdhocPacketThread(myHandler, this);
		aat.start();

		// Start the adhoc TCP server thread
		log("Starting adhoc server thread...");
		ast = new AdhocServerThread(mainHandler, this);
		ast.start();

		// fix issues with unsigned byte going to signed int
		// for now just keep last octet of ip addresses low (under 127)
		mId = aat.getLocalAddress().getAddress()[3];

		// Set up regions
		this.regions = experimentARegions();

		// Initialize state
		this.reservationsInUse = new ConcurrentHashMap<String, ResRequest>();
		this.getsPending = new ConcurrentLinkedQueue<ResRequest>();
		this.offers = new ConcurrentLinkedQueue<ResRequest>();

		// Start periodic announcements for request deadline checking
		myHandler.postDelayed(cloudDirectGetRequestCheck,
				Globals.REQUEST_DEADLINE_CHECK_PERIOD);
		myHandler.postDelayed(cloudDirectPutRequestCheck,
				Globals.REQUEST_DEADLINE_CHECK_PERIOD);

		// Start recurring UDP adhoc announcements
		if (adhocEnabled) {
			myHandler.post(adhocAnnounceR);
		}

		updateDisplay();
	}

	/** Test regions in Stata courtyard */
	private List<Region> stataRegions() {
		List<Region> rs = new ArrayList<Region>();
		Region r;

		// Region 1
		r = new Region("Stata-1");
		r.addVertex(42.36218276352746, -71.08994364738464);
		r.addVertex(42.36207970542849, -71.08879566192627);
		r.addVertex(42.36181809564884, -71.08882784843445);
		r.addVertex(42.36184980598321, -71.08983635902405);
		rs.add(r);

		// Region 2
		r = new Region("Stata-2");
		r.addVertex(42.36184980598321, -71.08983635902405);
		r.addVertex(42.36181809564884, -71.08882784843445);
		r.addVertex(42.361556484779946, -71.08887076377869);
		r.addVertex(42.36158819524629, -71.08986854553223);
		rs.add(r);

		// Region 3
		r = new Region("Stata-3");
		r.addVertex(42.36158819524629, -71.08986854553223);
		r.addVertex(42.361556484779946, -71.08887076377869);
		r.addVertex(42.36131865577206, -71.08895659446716);
		r.addVertex(42.361366221645646, -71.08989000320435);
		rs.add(r);

		log("Testing regions and getRegion logic...");
		Location l;
		l = new Location("");
		l.setLatitude(42.36196871959442);
		l.setLongitude(-71.0893964767456);
		log(String.format("Test point 1 is in region %s", getRegion(rs, l)));

		l = new Location("");
		l.setLatitude(42.361659543737126);
		l.setLongitude(-71.0893964767456);
		log(String.format("Test point 2 is in region %s", getRegion(rs, l)));

		l = new Location("");
		l.setLatitude(42.36140585984613);
		l.setLongitude(-71.0893964767456);
		log(String.format("Test point 3 is in region %s", getRegion(rs, l)));

		return rs;
	}

	/** Regions in experiment A */
	private List<Region> experimentARegions() {
		List<Region> rs = new ArrayList<Region>();
		Region r;

		// Vassar St
		r = new Region("Vassar-1");
		r.addVertex(42.36255147026933, -71.09034599930573);
		r.addVertex(42.36240877523236, -71.08975591332245);
		r.addVertex(42.36013353836458, -71.09434785515595);
		r.addVertex(42.360442721730834, -71.0948091951065);
		rs.add(r);

		// Windsor-1
		r = new Region("Windsor-1");
		r.addVertex(42.36302711805193, -71.09707297951508);
		r.addVertex(42.36297955343571, -71.09641852051544);
		r.addVertex(42.3615288153431, -71.09657945305634);
		r.addVertex(42.36186970216797, -71.09723391205597);
		rs.add(r);

		// Mass-1
		r = new Region("Mass-1");
		r.addVertex(42.36114036066024, -71.09588207871246);
		r.addVertex(42.360791542163774, -71.09660091072845);
		r.addVertex(42.36106901157985, -71.0969335046463);
		r.addVertex(42.36156052582344, -71.09657945305634);
		rs.add(r);

		// Mass-2
		r = new Region("Mass-2");
		r.addVertex(42.36035551632001, -71.09489502579498);
		r.addVertex(42.3601731773427, -71.09523834854889);
		r.addVertex(42.360577493491306, -71.095978638237);
		r.addVertex(42.36077568673155, -71.0955816713028);
		rs.add(r);

		// Albany-1
		r = new Region("Albany-1");
		r.addVertex(42.36172700558263, -71.09442295700836);
		r.addVertex(42.3614891772202, -71.09410109192658);
		r.addVertex(42.360823253016186, -71.09553875595856);
		r.addVertex(42.361084866938036, -71.09590353638458);
		rs.add(r);

		// Albany-2
		r = new Region("Albany-2");
		r.addVertex(42.362678310030105, -71.09243812233734);
		r.addVertex(42.36253561528121, -71.09191240937042);
		r.addVertex(42.36180628150339, -71.09342517525482);
		r.addVertex(42.36223436974708, -71.09344663292694);
		rs.add(r);

		// Portland-1
		r = new Region("Portland-1");
		r.addVertex(42.362757584750575, -71.09386505753326);
		r.addVertex(42.36273380234492, -71.09342517525482);
		r.addVertex(42.36217887699113, -71.09354319245148);
		r.addVertex(42.36198861574153, -71.09409036309052);
		rs.add(r);

		// Main-1
		r = new Region("Main-1");
		r.addVertex(42.36318566651262, -71.09384359986115);
		r.addVertex(42.36278929461076, -71.09392943054962);
		r.addVertex(42.36297162599619, -71.09643997818756);
		r.addVertex(42.36336799674776, -71.09641852051544);
		rs.add(r);

		// Main-2
		r = new Region("Main-2");
		r.addVertex(42.36300333574834, -71.09216990143585);
		r.addVertex(42.36271794740286, -71.09249176651764);
		r.addVertex(42.36277343968266, -71.09333934456635);
		r.addVertex(42.363106392332284, -71.09324278504181);
		rs.add(r);

		// Main-3
		r = new Region("Main-3");
		r.addVertex(42.36289235154579, -71.09035672814178);
		r.addVertex(42.36259110772208, -71.09038891464996);
		r.addVertex(42.36264660011392, -71.09166564614105);
		r.addVertex(42.36303504548448, -71.09157981545258);
		rs.add(r);

		Location l;

		l = new Location("");
		l.setLatitude(42.36035940296916);
		l.setLongitude(-71.0944926738739);
		log(String.format("Test point on Vassar is in region %s",
				getRegion(rs, l)));

		l = new Location("");
		l.setLatitude(42.36081921192526);
		l.setLongitude(-71.09338760375977);
		log(String.format("Test point on Vassar is in region %s",
				getRegion(rs, l)));

		l = new Location("");
		l.setLatitude(42.36160405047349);
		l.setLongitude(-71.0919177532196);
		log(String.format("Test point on Vassar is in region %s",
				getRegion(rs, l)));

		l = new Location("");
		l.setLatitude(42.3619370093201);
		l.setLongitude(-71.09123110771179);
		log(String.format("Test point on Vassar is in region %s",
				getRegion(rs, l)));

		l = new Location("");
		l.setLatitude(42.36234924163794);
		l.setLongitude(-71.09039425849915);
		log(String.format("Test point on Vassar is in region %s",
				getRegion(rs, l)));

		l = new Location("");
		l.setLatitude(42.3631736981596);
		l.setLongitude(-71.09626293182373);
		log(String.format("Test point on Main-1 is in region %s",
				getRegion(rs, l)));

		l = new Location("");
		l.setLatitude(42.36303893196785);
		l.setLongitude(-71.09436392784119);
		log(String.format("Test point on Main-1 is in region %s",
				getRegion(rs, l)));

		l = new Location("");
		l.setLatitude(42.362935875273244);
		l.setLongitude(-71.09288334846497);
		log(String.format("Test point on Main-2 is in region %s",
				getRegion(rs, l)));

		l = new Location("");
		l.setLatitude(42.362785253646265);
		l.setLongitude(-71.09100580215454);
		log(String.format("Test point on Main-3 is in region %s",
				getRegion(rs, l)));

		l = new Location("");
		l.setLatitude(42.362476081807);
		l.setLongitude(-71.0936987400055);
		log(String.format("Test point on Portland-1 is in region %s",
				getRegion(rs, l)));

		l = new Location("");
		l.setLatitude(42.36099362133876);
		l.setLongitude(-71.09561920166016);
		log(String.format("Test point on Albany-1 is in region %s",
				getRegion(rs, l)));

		l = new Location("");
		l.setLatitude(42.36154855716084);
		l.setLongitude(-71.0943853855133);
		log(String.format("Test point on Albany-1 is in region %s",
				getRegion(rs, l)));

		l = new Location("");
		l.setLatitude(42.362008357414815);
		l.setLongitude(-71.093430519104);
		log(String.format("Test point on Albany-2 is in region %s",
				getRegion(rs, l)));

		l = new Location("");
		l.setLatitude(42.362610849206014);
		l.setLongitude(-71.09221816062927);
		log(String.format("Test point on Albany-2 is in region %s",
				getRegion(rs, l)));

		l = new Location("");
		l.setLatitude(42.3611521749309);
		l.setLongitude(-71.09653115272522);
		log(String.format("Test point on Mass-1 is in region %s",
				getRegion(rs, l)));

		l = new Location("");
		l.setLatitude(42.3604862471552);
		l.setLongitude(-71.09537243843079);
		log(String.format("Test point on Mass-2 is in region %s",
				getRegion(rs, l)));

		l = new Location("");
		l.setLatitude(42.36238887921827);
		l.setLongitude(-71.09683156013489);
		log(String.format("Test point on Windsor-1 is in region %s",
				getRegion(rs, l)));

		return rs;
	}

	// http://stackoverflow.com/questions/1066589/java-iterate-through-hashmap
	private String getRegion(List<Region> rs, Location loc) {
		Iterator<Region> it = rs.iterator();
		while (it.hasNext()) {
			Region r = (Region) it.next();
			if (r.contains(loc)) {
				return r.id;
			}
		}
		return "FREE";
	}

	public synchronized void stop() {
		log("Stopping service...");

		myHandler.removeCallbacks(adhocAnnounceR);
		myHandler.removeCallbacks(cloudDirectGetRequestCheck);
		myHandler.removeCallbacks(cloudDirectPutRequestCheck);

		log("Terminating adhoc announce thread...");
		if (aat != null) {
			aat.close();
			aat = null;
		}

		log("Terminating adhoc server thread WiFi...");
		if (ast != null) {
			ast.close();
			ast = null;
		}
	}

	/***********************************************
	 * GPS
	 ***********************************************/

	public long getTime() {
		return MainActivity.getTime();
	}

	/** Location - location changed */
	@Override
	public void onLocationChanged(Location loc) {
		// sync internal clock to GPS on first fix
		if (!MainActivity.clockSynced) {
			MainActivity.clockOffset = loc.getTime()
					- System.currentTimeMillis();
			MainActivity.clockSynced = true;
			log(String.format("CLOCK SYNCED TO GPS with offset %d",
					MainActivity.clockOffset));
		}

		this.mLoc = loc;

		// did we enter a new region?
		String oldRegion = this.mRegion;
		String newRegion = getRegion(this.regions, loc);
		if (newRegion == oldRegion) {
			return; // do nothing if we haven't changed regions
		}
		this.mRegion = newRegion;

		long now = getTime();

		// offer up old reservation
		if (this.reservationsInUse.containsKey(oldRegion)) {
			ResRequest oldRes = this.reservationsInUse.remove(oldRegion);
			oldRes.hardDeadline = now
					+ Globals.REQUEST_DIRECT_GET_DEADLINE_FROM_NOW;
			this.offers.add(oldRes);
		}

		// region transition, so check for reservation if necessary
		if ("FREE".equals(newRegion)) {
			log(String
					.format("Moved from %s to %s with GPS fix %s, no reservation needed.",
							oldRegion, newRegion, loc));
		} else if (this.reservationsInUse.containsKey(newRegion)) {
			log(String
					.format("Moved from %s to %s with GPS fix %s, reservation from in-use store.",
							oldRegion, newRegion, loc));
		} else if (queueKeySet(offers).contains(newRegion)) {
			log(String
					.format("Moved from %s to %s with GPS fix %s, reservation from offer store.",
							oldRegion, newRegion, loc));
			ResRequest res = queuePoll(offers, newRegion);
			if (res != null) {
				this.reservationsInUse.put(newRegion, res);
			} else {
				log("ERROR getting reservation from offer store, NULL.");
			}
		} else {
			log(String
					.format("Moved from %s to %s with GPS fix %s, no reservation, PENALTY.",
							oldRegion, newRegion, loc));
		}

		updateDisplay();
	}

	/** Location - provider disabled */
	@Override
	public void onProviderDisabled(String arg0) {
	}

	/** Location - provider disabled */
	@Override
	public void onProviderEnabled(String arg0) {
	}

	/** Location - provider status changed */
	@Override
	public void onStatusChanged(String provider, int status, Bundle extras) {
		switch (status) {
		case LocationProvider.OUT_OF_SERVICE:
			log("LocationProvider out of service, stopping adhoc announcements.");
			// myHandler.removeCallbacks(adhocAnnounceR);
			break;
		case LocationProvider.TEMPORARILY_UNAVAILABLE:
			log("LocationProvider temporarily unavailable, stopping adhoc announcements.");
			// myHandler.removeCallbacks(adhocAnnounceR);
			break;
		case LocationProvider.AVAILABLE:
			log("LocationProvider available, starting adhoc announcements.");
			// myHandler.postDelayed(adhocAnnounceR,
			// Globals.ADHOC_ANNOUNCE_PERIOD);
			break;
		}
	}
}
