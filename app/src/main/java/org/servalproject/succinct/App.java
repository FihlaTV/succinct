package org.servalproject.succinct;

import android.app.Application;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.preference.PreferenceManager;

import org.servalproject.succinct.messaging.MessageQueue;
import org.servalproject.succinct.messaging.rock.RockMessaging;
import org.servalproject.succinct.networking.Networks;
import org.servalproject.succinct.networking.PeerId;
import org.servalproject.succinct.storage.Storage;
import org.servalproject.succinct.team.Team;
import org.servalproject.succinct.team.TeamMember;

import java.io.IOException;

public class App extends Application {
	public static Handler UIHandler;
	private RockMessaging rock;
	public Storage teamStorage;
	public Networks networks;
	private TeamMember me;

	// a single background thread for short work tasks
	public static Handler backgroundHandler;

	public RockMessaging getRock(){
		if (rock == null)
			rock = new RockMessaging(this);
		return rock;
	}

	static {
		// ensure our jni library has been loaded
		System.loadLibrary("native-lib");
	}

	public static final String MY_ID = "my_id";
	public static final String TEAM_ID = "team_id";
	public static final String MY_NAME = "my_name";
	public static final String MY_EMPLOYEE_ID = "my_employee_id";

	private PeerId fromPreference(SharedPreferences prefs, String pref){
		String id = prefs.getString(pref, null);
		if (id==null || id.length() != PeerId.LEN*2)
			return null;
		return new PeerId(id);
	}

	@Override
	public void onCreate() {
		super.onCreate();
		UIHandler = new Handler(this.getMainLooper());
		HandlerThread backgroundThread = new HandlerThread("Background");
		backgroundThread.start();
		backgroundHandler = new Handler(backgroundThread.getLooper());

		try {
			SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
			PeerId myId = fromPreference(prefs, MY_ID);
			if (myId == null){
				myId = new PeerId();
				SharedPreferences.Editor ed = prefs.edit();
				ed.putString(MY_ID, myId.toString());
				ed.apply();
			}
			PeerId teamId = fromPreference(prefs, TEAM_ID);
			if (teamId!=null)
				teamStorage = new Storage(this, teamId);
			networks = Networks.init(this, myId);

			if (teamStorage!=null) {
				postJoinTeam();
			}
		} catch (java.io.IOException e) {
			throw new IllegalStateException("");
		}
	}

	public TeamMember getMe() throws IOException {
		if (teamStorage == null)
			return null;
		if (me == null)
			me = teamStorage.getLastRecord(TeamMember.factory, networks.myId);
		return me;
	}

	public void createTeam(String name) throws IOException {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
		String myName = prefs.getString(App.MY_NAME, null);
		int myId = prefs.getInt(App.MY_EMPLOYEE_ID, -1);
		TeamMember me = new TeamMember(myId, myName);
		PeerId teamId = new PeerId();
		Storage storage = new Storage(this, teamId);
		storage.appendRecord(Team.factory, teamId, new Team(teamId, networks.myId, name));
		storage.appendRecord(TeamMember.factory, networks.myId, me);

		teamStorage = storage;
		this.me = me;
		SharedPreferences.Editor ed = prefs.edit();
		ed.putString(TEAM_ID, teamId.toString());
		ed.apply();

		postJoinTeam();
	}

	public void joinTeam(PeerId teamId) throws IOException {
		if (teamStorage!=null)
			throw new IllegalStateException("Already in a team");
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
		String myName = prefs.getString(App.MY_NAME, null);
		int myId = prefs.getInt(App.MY_EMPLOYEE_ID, -1);
		TeamMember me = new TeamMember(myId, myName);

		Storage storage = new Storage(this, teamId);
		storage.appendRecord(TeamMember.factory, networks.myId, me);

		teamStorage = storage;
		this.me = me;
		SharedPreferences.Editor ed = prefs.edit();
		ed.putString(TEAM_ID, teamId.toString());
		ed.apply();
		// trigger a heartbeat now, which should start syncing with existing peers almost immediately
		networks.setAlarm(0);

		postJoinTeam();
	}

	@Override
	public void onTrimMemory(int level) {
		if (rock!=null && level!=TRIM_MEMORY_UI_HIDDEN)
			rock.onTrimMemory(level);
		super.onTrimMemory(level);
	}

	private void postJoinTeam() {
		backgroundHandler.post(new Runnable() {
			@Override
			public void run() {
				MessageQueue.init(App.this);
			}
		});
	}
}
