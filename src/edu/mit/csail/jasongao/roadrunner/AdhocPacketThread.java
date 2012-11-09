package edu.mit.csail.jasongao.roadrunner;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Enumeration;

import android.os.Handler;

public class AdhocPacketThread extends Thread {
	private static final String TAG = "AdhocPacketThread";

	private Handler parentHandler;
	private RoadRunnerService rrs;

	private boolean recvSocketOK = false;
	private boolean sendSocketOK = false;

	private DatagramSocket recvSocket;
	private DatagramSocket sendSocket;

	private InetAddress remoteIPAddress;
	private InetAddress localIPAddress;

	/** Send an UDP packet to the broadcast address */
	public void sendData(byte[] sendData) throws IOException {
		try {
			sendSocket.send(new DatagramPacket(sendData, sendData.length,
					remoteIPAddress, Globals.ADHOC_SEND_PORT));
		} catch (IOException e) {
			sendSocketOK = false;
			throw e;
		}
	}

	private void log(String s) {
		if (rrs != null)
			rrs.log(s);
	}

	private void log_nodisplay(String s) {
		if (rrs != null)
			rrs.log(s);
	}

	/** Set a socket's buffer sizes */
	public void setBuffers(Socket socket) throws SocketException {
		log(String.format("Orig socket buffers: %d receive, %d send",
				socket.getReceiveBufferSize(), socket.getSendBufferSize()));

		socket.setReceiveBufferSize(Globals.ADHOC_MAX_PACKET_SIZE);
		socket.setSendBufferSize(Globals.ADHOC_MAX_PACKET_SIZE);

		log(String.format("New socket buffers: %d receive, %d send",
				socket.getReceiveBufferSize(), socket.getSendBufferSize()));
	}

	/** AdhocPacketThread constructor */
	public AdhocPacketThread(Handler p_, RoadRunnerService rrs_) {
		this.parentHandler = p_;
		this.rrs = rrs_;

		// Figure out my local IP address
		localIPAddress = null;
		try {
			NetworkInterface intf = NetworkInterface
					.getByName(Globals.ADHOC_IFACE_NAME);

			// Loop through all the addresses
			for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr
					.hasMoreElements();) {
				InetAddress inetAddress = enumIpAddr.nextElement();
				if (!inetAddress.isLoopbackAddress()
						&& (inetAddress instanceof Inet4Address)) {
					localIPAddress = (Inet4Address) inetAddress;
					break;
				}
			}

			// Check that we got a hit
			if (localIPAddress == null)
				throw new IOException("no addresses bound to "
						+ Globals.ADHOC_IFACE_NAME);
		} catch (IOException e) {
			log("can't determine local IPv4 address: " + e.toString());
			return;
		}
		log("determined local IPv4 address: " + localIPAddress.getHostAddress());

		// Setup remote address
		try {
			remoteIPAddress = InetAddress
					.getByName(Globals.ADHOC_SEND_REMOTE_ADDRESS);
		} catch (UnknownHostException e1) {
			log("Exception getting adhoc send address");
			e1.printStackTrace();
		}

		// Send socket
		try {
			sendSocket = new DatagramSocket(50000); // any free port
			log(String.format("sendSocket is bound to local address %s:%d",
					sendSocket.getLocalAddress().getHostAddress(),
					sendSocket.getLocalPort()));
			sendSocket.setBroadcast(true);
			log(String.format("sendSocket.getBroadcast() is %s",
					(sendSocket.getBroadcast()) ? "true" : "false"));
			sendSocketOK = true;
		} catch (SocketException e1) {
			log("Cannot setup send socket: " + e1.getMessage());
			e1.printStackTrace();
			return;
		}

		// Receive socket
		try {
			recvSocket = new DatagramSocket(Globals.ADHOC_RECV_PORT);
			log(String.format("recvSocket is bound to local address %s:%d",
					recvSocket.getLocalAddress().getHostAddress(),
					recvSocket.getLocalPort()));
			recvSocket.setBroadcast(true);
			log(String.format("recvSocket.getBroadcast() is %s",
					(recvSocket.getBroadcast()) ? "true" : "false"));
			recvSocketOK = true;
		} catch (SocketException e1) {
			log("Cannot setup socket: " + e1.getMessage());
			e1.printStackTrace();
			return;
		}

		log("AdhocPacketThread started.");
	}

	/** Close the socket before exiting the application */
	public synchronized void close() {
		if (recvSocket != null && !recvSocket.isClosed()) {
			recvSocket.close();
			log("closed adhoc recv socket");
		}
		if (sendSocket != null && !sendSocket.isClosed()) {
			sendSocket.close();
			log("closed adhoc send socket");
		}
	}

	/** Thread's receive loop for UDP packets */
	@Override
	public void run() {
		byte[] receiveData = new byte[Globals.ADHOC_MAX_PACKET_SIZE];

		try {
			while (recvSocketOK && sendSocketOK) {
				DatagramPacket dPacket = new DatagramPacket(receiveData,
						receiveData.length);
				recvSocket.receive(dPacket);

				if (dPacket.getAddress().equals(localIPAddress))
					continue; // ignore our own UDP broadcasts

				AdhocPacket p = readPacket(dPacket.getData(),
						dPacket.getLength());

				/*
				 * log_nodisplay(String.format(
				 * "received %d byte adhoc packet type %d", dPacket.getLength(),
				 * p.type));
				 */

				parentHandler.obtainMessage(
						RoadRunnerService.ADHOC_PACKET_RECV, p).sendToTarget();
			}
		} catch (Exception e) {
			log("Exception on recvSocket.receive: " + e.getMessage());
			recvSocketOK = false;
		}

		log("AdhocPacketThread exiting.");
	} // end run()

	/** Return our stored local IP address. */
	public synchronized InetAddress getLocalAddress() {
		return localIPAddress;
	}

	/**
	 * Deserialize a UDP packet back into an AdhocPacket object
	 * 
	 * @throws Exception
	 */
	private AdhocPacket readPacket(byte[] data, int length) {
		AdhocPacket adhocPacket = null;
		ByteArrayInputStream bis = new ByteArrayInputStream(data);
		ObjectInputStream ois = null;
		try {
			ois = new ObjectInputStream(bis);
			adhocPacket = (AdhocPacket) ois.readObject();
			ois.close();
		} catch (IOException e) {
			log("error on new ObjectInputStream: " + e.getMessage());
			e.printStackTrace();
		} catch (ClassNotFoundException e) {
			log("ClassNotFoundException from ois: " + e.getMessage());
			e.printStackTrace();
		}
		adhocPacket.length = length;
		return adhocPacket;
	}
}