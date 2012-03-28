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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
	private AdhocAnnounceThread aat;
	private AdhocServerThread ast;

	/***********************************************
	 * RoadRunner state
	 ***********************************************/

	private List<Region> regions;

	private Location mLoc;
	private long mRegion = -1;
	private long mId = -1000;

	/** Reservations we are using/will use. Map regionId to done ResRequest */
	private Map<Long, ResRequest> reservationsInUse;

	/** Reservations we can give away */
	public Map<Long, ConcurrentLinkedQueue<ResRequest>> reservationsOffered;

	/** Pending GET RES_REQUESTS that can be sent to either cloud or to adhoc */
	private Queue<ResRequest> getsPending;

	/** Pending PUT RES_REQUESTS that will be sent to cloud eventually */
	private Queue<ResRequest> putsPending;

	/***********************************************
	 * Handle messages from other components and threads
	 ***********************************************/
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
	private boolean linkIsViable(Location v1, AdhocAnnounce other) {
		Location v2 = other.getLocation();

		if (v1 == null || v2 == null) {
			log("Link viable debug: no GPS fix.");
			return true;
		}

		// Too far away (> 70m)
		if (v1.distanceTo(v2) > 70) {
			log("Link not viable: more than 70 meters apart.");
			return false;
		}

		// Quite close together (< 20 m)
		if (v1.distanceTo(v2) < 20) {
			log("Link viable: less than 20 meters apart.");
			return false;
		}

		// Both stationary?
		if (v1.hasSpeed() && v1.getSpeed() < 2 && v2.hasSpeed()
				&& v2.getSpeed() < 2) {
			log("Link viable: both stationary (less than 2 meters per second).");
			return true;
		}

		// One stationary and other moving towards it?
		if (v1.hasSpeed()
				&& v1.getSpeed() < 2
				&& v2.hasBearing()
				&& ((Math.abs(v2.bearingTo(v1) - v2.getBearing()) < 45) || (Math
						.abs(v2.bearingTo(v1) - v2.getBearing()) > 360 - 45))) {
			log("Link viable: stationary and other vehicle moving closer.");
			return true;
		}
		if (v2.hasSpeed()
				&& v2.getSpeed() < 2
				&& v1.hasBearing()
				&& ((Math.abs(v1.bearingTo(v2) - v1.getBearing()) < 45) || (Math
						.abs(v1.bearingTo(v2) - v1.getBearing()) > 360 - 45))) {
			log("Link viable: moving closer to stationary other vehicle.");
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
			log("Link viable: moving towards each other");
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
			log("Link viable: moving together.");
			return true;
		}

		log("Link not viable: moving apart.");
		return false;
	}

	private final Handler myHandler = new Handler() {
		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
			case ADHOC_PACKET_RECV:
				AdhocAnnounce other = (AdhocAnnounce) msg.obj;
				long now = System.currentTimeMillis();
				if (!linkIsViable(mLoc, other)) {
					break;
				}

				for (Iterator<ResRequest> it = getsPending.iterator(); it
						.hasNext();) {
					ResRequest req = it.next();

					// try to get token from other vehicle?
					if (other.tokensOffered.contains(req.regionId)) {
						log(String.format("Other vehicle %d offers %s, GET %d",
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

			long startTime = System.currentTimeMillis();

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
					writer.write(String.format("GET %d %d\r\n", mId,
							req.regionId));
					writer.flush();

					response = reader.readLine();
					log("Response: " + response);
					if (response.equals("GET 200 OK")) {
						req.tokenString = reader.readLine();
						req.signature = reader.readLine();
						log(String.format("GET 200 OK\nTOKEN %s\nSIG %s",
								req.tokenString, req.signature));

						// deserialize token attributes
						String[] parts = req.tokenString.split(" ");
						req.issued = Long.parseLong(parts[1]);
						req.expires = Long.parseLong(parts[2]);

						// TODO verify signature
						if (!req.tokenIsValid()) {
							log("Token signature verification FAILED!");
							// failed to verify token, put back into pending q?
						}

						req.done = true;
					} else {
						log("GET request failed: " + response);
						req.done = false;
						// reset deadlines
						long now = System.currentTimeMillis();
						req.softDeadline = now
								+ Globals.REQUEST_SOFT_DEADLINE_FROM_NOW;
						req.hardDeadline = now
								+ Globals.REQUEST_HARD_DEADLINE_FROM_NOW;
					}
					break;
				case ResRequest.RES_PUT:
					writer.write(String.format("PUT %d %d\r\n", mId,
							req.regionId));
					writer.flush();

					response = reader.readLine();
					log("Response: " + response);
					if (response.equals("PUT 200 OK")) {
						req.done = true;
					} else {
						log("PUT request failed: " + response);
						req.done = false;
					}
					break;
				case ResRequest.DEBUG_RESET:
					writer.write(String.format("DEBUG-RESET %d %d\r\n", mId,
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

			long stopTime = System.currentTimeMillis();
			log(String.format("Request task took %d ms.", stopTime - startTime));

			return req;
		}

		protected void onProgressUpdate(Integer... progress) {
		}

		protected void onPostExecute(ResRequest req) {
			/* GET */
			if (req.type == ResRequest.RES_GET) {
				/* GET SUCCESSFUL */
				if (req.done) {
					req.completed = System.currentTimeMillis();
					log(String
							.format("GET request on regionId %d successful, took %d ms",
									req.regionId, req.completed - req.created));
					/* Use reservation if we don't have it, otherwise extras */
					if (!reservationsInUse.containsKey(req.regionId)) {
						reservationsInUse.put(req.regionId, req);
						log(String.format("Added to reservationsInUse: %s",
								reservationsInUse));
					} else {
						if (!reservationsOffered.containsKey(req.regionId)) {
							reservationsOffered.put(req.regionId,
									new ConcurrentLinkedQueue<ResRequest>());
						}
						reservationsOffered.get(req.regionId).add(req);
						log(String.format(
								"Added to reservationsOffered(%d): %s",
								req.regionId,
								reservationsOffered.get(req.regionId)));
					}
				}
				/* GET FAILED */
				else {
					log(String
							.format("GET request on regionId %d failed, adding back to pending queue.",
									req.regionId));
					getsPending.add(req);
				}
			}
			/* PUT */
			else if (req.type == ResRequest.RES_PUT) {
				/* PUT SUCCESSFUL */
				if (req.done) {
					req.completed = System.currentTimeMillis();
					log(String
							.format("PUT request on regionId %d successful, took %d ms",
									req.regionId, req.completed - req.created));
				}
				/* PUT FAILED */
				else {
					log(String
							.format("PUT request on regionId %d failed, adding back to offers.",
									req.regionId));
					// Reset request time
					req.created = System.currentTimeMillis();
					reservationsOffered.get(req.regionId).add(req);
				}
			}

			updateDisplay();
		}
	}

	/***********************************************
	 * Recurring Runnables
	 ***********************************************/

	/** Periodic check for RES_REQUESTS that need to be sent to the cloud */
	private Runnable cloudDirectRequestCheck = new Runnable() {
		public void run() {
			long now = System.currentTimeMillis();

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

	/** Periodic status announcements over adhoc */
	private Runnable adhocAnnounceR = new Runnable() {
		public void run() {
			AdhocAnnounce p = new AdhocAnnounce(mId, mLoc);

			// AdhocAnnounce the state of our data link
			p.dataActivity = tm.getDataActivity();

			// AdhocAnnounce what tokens we are offering
			p.tokensOffered = new HashSet<Long>(reservationsOffered.keySet());

			new SendPacketsTask().execute(p);
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

	public void log(String message) {
		Log.i(TAG, message);

		mainHandler.obtainMessage(MainActivity.LOG, "NS: " + message)
				.sendToTarget();
	}

	public void updateDisplay() {
		List<String> update = new ArrayList<String>();
		if (this.mRegion == -1) {
			update.add("Free Zone");
		} else {
			update.add(String.format("%d", this.mRegion));
		}
		update.add(String.format("%s", this.reservationsInUse.keySet()));
		update.add(String.format("%s", this.reservationsOffered.keySet()));
		mainHandler.obtainMessage(MainActivity.UPDATE_DISPLAY, update)
				.sendToTarget();
	}

	/***********************************************
	 * Activity-Service interface
	 ***********************************************/

	public void resetCloud() {
		log(String.format("Sending ResRequest for DEBUG-RESET"));
		ResRequest r1 = new ResRequest(mId, ResRequest.DEBUG_RESET, 1);
		new ResRequestTask().execute(r1, Globals.CLOUD_HOST);
	}

	public void makeReservationRouteA() {
		log(String.format("Adding ResRequests for route A"));
		ResRequest r1 = new ResRequest(mId, ResRequest.RES_GET, 1);
		r1.softDeadline = r1.created + Globals.REQUEST_SOFT_DEADLINE_FROM_NOW;
		r1.hardDeadline = r1.created + Globals.REQUEST_HARD_DEADLINE_FROM_NOW;
		getsPending.add(r1);
	}

	public void makeReservationRouteB() {
		log(String.format("Adding ResRequests for route B"));
		ResRequest r1 = new ResRequest(mId, ResRequest.RES_GET, 1);
		r1.softDeadline = r1.created + Globals.REQUEST_SOFT_DEADLINE_FROM_NOW;
		r1.hardDeadline = r1.created + Globals.REQUEST_HARD_DEADLINE_FROM_NOW;
		getsPending.add(r1);
	}

	public void makeReservationRouteC() {
		log(String.format("Adding ResRequests for route C"));
		ResRequest r1 = new ResRequest(mId, ResRequest.RES_GET, 1);
		r1.softDeadline = r1.created + Globals.REQUEST_SOFT_DEADLINE_FROM_NOW;
		r1.hardDeadline = r1.created + Globals.REQUEST_HARD_DEADLINE_FROM_NOW;
		getsPending.add(r1);
	}

	/***********************************************
	 * Interface to adhoc network
	 ***********************************************/

	/** Asynchronous background task for sending packets to the network */
	public class SendPacketsTask extends
			AsyncTask<AdhocAnnounce, Integer, Long> {
		@Override
		protected Long doInBackground(AdhocAnnounce... packets) {
			long count = packets.length;
			long sent = 0;
			for (int i = 0; i < count; i++) {
				AdhocAnnounce adhocAnnounce = packets[i];

				ByteArrayOutputStream bos = new ByteArrayOutputStream();
				try {
					ObjectOutput out = new ObjectOutputStream(bos);
					out.writeObject(adhocAnnounce);
					out.close();
					byte[] data = bos.toByteArray();
					publishProgress((int) i + 1, (int) count);
					aat.sendData(data);
					sent++;
				} catch (IOException e) {
					log("error sending packet:" + e.getMessage());
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

	public synchronized void start() {
		log("Service started, connecting to networks...");

		// Start the adhoc UDP announcement thread
		log("Starting adhoc announce thread...");
		aat = new AdhocAnnounceThread(myHandler);
		aat.start();

		// Start the adhoc TCP server thread
		log("Starting adhoc server thread...");
		ast = new AdhocServerThread(mainHandler, this);
		ast.start();

		// fix issues with unsigned byte going to signed int
		// for now just keep last octet of ip addresses low (under 127)
		mId = aat.getLocalAddress().getAddress()[3];

		// Set up regions
		this.regions = stataRegions();

		// Initialize state
		this.reservationsOffered = new ConcurrentHashMap<Long, ConcurrentLinkedQueue<ResRequest>>();
		this.reservationsInUse = new ConcurrentHashMap<Long, ResRequest>();
		this.getsPending = new ConcurrentLinkedQueue<ResRequest>();
		this.putsPending = new ConcurrentLinkedQueue<ResRequest>();

		// Start periodic announcements for request deadline checking
		myHandler.postDelayed(cloudDirectRequestCheck,
				Globals.REQUEST_DEADLINE_CHECK_PERIOD);
		myHandler.postDelayed(adhocAnnounceR, Globals.ADHOC_ANNOUNCE_PERIOD);

		updateDisplay();
	}

	/** Test regions in Stata courtyard */
	private List<Region> stataRegions() {
		List<Region> rs = new ArrayList<Region>();
		Region r;

		// Region 1
		r = new Region(1L);
		r.addVertex(42.36218276352746, -71.08994364738464);
		r.addVertex(42.36207970542849, -71.08879566192627);
		r.addVertex(42.36181809564884, -71.08882784843445);
		r.addVertex(42.36184980598321, -71.08983635902405);
		rs.add(r);

		// Region 2
		r = new Region(2L);
		r.addVertex(42.36184980598321, -71.08983635902405);
		r.addVertex(42.36181809564884, -71.08882784843445);
		r.addVertex(42.361556484779946, -71.08887076377869);
		r.addVertex(42.36158819524629, -71.08986854553223);
		rs.add(r);

		// Region 3
		r = new Region(3L);
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
		log(String.format("Test point 1 is in region %d", getRegion(rs, l)));

		l = new Location("");
		l.setLatitude(42.361659543737126);
		l.setLongitude(-71.0893964767456);
		log(String.format("Test point 2 is in region %d", getRegion(rs, l)));

		l = new Location("");
		l.setLatitude(42.36140585984613);
		l.setLongitude(-71.0893964767456);
		log(String.format("Test point 3 is in region %d", getRegion(rs, l)));

		return rs;
	}

	/** Regions in experiment A */
	private List<Region> experimentARegions() {
		List<Region> rs = new ArrayList<Region>();
		Region r;

		// Vassar St
		r = new Region(1L);
		r.addVertex(42.360438757851156, -71.09472336441803);
		r.addVertex(42.36252372403751, -71.09044255883026);
		r.addVertex(42.362396883964664, -71.08983101517487);
		r.addVertex(42.36017714123914, -71.09434785515595);
		rs.add(r);

		// Main-3
		r = new Region(2L);
		r.addVertex(42.360438757851156, -71.09472336441803);
		r.addVertex(42.36252372403751, -71.09044255883026);
		r.addVertex(42.362396883964664, -71.08983101517487);
		r.addVertex(42.36017714123914, -71.09434785515595);
		rs.add(r);

		return rs;
	}

	// http://stackoverflow.com/questions/1066589/java-iterate-through-hashmap
	private long getRegion(List<Region> rs, Location loc) {
		Iterator<Region> it = rs.iterator();
		while (it.hasNext()) {
			Region r = (Region) it.next();
			if (r.contains(loc)) {
				return r.id;
			}
		}
		return -1;
	}

	public synchronized void stop() {
		log("Stopping service...");
		
		myHandler.removeCallbacks(adhocAnnounceR);
		myHandler.removeCallbacks(cloudDirectRequestCheck);

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

	/** Location - location changed */
	@Override
	public void onLocationChanged(Location loc) {
		this.mLoc = loc;

		// did we enter a new region?
		long oldRegion = this.mRegion;
		long newRegion = getRegion(this.regions, loc);
		if (newRegion == oldRegion) {
			return; // do nothing if we haven't changed regions
		}
		this.mRegion = newRegion;

		// offer up old reservation
		if (this.reservationsInUse.containsKey(oldRegion)) {
			ResRequest oldRes = this.reservationsInUse.remove(oldRegion);
			this.reservationsOffered.get(oldRegion).add(oldRes);
		}

		// region transition, so check for reservation if necessary
		if (newRegion == -1) {
			log(String
					.format("Moved from region %d to %d with GPS fix %s, no reservation needed.",
							oldRegion, newRegion, loc));
		} else if (this.reservationsInUse.containsKey(newRegion)) {
			log(String
					.format("Moved from region %d to %d with GPS fix %s, reservation from in-use store.",
							oldRegion, newRegion, loc));
		} else if (this.reservationsOffered.containsKey(newRegion)
				&& this.reservationsOffered.get(newRegion).size() != 0) {
			log(String
					.format("Moved from region %d to %d with GPS fix %s, reservation from offer store.",
							oldRegion, newRegion, loc));
			ResRequest res = this.reservationsInUse.remove(oldRegion);
			this.reservationsInUse.put(newRegion, res);
		} else {
			log(String
					.format("Moved from region %d to %d with GPS fix %s, missing reservation, PENALTY.",
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
			myHandler.removeCallbacks(adhocAnnounceR);
			break;
		case LocationProvider.TEMPORARILY_UNAVAILABLE:
			log("LocationProvider temporarily unavailable, stopping adhoc announcements.");
			myHandler.removeCallbacks(adhocAnnounceR);
			break;
		case LocationProvider.AVAILABLE:
			log("LocationProvider available, starting adhoc announcements.");
			myHandler
					.postDelayed(adhocAnnounceR, Globals.ADHOC_ANNOUNCE_PERIOD);
			break;
		}
	}
}
