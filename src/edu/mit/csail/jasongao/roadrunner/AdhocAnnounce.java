package edu.mit.csail.jasongao.roadrunner;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.Set;

import android.location.Location;

public class AdhocAnnounce implements Serializable {
	private static final long serialVersionUID = 202L;

	public long timestamp;
	public long src;

	private transient Location loc;
	private double lat, lng;
	private float speed, bearing;

	public Set<Long> tokensOffered; // Regions for which I am offering t's
	public int dataActivity = -1; // dataActivity state from TelephonyManager

	public AdhocAnnounce(long src_, Location loc) {
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
		return String.format(
				"AdhocAnnounce[src=%d, lng=%f, lat=%f, spd=%f, bearing=%f, offers=%s]", src,
				lng, lat, speed, bearing, tokensOffered);
	}
}
