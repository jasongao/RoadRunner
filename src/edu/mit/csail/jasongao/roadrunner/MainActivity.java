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
	private boolean firstSet = false;
	private int experimentNumber = 0;

	private Runnable endExperimentR = new Runnable() {
		public void run() {
			log(String.format("##### endExperimentR %d", experimentNumber));
			logWriter.flush();

			switch (experimentNumber) {
			case 0:
				say("Continue driving along the default route.");
				experimentNumber++;
				myHandler.postDelayed(resetServerR, Globals.RESET_SERVER_DELAY);
				break;
			case 1:
				say("Continue driving along the default route.");
				experimentNumber++;
				myHandler.postDelayed(resetServerR, Globals.RESET_SERVER_DELAY);
				break;
			case 2:
				say("Continue driving along the default route.");
				experimentNumber++;
				myHandler.postDelayed(resetServerR, Globals.RESET_SERVER_DELAY);
				break;
			case 3:
				say("Continue driving along the default route.");
				experimentNumber++;
				myHandler.postDelayed(resetServerR, Globals.RESET_SERVER_DELAY);
				break;
			case 4:
				say("Continue driving along the default route.");
				experimentNumber++;
				myHandler.postDelayed(resetServerR, Globals.RESET_SERVER_DELAY);
				break;
			case 5:
				say("Continue driving along the default route.");
				experimentNumber++;
				myHandler.postDelayed(resetServerR, Globals.RESET_SERVER_DELAY);
				break;
			case 6:
				say("Continue driving along the default route.");
				experimentNumber++;
				myHandler.postDelayed(resetServerR, Globals.RESET_SERVER_DELAY);
				break;
			case 7:
				say("Please go to Vassar Street and loop back and forth, making U turns as necessary.");
				experimentNumber++;
				myHandler.postDelayed(resetServerR, Globals.RESET_SERVER_DELAY);
				break;
			default:
				if (firstSet) {
					log("##### BANANA TIME");
					logWriter.flush();
					say("Banana time. Banana time. Please follow the instructions on the flyer for banana time.");
				} else {
					say("Experiments complete. Please return or park your vehicle and bring me back to the Stata Center.");
				}
				break;
			}
		}
	};

	/** Reset the server in the interlude between experiments. */
	private Runnable resetServerR = new Runnable() {
		public void run() {
			log("------ SERVER RESET ------");
			if (mService != null) {
				mService.resetCloud();
			}
			doUnbindService();

			myHandler.postDelayed(startExperimentR, Globals.EXPT_START_DELAY);
		}
	};

	// Cloud-only experiment
	private Runnable startExperimentR = new Runnable() {
		public void run() {
			startExperimentNum();
		}
	};

	private void startExperimentNum() {
		log(String.format("------ STARTING EXPERIMENT %d ------",
				experimentNumber));
		say(String.format("Starting experiment %d.", experimentNumber));

		switch (experimentNumber) {
		case 0:
			// DEP: Go to endExperimentR for setup
			experimentNumber++;
			
			// Wait until 10:20am to start first experiment
			if (firstSet) {
				long timeUntilFirstExperiment = Globals.FRIDAY_10_20_AM
						- System.currentTimeMillis();
				log(String.format("%d - %d = %d msecs until 10:20AM",
						Globals.FRIDAY_10_20_AM,
						System.currentTimeMillis(),
						timeUntilFirstExperiment));
				myHandler.postDelayed(resetServerR,
						timeUntilFirstExperiment);
				say(String.format("Synchornizing in %5d minutes.",
						(int) (timeUntilFirstExperiment / 1000 / 60)));
			} else { // wait until 11:35 am to start second set
				long timeUntilSecondExperiment = Globals.FRIDAY_11_35_AM
						- System.currentTimeMillis();
				log(String.format("%d - %d = %d msecs until 11:35AM",
						Globals.FRIDAY_11_35_AM,
						System.currentTimeMillis(),
						timeUntilSecondExperiment));
				myHandler.postDelayed(resetServerR,
						timeUntilSecondExperiment);
				say(String.format("Synchornizing in %5d minutes.",
						(int) (timeUntilSecondExperiment / 1000 / 60)));
			}
		case 1:
			// Adhoc OFF, 4G Cloud-only, Trial 1
			// say("WiFi off. Cloud only. Trial 1.");
			Globals.REQUEST_DIRECT_PUT_DEADLINE_FROM_NOW = 2000;
			Globals.REQUEST_DIRECT_GET_DEADLINE_FROM_NOW = 2000;
			((CheckBox) findViewById(R.id.adhoc_checkbox)).setChecked(false);
			((CheckBox) findViewById(R.id.ondemand_checkbox)).setChecked(false);
			doBindService();
			say("Exit the starting area and turn onto Vassar Street.");
			// say("Exit the parking lot and turn right onto Main Street.");

			// End the experiment after a while
			myHandler.postDelayed(endExperimentR, Globals.EXPT_LENGTH);
			break;
		case 2:
			// Adhoc OFF, 4G Cloud-only, Trial 2
			// say("WiFi off. Cloud only. Trial 2.");
			Globals.REQUEST_DIRECT_PUT_DEADLINE_FROM_NOW = 2000;
			Globals.REQUEST_DIRECT_GET_DEADLINE_FROM_NOW = 2000;
			((CheckBox) findViewById(R.id.adhoc_checkbox)).setChecked(false);
			((CheckBox) findViewById(R.id.ondemand_checkbox)).setChecked(false);
			doBindService();
			say("Continue driving along the default route.");

			// End the experiment after a while
			myHandler.postDelayed(endExperimentR, Globals.EXPT_LENGTH);
			break;
		case 3:
			// Adhoc ON, PRERESERVE Tokens, Trial 1
			// say("WiFi on. Pre-reserved tokens. Trial 1.");
			Globals.REQUEST_DIRECT_PUT_DEADLINE_FROM_NOW = 60000;
			Globals.REQUEST_DIRECT_GET_DEADLINE_FROM_NOW = 30000;
			((CheckBox) findViewById(R.id.adhoc_checkbox)).setChecked(true);
			((CheckBox) findViewById(R.id.ondemand_checkbox)).setChecked(false);
			doBindService();
			say("Continue driving along the default route.");

			// End the experiment after a while
			myHandler.postDelayed(endExperimentR, Globals.EXPT_LENGTH);
			break;
		case 4:
			// Adhoc ON, PRERESERVE Tokens, Trial 2
			// say("WiFi on. Pre-reserved tokens. Trial 2.");
			Globals.REQUEST_DIRECT_PUT_DEADLINE_FROM_NOW = 60000;
			Globals.REQUEST_DIRECT_GET_DEADLINE_FROM_NOW = 30000;
			((CheckBox) findViewById(R.id.adhoc_checkbox)).setChecked(true);
			((CheckBox) findViewById(R.id.ondemand_checkbox)).setChecked(false);
			doBindService();
			say("Continue driving along the default route.");

			// End the experiment after a while
			myHandler.postDelayed(endExperimentR, Globals.EXPT_LENGTH);
			break;
		case 5:
			// Adhoc ON, ONDEMAND Tokens, Trial 1
			// say("WiFi on. On-demand tokens. Trial 1.");
			Globals.REQUEST_DIRECT_PUT_DEADLINE_FROM_NOW = 10000;
			Globals.REQUEST_DIRECT_GET_DEADLINE_FROM_NOW = 10000;
			((CheckBox) findViewById(R.id.adhoc_checkbox)).setChecked(true);
			((CheckBox) findViewById(R.id.ondemand_checkbox)).setChecked(true);
			doBindService();
			say("Continue driving along the default route.");

			// End the experiment after a while
			myHandler.postDelayed(endExperimentR, Globals.EXPT_LENGTH);
			break;
		case 6:
			// Adhoc ON, ONDEMAND Tokens, Trial 2
			// say("WiFi on. On-demand tokens. Trial 2.");
			Globals.REQUEST_DIRECT_PUT_DEADLINE_FROM_NOW = 10000;
			Globals.REQUEST_DIRECT_GET_DEADLINE_FROM_NOW = 10000;
			((CheckBox) findViewById(R.id.adhoc_checkbox)).setChecked(true);
			((CheckBox) findViewById(R.id.ondemand_checkbox)).setChecked(true);
			doBindService();
			say("Continue driving along the default route.");

			// End the experiment after a while
			myHandler.postDelayed(endExperimentR, Globals.EXPT_LENGTH);
			break;
		case 7:
			// Adhoc ON, SUPERDENSE Tokens, Trial 1
			// say("WiFi on. Super dense tokens. Trial 1.");
			Globals.REQUEST_DIRECT_PUT_DEADLINE_FROM_NOW = 10 * 60 * 1000;
			Globals.REQUEST_DIRECT_PUT_DEADLINE_FROM_NOW = 10 * 60 * 1000;
			((CheckBox) findViewById(R.id.adhoc_checkbox)).setChecked(true);
			((CheckBox) findViewById(R.id.ondemand_checkbox)).setChecked(true);
			doBindService();
			say("Please go to Vassar Street and loop back and forth, making U turns as necessary.");

			// End the experiment after a while
			myHandler.postDelayed(endExperimentR, Globals.EXPT_LENGTH);
			break;
		default:
			say("No experiments left to do. Please return to the starting point.");
			break;
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
		findViewById(R.id.start_stop_button).setOnClickListener(mClicked);

		receivedMessages = new ArrayAdapter<String>(this, R.layout.message);
		((ListView) findViewById(R.id.msgList)).setAdapter(receivedMessages);

		// Setup writing to log file on sd card
		String state = Environment.getExternalStorageState();
		if (Environment.MEDIA_MOUNTED.equals(state)) {
			// We can read and write the media
			logFile = new File(Environment.getExternalStorageDirectory(),
					String.format("roadrunner/roadrunner-%d.txt",
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

		if (logWriter != null) {
			logWriter.flush();
			logWriter.close();
		}

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
					.setText("Unbind Service");

			Toast.makeText(getApplicationContext(), "Service connected.",
					Toast.LENGTH_LONG).show();

			// Start the service
			CheckBox adhocCheckBox = (CheckBox) findViewById(R.id.adhoc_checkbox);
			CheckBox ondemandCheckBox = (CheckBox) findViewById(R.id.ondemand_checkbox);
			CheckBox dirCheckBox = (CheckBox) findViewById(R.id.direction_checkbox);
			mService.start(mTts, adhocCheckBox.isChecked(),
					ondemandCheckBox.isChecked(), dirCheckBox.isChecked());
		}

		/** If service disconnects -unexpectedly- / crashes */
		@Override
		public void onServiceDisconnected(ComponentName arg0) {
			mBound = false;
			((Button) findViewById(R.id.connect_button))
					.setText("Bind Service");
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
					.setText("Bind Service");

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
				myHandler.removeCallbacks(startExperimentR);
				myHandler.removeCallbacks(endExperimentR);
				myHandler.removeCallbacks(resetServerR);
				CheckBox firstSetCheckbox = (CheckBox) findViewById(R.id.firstset_checkbox);
				firstSet = firstSetCheckbox.isChecked();
				if (firstSet) {
					Globals.EXPT_LENGTH = 10 * 60 * 1000; // 10 min trials
				} else {
					Globals.EXPT_LENGTH = 5 * 60 * 1000; // 5 min trials
				}
				log(String.format("Globals.EXPT_LENGTH = %d",
						Globals.EXPT_LENGTH));

				if (!experimentsRunning) {
					experimentsRunning = true;
					myHandler.post(startExperimentR);
					((Button) findViewById(R.id.start_stop_button))
							.setText("STOP experiments");
				} else {
					experimentsRunning = false;
					// myHandler.post(endExperimentR);
					((Button) findViewById(R.id.start_stop_button))
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