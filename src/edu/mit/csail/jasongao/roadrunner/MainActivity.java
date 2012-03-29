package edu.mit.csail.jasongao.roadrunner;

import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import edu.mit.csail.jasongao.roadrunner.RoadRunnerService.LocalBinder;

public class MainActivity extends Activity {
	final static private String TAG = "MainActivity";

	// UI
	ArrayAdapter<String> receivedMessages;

	// Logging to file
	File logFile;
	PrintWriter logWriter;

	// RoadRunnerService
	RoadRunnerService mService;
	boolean mBound = false;

	/***********************************************
	 * Handler for messages from other components
	 ***********************************************/

	// Handler message types
	protected final static int LOG_NODISPLAY = 2;
	protected final static int LOG = 3;
	protected final static int UPDATE_DISPLAY = 4;

	/** Handle messages from various components */
	private final Handler myHandler = new Handler() {
		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
			case LOG: // Write a string to log file and UI log display
				log((String) msg.obj);
				break;
			case LOG_NODISPLAY: // Write a string to log file and UI log display
				log_nodisplay((String) msg.obj);
				break;
			case UPDATE_DISPLAY: // Update status display TextViews
				List<String> update = (ArrayList<String>) msg.obj;

				TextView regionTv = (TextView) findViewById(R.id.region_tv);
				TextView reservationsTv = (TextView) findViewById(R.id.reservations_tv);
				TextView extrasTv = (TextView) findViewById(R.id.extras_tv);

				regionTv.setText(update.get(0));
				reservationsTv.setText(update.get(1));
				extrasTv.setText(update.get(2));

				break;
			}
		}
	};

	/** Log message and also display on screen */
	public void log(String line) {
		line = String.format("%s", line);
		Log.i(TAG, line);
		receivedMessages.add((String) line);
		if (logWriter != null) {
			logWriter.println((String) line);
		}
	}

	/** Log message only to file. */
	public void log_nodisplay(String line) {
		line = String.format("%s", line);
		Log.i(TAG, line);
		if (logWriter != null) {
			logWriter.println((String) line);
		}
	}

	/***********************************************
	 * Android lifecycle
	 ***********************************************/

	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// UI
		setContentView(R.layout.main);
		findViewById(R.id.connect_button).setOnClickListener(mClicked);
		findViewById(R.id.reset_button).setOnClickListener(mClicked);
		findViewById(R.id.reserve_button_a).setOnClickListener(mClicked);
		findViewById(R.id.reserve_button_b).setOnClickListener(mClicked);
		findViewById(R.id.reserve_button_c).setOnClickListener(mClicked);

		receivedMessages = new ArrayAdapter<String>(this, R.layout.message);
		((ListView) findViewById(R.id.msgList)).setAdapter(receivedMessages);

		// Setup writing to log file on sd card
		String state = Environment.getExternalStorageState();
		if (Environment.MEDIA_MOUNTED.equals(state)) {
			// We can read and write the media
			logFile = new File(Environment.getExternalStorageDirectory(),
					String.format("roadrunner-%d.txt", System.currentTimeMillis()));
			try {
				logWriter = new PrintWriter(logFile);
				log("Opened log file for writing");
			} catch (Exception e) {
				logWriter = null;
				log("Couldn't open log file for writing");
			}
		} else if (Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) {
			// We can only read the media
		} else {
			// One of many other states, but we can neither read nor write
		}
	}

	@Override
	public void onDestroy() {
		doUnbindService();

		logWriter.flush();
		logWriter.close();

		super.onDestroy();
	}

	/***********************************************
	 * Service binding
	 ***********************************************/

	/** Defines callbacks for service binding, passed to bindService() */
	private ServiceConnection mServiceConnection = new ServiceConnection() {

		@Override
		public void onServiceConnected(ComponentName className, IBinder service) {
			// We've bound to RoadRunnerService, cast the IBinder and get
			// instance
			LocalBinder binder = (LocalBinder) service;
			mService = binder.getService(myHandler);
			mBound = true;
			((Button) findViewById(R.id.connect_button))
					.setText("Stop RoadRunner");

			Toast.makeText(getApplicationContext(), "Service connected.",
					Toast.LENGTH_LONG).show();

			// Start the service
			CheckBox adhocCheckBox = (CheckBox) findViewById(R.id.adhoc_checkbox);
			mService.start(adhocCheckBox.isChecked());
		}

		/** If service disconnects -unexpectedly- / crashes */
		@Override
		public void onServiceDisconnected(ComponentName arg0) {
			mBound = false;
			((Button) findViewById(R.id.connect_button))
					.setText("Start RoadRunner");
			Toast.makeText(getApplicationContext(), "Service disconnected.",
					Toast.LENGTH_LONG).show();

		}
	};

	void doBindService() {
		// Bind to LocalService
		Intent intent = new Intent(this, RoadRunnerService.class);
		bindService(intent, mServiceConnection, Context.BIND_AUTO_CREATE);
	}

	void doUnbindService() {
		if (mBound) {
			mService.stop();
			unbindService(mServiceConnection);
			mBound = false;
			((Button) findViewById(R.id.connect_button))
					.setText("Start RoadRunner");

			Toast.makeText(getApplicationContext(), "Service disconnected.",
					Toast.LENGTH_LONG).show();
		}
	}

	/***********************************************
	 * ResRequest
	 ***********************************************/

	// Buttons
	private final OnClickListener mClicked = new OnClickListener() {
		public void onClick(View v) {
			switch (v.getId()) {
			case R.id.reserve_button_a:
				if (mBound) {
					mService.makeReservationRouteA();
				}
				break;
			case R.id.reserve_button_b:
				if (mBound) {
					mService.makeReservationRouteB();
				}
				break;
			case R.id.reserve_button_c:
				if (mBound) {
					mService.makeReservationRouteC();
				}
				break;
			case R.id.connect_button:
				if (!mBound) {
					doBindService();
				} else {
					doUnbindService();
				}
				break;
			case R.id.reset_button:
				if (mBound) {
					mService.resetCloud();
				}
				break;
			}
		}
	};

}