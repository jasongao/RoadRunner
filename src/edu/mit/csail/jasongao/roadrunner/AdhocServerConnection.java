package edu.mit.csail.jasongao.roadrunner;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;

import android.telephony.TelephonyManager;

public class AdhocServerConnection implements Runnable {
	public static final String TAG = "AdhocServerConnection";

	private Socket mSocket;
	private long id;
	private RoadRunnerService rrs;

	AdhocServerConnection(Socket s, long i, RoadRunnerService rrs_) {
		this.mSocket = s;
		this.id = i;
		this.rrs = rrs_;
	}

	public void log(String line) {
		rrs.log(String.format("%d - %s - %s", this.id,
				this.mSocket.getRemoteSocketAddress(), line));
	}

	@Override
	public void run() {
		Socket clientSocket = mSocket;

		log("Connected.");

		try {
			BufferedReader clientReader = new BufferedReader(
					new InputStreamReader(clientSocket.getInputStream()));
			Writer clientWriter = new OutputStreamWriter(
					clientSocket.getOutputStream());

			// read request from socket
			String request = clientReader.readLine();
			// log("Received request: " + request);
			String[] parts = request.split(" ");
			if (parts.length != 3) {
				log(String.format(
						"Improperly formatted request line from %s, exiting.",
						clientSocket.getRemoteSocketAddress()));
				mSocket.close();
				return;
			}

			String method = parts[0];
			long otherId = Long.parseLong(parts[1]);
			String regionId = parts[2];
			String response;

			if ("GET".equals(method)) {
				// respond with token from offered tokens store if possible
				ResRequest offer = RoadRunnerService.queuePoll(rrs.offers,
						regionId);
				if (offer != null) {
					log(String
							.format("Responding to GET request from %d with an offered reservation.",
									otherId));
					response = String.format("GET 200 OK\r\n%s\r\n%s\r\n\r\n",
							offer.tokenString, offer.signature);
					clientWriter.write(response);
					clientWriter.flush();

					rrs.updateDisplay();
				}
				// relay request if cellular link is not dormant
				else if (Globals.RELAY_ENABLED
						&& rrs.tm.getDataActivity() != TelephonyManager.DATA_ACTIVITY_DORMANT) {
					log(String.format(
							"Relaying vehicle %d GET request to cloud.",
							otherId));
					Socket cloudSocket = new Socket();
					try {
						cloudSocket.connect(new InetSocketAddress(
								Globals.CLOUD_HOST, Globals.CLOUD_PORT),
								Globals.CLOUD_SOCKET_TIMEOUT);

						BufferedReader cloudReader = new BufferedReader(
								new InputStreamReader(
										cloudSocket.getInputStream()));
						Writer cloudWriter = new OutputStreamWriter(
								cloudSocket.getOutputStream());

						// Send the original GET request
						String line = String.format("GET %d %s\r\n", otherId,
								regionId);
						cloudWriter.write(line);
						cloudWriter.flush();

						// relay response back to client
						String relayResponse;
						relayResponse = cloudReader.readLine();

						log(String.format("Relaying cloud response to %d: %s",
								otherId, relayResponse));

						if ("GET 200 OK".equals(relayResponse)) {
							clientWriter.write(relayResponse + "\r\n");
							clientWriter.write(cloudReader.readLine() + "\r\n");
							clientWriter.write(cloudReader.readLine() + "\r\n");
							clientWriter.write(cloudReader.readLine() + "\r\n");
							clientWriter.flush();
						} else { // not OK
							clientWriter.write(relayResponse + "\r\n");
							clientWriter.flush();
						}

						rrs.myHandler.post(rrs.updateLastDataActivity);

					} catch (IOException e) {
						log("Unexpected I/O error or natural shutdown: "
								+ e.toString());
					} finally {
						try {
							cloudSocket.shutdownOutput();
						} catch (IOException e) {
						}

						try {
							cloudSocket.shutdownInput();
						} catch (IOException e) {
						}

						try {
							cloudSocket.close();
						} catch (IOException e) {
						}
					}
				}
				// otherwise respond failure
				else {
					log("Responding to request, no token locally available.");
					response = String.format("GET 404 ERROR\r\n");
					clientWriter.write(response);
					clientWriter.flush();
				}
			}
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			try {
				mSocket.close();
			} catch (IOException e) {
			}
		}
	}
}