package edu.mit.csail.jasongao.roadrunner;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.channels.DatagramChannel;
import java.util.Enumeration;

import android.os.Handler;
import android.util.Log;

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
		sendSocket.send(new DatagramPacket(sendData, sendData.length,
				remoteIPAddress, Globals.ADHOC_SEND_PORT));
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
			for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr
					.hasMoreElements();) {
				InetAddress inetAddress = enumIpAddr.nextElement();
				if (!inetAddress.isLoopbackAddress()) {
					localIPAddress = inetAddress;
				}
			}
			if (localIPAddress == null)
				throw new Exception("no addresses bound to "
						+ Globals.ADHOC_IFACE_NAME);
		} catch (Exception e) {
			Log.e(TAG, "can't determine local IP address: " + e.toString());
			return;
		}
		
		
		try {
			remoteIPAddress = InetAddress
					.getByName(Globals.ADHOC_SEND_REMOTE_ADDRESS);
		} catch (UnknownHostException e1) {
			Log.i(TAG, "Exception getting adhoc send address");
			e1.printStackTrace();
		}
		
		/** Send socket */
		try {
			//DatagramChannel channel = DatagramChannel.open();
			//sendSocket = channel.socket();
			
			//sendSocket = new DatagramSocket();
			
			sendSocket = new DatagramSocket(50000); // any port we can get
			try {
				sendSocket.setBroadcast(true);
			} catch (SocketException e1) {
				Log.i(TAG, "exception setting socket to broadcast");
				e1.printStackTrace();
			}			
			
			Log.i(TAG, String.format("sendSocket is bound to local address %s:%d", sendSocket.getLocalAddress().getHostAddress(), sendSocket.getLocalPort()));
			Log.i(TAG, String.format("sendSocket.getBroadcast() is %s", (sendSocket.getBroadcast())?"true":"false"));
			
			/*
			Log.i(TAG, String.format(
					"Orig socket buffers: %d receive, %d send",
					sendSocket.getReceiveBufferSize(),
					sendSocket.getSendBufferSize()));

			sendSocket.setReceiveBufferSize(Globals.ADHOC_MAX_PACKET_SIZE);
			sendSocket.setSendBufferSize(Globals.ADHOC_MAX_PACKET_SIZE);
			
			Log.i(TAG, String.format("Set socket buffers: %d receive, %d send",
					sendSocket.getReceiveBufferSize(),
					sendSocket.getSendBufferSize()));
			*/
			
			sendSocketOK = true;
		} catch (SocketException e1) {
			Log.i(TAG, "Cannot setup send socket: " + e1.getMessage());
			e1.printStackTrace();
			return;
		} catch (IOException e1) {
			// TODO Auto-generated catch block
			Log.i(TAG, "Cannot setup send channel: " + e1.getMessage());
			e1.printStackTrace();
		}
		
		
		

		/** Receive socket. */
		try {
			//DatagramChannel channel = DatagramChannel.open();
			//recvSocket = channel.socket();
			
			//recvSocket = new DatagramSocket();
			
			recvSocket = new DatagramSocket(Globals.ADHOC_RECV_PORT);
			/*
			try {
				recvSocket.setReuseAddress(true);
				recvSocket.setBroadcast(true);
			} catch (SocketException e1) {
				Log.i(TAG, "exception setting socket to broadcast");
				e1.printStackTrace();
			}
			*/
			
			/*
			try {
				SocketAddress sAddr = new InetSocketAddress(Globals.ADHOC_RECV_PORT);
				//SocketAddress sAddr = new InetSocketAddress(localIPAddress, Globals.ADHOC_RECV_PORT);
				recvSocket.bind(sAddr);
			} catch (SocketException e1) {
				Log.i(TAG, String.format("exception binding socket to local addr %s:%s", localIPAddress.toString(), Globals.ADHOC_RECV_PORT));
				e1.printStackTrace();
			}
			*/
			
			Log.i(TAG, String.format("recvSocket is bound to local address %s:%d", recvSocket.getLocalAddress().getHostAddress(), recvSocket.getLocalPort()));
			Log.i(TAG, String.format("recvSocket.getBroadcast() is %s", (recvSocket.getBroadcast())?"true":"false"));
			
			/*
			Log.i(TAG, String.format(
					"Orig socket buffers: %d receive, %d send",
					recvSocket.getReceiveBufferSize(),
					recvSocket.getSendBufferSize()));

			recvSocket.setReceiveBufferSize(Globals.ADHOC_MAX_PACKET_SIZE);
			recvSocket.setSendBufferSize(Globals.ADHOC_MAX_PACKET_SIZE);
			
			Log.i(TAG, String.format("Set socket buffers: %d receive, %d send",
					recvSocket.getReceiveBufferSize(),
					recvSocket.getSendBufferSize()));
			*/
			
			recvSocketOK = true;
		} catch (SocketException e1) {
			Log.i(TAG, "Cannot setup socket: " + e1.getMessage());
			e1.printStackTrace();
			return;
		} catch (IOException e1) {
			// TODO Auto-generated catch block
			Log.i(TAG, "Cannot setup channel: " + e1.getMessage());
			e1.printStackTrace();
		}

		Log.i(TAG, "started, adhoc IP address:" + localIPAddress);
	}

	/** If not recvSocketOK, then receive loop thread will stop */
	public synchronized boolean recvSocketIsOK() {
		return recvSocketOK;
	}
	
	public synchronized boolean sendSocketIsOK() {
		return sendSocketOK;
	}

	/** Close the socket before exiting the application */
	public synchronized void close() {
		if (recvSocket != null && !recvSocket.isClosed()) {
			recvSocket.close();
			Log.i(TAG, "closed adhoc recv socket");
		}
		if (sendSocket != null && !sendSocket.isClosed()) {
			sendSocket.close();
			Log.i(TAG, "closed adhoc send socket");
		}	
	}

	/** Thread's receive loop for UDP packets */
	@Override
	public void run() {
		byte[] receiveData = new byte[Globals.ADHOC_MAX_PACKET_SIZE];

		try {
			while (recvSocketOK) {
				DatagramPacket dPacket = new DatagramPacket(receiveData,
						receiveData.length);
				recvSocket.receive(dPacket);

				// ignore our own UDP broadcasts
				if (dPacket.getAddress().equals(localIPAddress))
					continue;

				AdhocPacket p = readPacket(dPacket.getData());

				rrs.log_nodisplay(String.format(
						"received %d byte adhoc packet type %d",
						dPacket.getLength(), p.type));

				parentHandler.obtainMessage(
						RoadRunnerService.ADHOC_PACKET_RECV, p)
						.sendToTarget();
			}
		} catch (Exception e) {
			Log.e(TAG, "Exception on recvSocket.receive: " + e.getMessage());
			recvSocketOK = false;
		}

		Log.i(TAG, "AdhocPacketThread exiting.");
	} // end run()

	/** Return our stored local IP address. */
	public synchronized InetAddress getLocalAddress() {
		return localIPAddress;
	}

	/**
	 * Read back a packet.
	 * 
	 * @throws Exception
	 */
	private AdhocPacket readPacket(byte[] data) {
		AdhocPacket adhocPacket = null;
		ByteArrayInputStream bis = new ByteArrayInputStream(data);
		ObjectInputStream ois = null;
		try {
			ois = new ObjectInputStream(bis);
			adhocPacket = (AdhocPacket) ois.readObject();
			ois.close();
		} catch (IOException e) {
			Log.e(TAG, "error on new ObjectInputStream: " + e.getMessage());
			e.printStackTrace();
		} catch (ClassNotFoundException e) {
			Log.e(TAG, "ClassNotFoundException from ois: " + e.getMessage());
			e.printStackTrace();
		}
		return adhocPacket;
	}
}