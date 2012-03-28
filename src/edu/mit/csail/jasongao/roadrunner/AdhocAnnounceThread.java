package edu.mit.csail.jasongao.roadrunner;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.UnknownHostException;
import java.util.Enumeration;

import android.os.Handler;
import android.util.Log;

public class AdhocAnnounceThread extends Thread {
	private static final String TAG = "AdhocAnnounceThread";

	private Handler parentHandler;

	private DatagramSocket mySocket;
	private boolean socketOK = false;
	private InetAddress myBcastIPAddress;
	private InetAddress myIPAddress;

	/** Send an UDP packet to the broadcast address */
	public void sendData(byte[] sendData) throws IOException {
		mySocket.send(new DatagramPacket(sendData, sendData.length,
				myBcastIPAddress, Globals.ADHOC_PORT));
	}

	/** AdhocAnnounceThread constructor */
	public AdhocAnnounceThread(Handler p) {
		parentHandler = p;

		myIPAddress = null;
		try {
			NetworkInterface intf = NetworkInterface
					.getByName(Globals.ADHOC_IFACE_NAME);
			for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr
					.hasMoreElements();) {
				InetAddress inetAddress = enumIpAddr.nextElement();
				if (!inetAddress.isLoopbackAddress()) {
					myIPAddress = inetAddress;
				}
			}
			if (myIPAddress == null)
				throw new Exception("no addresses bound to "
						+ Globals.ADHOC_IFACE_NAME);
		} catch (Exception e) {
			Log.e(TAG, "can't determine local IP address: " + e.toString());
			return;
		}

		try {
			myBcastIPAddress = InetAddress.getByName(Globals.ADHOC_BROADCAST_ADDRESS);
		} catch (UnknownHostException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}

		/** Create a socket and set it up for receiving broadcasts. */
		try {
			mySocket = new DatagramSocket(Globals.ADHOC_PORT);
			mySocket.setBroadcast(true);

			Log.i(TAG, String.format(
					"Orig socket buffers: %d receive, %d send",
					mySocket.getReceiveBufferSize(),
					mySocket.getSendBufferSize()));

			mySocket.setReceiveBufferSize(Globals.ADHOC_MAX_PACKET_SIZE);
			mySocket.setSendBufferSize(Globals.ADHOC_MAX_PACKET_SIZE);

			Log.i(TAG, String.format("Set socket buffers: %d receive, %d send",
					mySocket.getReceiveBufferSize(),
					mySocket.getSendBufferSize()));

			socketOK = true;
		} catch (Exception e) {
			Log.i(TAG, "Cannot open socket: " + e.getMessage());
			return;
		}

		Log.i(TAG, "started, adhoc IP address:" + myIPAddress);
	}

	/** If not socketOK, then receive loop thread will stop */
	public synchronized boolean socketIsOK() {
		return socketOK;
	}

	/** Close the socket before exiting the application */
	public synchronized void close() {
		if (mySocket != null && !mySocket.isClosed())
			mySocket.close();
	}

	/** Thread's receive loop for UDP packets */
	@Override
	public void run() {
		byte[] receiveData = new byte[Globals.ADHOC_MAX_PACKET_SIZE];

		try {
			while (socketOK) {
				DatagramPacket dPacket = new DatagramPacket(receiveData,
						receiveData.length);
				mySocket.receive(dPacket);

				// ignore our own UDP broadcasts
				if (dPacket.getAddress().equals(myIPAddress))
					continue;

				parentHandler.obtainMessage(
						RoadRunnerService.ADHOC_PACKET_RECV,
						readPacket(dPacket.getData())).sendToTarget();
			}
		} catch (IOException e) {
			Log.e(TAG, "Exception on mySocket.receive: " + e.getMessage());
			socketOK = false;
		}

		Log.i(TAG, "AdhocAnnounceThread exiting.");
	} // end run()

	/** Return our stored local IP address. */
	public synchronized InetAddress getLocalAddress() {
		return myIPAddress;
	}

	/**
	 * Read back a packet.
	 * 
	 * @throws Exception
	 */
	private AdhocAnnounce readPacket(byte[] data) {
		AdhocAnnounce adhocAnnounce = null;
		ByteArrayInputStream bis = new ByteArrayInputStream(data);
		ObjectInputStream ois = null;
		try {
			ois = new ObjectInputStream(bis);
			adhocAnnounce = (AdhocAnnounce) ois.readObject();
			ois.close();
		} catch (IOException e) {
			Log.e(TAG, "error on new ObjectInputStream: " + e.getMessage());
			e.printStackTrace();
		} catch (ClassNotFoundException e) {
			Log.e(TAG, "ClassNotFoundException from ois: " + e.getMessage());
			e.printStackTrace();
		}
		return adhocAnnounce;
	}
}