package org.servalproject.succinct;

import android.app.Application;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.preference.PreferenceManager;

import org.servalproject.succinct.chat.ChatDatabase;
import org.servalproject.succinct.chat.StoredChatMessage;
import org.servalproject.succinct.messaging.MessageQueue;
import org.servalproject.succinct.messaging.rock.RockMessaging;
import org.servalproject.succinct.networking.Networks;
import org.servalproject.succinct.networking.PeerId;
import org.servalproject.succinct.storage.RecordIterator;
import org.servalproject.succinct.storage.Storage;
import org.servalproject.succinct.storage.StorageWatcher;
import org.servalproject.succinct.team.MembershipList;
import org.servalproject.succinct.team.Team;
import org.servalproject.succinct.team.TeamMember;

import java.io.IOException;

public class App extends Application {
	public static Handler UIHandler;
	private RockMessaging rock;
	private SharedPreferences prefs;
	public Storage teamStorage;
	public Networks networks;
	public MembershipList membershipList;
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

	// All the preference names we're using anywhere in the app
	public static final String MY_ID = "my_id";
	public static final String TEAM_ID = "team_id";
	public static final String MY_NAME = "my_name";
	public static final String MY_EMPLOYEE_ID = "my_employee_id";
	public static final String PAIRED_ROCK = "paired_rock";
	public static final String SMS_DESTINATION = "sms_destination";
	public static final String BASE_SERVER_URL = "base_server_url";

	private PeerId fromPreference(SharedPreferences prefs, String pref){
		String id = prefs.getString(pref, null);
		if (id==null || id.length() != PeerId.LEN*2)
			return null;
		return new PeerId(id);
	}

	public SharedPreferences getPrefs(){
		return prefs;
	}

	@Override
	public void onCreate() {
		super.onCreate();
		UIHandler = new Handler(this.getMainLooper());
		HandlerThread backgroundThread = new HandlerThread("Background");
		backgroundThread.start();
		backgroundHandler = new Handler(backgroundThread.getLooper());

		try {
			prefs = PreferenceManager.getDefaultSharedPreferences(this);
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
		String myId = prefs.getString(App.MY_EMPLOYEE_ID, null);
		TeamMember me = new TeamMember(myId, myName);
		PeerId teamId = new PeerId();
		Storage storage = new Storage(this, teamId);
		Team team = new Team(System.currentTimeMillis(), teamId, networks.myId, name);
		storage.appendRecord(Team.factory, teamId, team);
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
		String myId = prefs.getString(App.MY_EMPLOYEE_ID, null);
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

	private void postJoinTeam() throws IOException {
		membershipList = MembershipList.getInstance(teamStorage);
		backgroundHandler.post(new Runnable() {
			@Override
			public void run() {
				MessageQueue.init(App.this);
				StorageWatcher<StoredChatMessage> chatWatcher = new StorageWatcher<StoredChatMessage>(backgroundHandler, teamStorage, StoredChatMessage.factory) {
					@Override
					protected void Visit(PeerId peer, RecordIterator<StoredChatMessage> records) throws IOException {
						// todo wait until we have peer's name from id file
						records.reset("imported");
						ChatDatabase db = ChatDatabase.getInstance(App.this);
						// todo handle duplicate records in case previous process inserted without saving mark?
						db.insert(teamStorage.teamId, peer, records);
						records.mark("imported");
					}
				};
				chatWatcher.activate();
			}
		});
	}
}
