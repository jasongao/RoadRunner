package edu.mit.csail.jasongao.roadrunner;

import java.util.ArrayList;
import java.util.List;

import android.location.Location;

public class Region {
	
	public String id;
	
	public List<Location> vertices;
	
	public Region(String id_) {
		this.id = id_;
		vertices = new ArrayList<Location>();
	}
	
	public void addVertex(double latitude, double longitude) {
		Location v = new Location("");
		v.setLongitude(longitude);
		v.setLatitude(latitude);
		vertices.add(v);
	}

	/* Test if a Location p is inside this Region */
	public boolean contains(Location p) {

		double x = p.getLongitude();
		double y = p.getLatitude();
		int polySides = vertices.size();
		boolean oddTransitions = false;

		for (int i = 0, j = polySides - 1; i < polySides; j = i++) {
			if ((vertices.get(i).getLatitude() < y && vertices.get(j)
					.getLatitude() >= y)
					|| (vertices.get(j).getLatitude() < y && vertices.get(i)
							.getLatitude() >= y)) {
				if (vertices.get(i).getLongitude()
						+ (y - vertices.get(i).getLatitude())
						/ (vertices.get(j).getLatitude() - vertices.get(i)
								.getLatitude())
						* (vertices.get(j).getLongitude() - vertices.get(i)
								.getLongitude()) < x) {
					oddTransitions = !oddTransitions;
				}
			}
		}
		return oddTransitions;
	}
}
