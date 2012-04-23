package edu.mit.csail.jasongao.roadrunner;

import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeech.OnInitListener;
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

public class MainActivity extends Activity implements OnInitListener {
	final static private String TAG = "MainActivity";

	// UI
	ArrayAdapter<String> receivedMessages;

	// Logging to file
	File logFile;
	PrintWriter logWriter;

	// RoadRunnerService
	RoadRunnerService mService;
	boolean mBound = false;

	// TTS
	TextToSpeech mTts;

	// GPS clock sync
	public static boolean clockSynced = false;
	public static long clockOffset = 0;

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
		log_selectable(line, true);
	}

	/** Log message only to file. */
	public void log_nodisplay(String line) {
		log_selectable(line, false);
	}

	public void log_selectable(String line, boolean display) {
		line = String.format("%d: %s", getTime(), line);
		Log.i(TAG, line);
		if (display) {
			receivedMessages.add((String) line);
		}
		if (logWriter != null) {
			logWriter.println((String) line);
		}
	}

	public static long getTime() {
		return System.currentTimeMillis() + MainActivity.clockOffset;
	}

	public void say(String msg) {
		if (mTts != null) {
			mTts.speak(msg, TextToSpeech.QUEUE_ADD, null);
		}
	}

	/***********************************************
	 * Experiments
	 ***********************************************/

	private boolean experimentsRunning = false;
	private int experimentNumber = 1;

	public Runnable endExperiment = new Runnable() {
		public void run() {
			log(String.format("------ ENDING EXPERIMENT %d ------",
					experimentNumber));
			say("End of experiment. Please return to the starting point.");

			// Reset server
			mService.resetCloud();

			// Stop service
			doUnbindService();

			// Wait 1 minute before starting next experiment
			log(String.format("------ END OF EXPERIMENT %d ------",
					experimentNumber));
			experimentNumber++;
			myHandler.postDelayed(startExperiment,
					Globals.EXPERIMENT_START_DELAY);
		}
	};

	// Cloud-only experiment
	public Runnable startExperiment = new Runnable() {
		public void run() {
			startExperimentNum();
		}
	};

	public void startExperimentNum() {
		log(String.format("------ STARTING EXPERIMENT %d ------",
				experimentNumber));
		say(String.format("Starting experiment %d.", experimentNumber));

		switch (experimentNumber) {
		case 1:
			// Start service with adhoc off
			((CheckBox) findViewById(R.id.adhoc_checkbox)).setChecked(false);
			((CheckBox) findViewById(R.id.ondemand_checkbox)).setChecked(false);
			doBindService();
			say("Exit the parking lot and turn left onto Main Street.");
			break;
		case 2:
			// Start service with adhoc on, prereserve
			((CheckBox) findViewById(R.id.adhoc_checkbox)).setChecked(true);
			((CheckBox) findViewById(R.id.ondemand_checkbox)).setChecked(false);
			doBindService();
			say("Exit the parking lot and turn left onto Main Street.");
			break;
		case 3:
			// Start service with adhoc on, prereserve
			((CheckBox) findViewById(R.id.adhoc_checkbox)).setChecked(true);
			((CheckBox) findViewById(R.id.ondemand_checkbox)).setChecked(true);
			doBindService();
			say("Exit the parking lot and turn left onto Main Street.");
			break;
		default:
			say("No experiments left to do. Please return to the starting point.");
			break;
		}

		// After 7 minutes, end the experiment
		myHandler.postDelayed(endExperiment, Globals.EXPERIMENT_LENGTH);
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
		findViewById(R.id.start_stop_button).setOnClickListener(mClicked);

		receivedMessages = new ArrayAdapter<String>(this, R.layout.message);
		((ListView) findViewById(R.id.msgList)).setAdapter(receivedMessages);

		// Setup writing to log file on sd card
		String state = Environment.getExternalStorageState();
		if (Environment.MEDIA_MOUNTED.equals(state)) {
			// We can read and write the media
			logFile = new File(Environment.getExternalStorageDirectory(),
					String.format("roadrunner-%d.txt",
							System.currentTimeMillis()));
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

		mTts = new TextToSpeech(this, this);
		mTts.setLanguage(Locale.US);
		// mTts.setLanguage(Locale.ENGLISH);
	}

	@Override
	public void onInit(int arg0) {
		// TODO Auto-generated method stub
	}

	@Override
	public void onDestroy() {
		doUnbindService();

		logWriter.flush();
		logWriter.close();

		if (mTts != null) {
			mTts.stop();
			mTts.shutdown();
		}

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
			case R.id.start_stop_button:
				myHandler.removeCallbacks(startExperiment);
				myHandler.removeCallbacks(endExperiment);
				if (!experimentsRunning) {
					myHandler.post(startExperiment);
					((Button) findViewById(R.id.connect_button))
							.setText("STOP experiments");
				} else {
					myHandler.post(endExperiment);
					((Button) findViewById(R.id.connect_button))
							.setText("START experiments");
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

			default:
				break;
			}
		}
	};

}