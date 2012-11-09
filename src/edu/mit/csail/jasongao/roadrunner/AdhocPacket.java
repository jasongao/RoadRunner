package edu.mit.csail.jasongao.roadrunner;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.Set;

import android.location.Location;

public class AdhocPacket implements Serializable {
	private static final long serialVersionUID = 202L;

	public final static int ANNOUNCE = 0;
	public final static int TOKEN_REQUEST = 1;
	public final static int TOKEN_SEND = 2;

	/** Announcement or token transfer? */
	public int type = 0;
	
	/** Only for TOKEN_REQUEST */
	public String region;
	/** End token transfer attributes */
	
	/** Only for TOKEN_SEND */
	public long issued, expires;
	public String tokenString;
	public String signature;
	/** End token transfer attributes */

	public long timestamp;
	public long src, dst = -1;
	public long nonce;

	private transient Location loc;
	private double lat, lng;
	private float speed, bearing;

	public Set<String> tokensOffered; // Regions for which I am offering tokens
	public int dataActivity = -1; // dataActivity state from TelephonyManager
	public boolean triggerAnnounce = false; // sender wants an announcement back

	public int length; // length of serialized packet in bytes

	public AdhocPacket(long src_, Location loc) {
		timestamp = System.currentTimeMillis();
		src = src_;
		if (loc != null) {
			lng = loc.getLongitude();
			lat = loc.getLatitude();
			speed = loc.getSpeed();
			bearing = loc.getBearing();
		} else {
			lng = -1;
			lat = -1;
			speed = -1;
			bearing = -1;
		}
	}

	public Location getLocation() {
		return this.loc;
	}

	public void setLocation(Location l) {
		this.loc = l;
	}

	private void writeObject(ObjectOutputStream out) throws IOException {
		out.defaultWriteObject();
	}

	private void readObject(ObjectInputStream in) throws IOException,
			ClassNotFoundException {
		in.defaultReadObject();
		// rebuild Location object
		loc = new Location("");
		loc.setLatitude(lat);
		loc.setLongitude(lng);
		loc.setSpeed(speed);
		loc.setBearing(bearing);
	}

	@Override
	public String toString() {
		return String
				.format("AdhocPacket[src=%d, lng=%f, lat=%f, spd=%f, bearing=%f, offers=%s, length=%dbytes]",
						src, lng, lat, speed, bearing, tokensOffered, length);
	}
}
