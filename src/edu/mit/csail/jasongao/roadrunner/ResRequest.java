package edu.mit.csail.jasongao.roadrunner;

import java.io.Serializable;
import java.util.Arrays;

/** A request to get or put a token */
public class ResRequest implements Serializable {
	private static final long serialVersionUID = 101L;

	/** Type of interaction */
	public static final int RES_GET = 1;
	public static final int RES_PUT = 4;
	public static final int DEBUG_RESET = 13;

	/** Always populated */
	public int type;
	public long created, completed, src, regionId;
	public long softDeadline;
	public long hardDeadline;
	public boolean done;

	/** Populated for RES_GET done=true */
	public long issued, expires;
	public String tokenString;
	public String signature;

	public ResRequest(long srcId_, int type_, int regionId_) {
		this.created = System.currentTimeMillis();
		this.src = srcId_;
		this.type = type_;
		this.regionId = regionId_;
		this.done = false;
	}

	/** Checks signature and expiration */
	public boolean tokenIsValid() {
		// TODO DEBUG
		if (true)
			return true;

		// Check expiration
		if (System.currentTimeMillis() > expires) {
			return false;
		}

		// Verify signature
		String tokenString = String.format(
				"{\"issued\": %d, \"regionId\": %d, \"expires\": %d}", issued,
				regionId, expires);
		// TODO

		byte[] byteArray1 = null;
		byte[] byteArray2 = null;

		if (Arrays.equals(byteArray1, byteArray2)) {
			return true;
		}

		return false;
	}

	@Override
	public String toString() {
		return String.format("ResRequest[src=%d, regionId=%d, type=%d, done=%b]", src,
				regionId, type, done);
	}
}
