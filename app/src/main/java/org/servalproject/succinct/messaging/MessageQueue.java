package org.servalproject.succinct.messaging;


import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;

import org.servalproject.succinct.App;
import org.servalproject.succinct.chat.StoredChatMessage;
import org.servalproject.succinct.forms.Form;
import org.servalproject.succinct.networking.PeerId;
import org.servalproject.succinct.storage.Factory;
import org.servalproject.succinct.storage.RecordIterator;
import org.servalproject.succinct.storage.Serialiser;
import org.servalproject.succinct.storage.Storage;
import org.servalproject.succinct.storage.StorageWatcher;
import org.servalproject.succinct.team.Membership;
import org.servalproject.succinct.team.MembershipList;
import org.servalproject.succinct.team.Team;
import org.servalproject.succinct.team.TeamMember;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

// Manage the queue of outgoing messages / fragments
public class MessageQueue {
	private final App app;
	private final Team myTeam;
	private final Storage store;
	private final RecordIterator<Fragment> fragments;
	private final RecordIterator<Membership> members;
	private final RecordIterator<Team> team;
	public final IMessaging[] services;
	private final int MTU;
	private final MembershipList membershipList;
	private final ConnectivityManager connectivityManager;
	private int nextFragmentSeq;
	private static final String TAG = "MessageQueue";

	private final StorageWatcher<TeamMember> memberWatcher;
	private final QueueWatcher<Form> formWatcher;
	private final QueueWatcher<StoredChatMessage> chatWatcher;

	private final ByteBuffer fragmentBuff;

	abstract class QueueWatcher<T> extends StorageWatcher<T>{
		private final HashMap<PeerId, RecordIterator<T>> queue = new HashMap<>();

		public QueueWatcher(Storage store, Factory<T> factory) {
			super(App.backgroundHandler, store, factory);
			activate();
		}

		boolean findNext(PeerId peer, RecordIterator<T> records) throws IOException {
			return records.next();
		}
		abstract void generateMessage(PeerId peer, RecordIterator<T> records) throws IOException;

		@Override
		protected void Visit(PeerId peer, RecordIterator<T> records) throws IOException {
			records.reset("sent");
			if (findNext(peer, records)){
				if (queue.containsKey(peer))
					return;
				Log.v(TAG, "Remembering "+peer+"/"+records.getFactory().getFileName());
				queue.put(peer, records);
				App.backgroundHandler.removeCallbacks(sendRunner);
				App.backgroundHandler.postDelayed(sendRunner, 500);
			}
		}

		boolean hasMessage(){
			return !queue.isEmpty();
		}

		boolean nextMessage() throws IOException {
			Iterator<Map.Entry<PeerId, RecordIterator<T>>> i = queue.entrySet().iterator();
			while(i.hasNext()) {
				Map.Entry<PeerId, RecordIterator<T>> e = i.next();
				PeerId peerId = e.getKey();

				// always skip peers if they aren't enrolled
				if (!membershipList.isActive(peerId)) {
					Log.v(TAG, "Skipping " + peerId+", not enrolled?");
					continue;
				}

				if (membershipList.getPosition(peerId)>255) {
					Log.v(TAG, "Skipping " + peerId+", too many?");
					continue;
				}

				RecordIterator<T> iterator = e.getValue();
				iterator.reset("sent");

				if (!findNext(peerId, iterator)) {
					Log.v(TAG, "Skipping " + peerId+", no interesting records?");
					i.remove();
					continue;
				}

				generateMessage(peerId, iterator);

				if (!findNext(peerId, iterator))
					i.remove();
				iterator.mark("sent");
				return true;
			}
			return false;
		}
	}

	public MessageQueue(App app, final Team myTeam) throws IOException {
		this.app = app;
		connectivityManager = (ConnectivityManager)app.getSystemService(Context.CONNECTIVITY_SERVICE);
		store = app.teamStorage;
		this.myTeam = myTeam;
		membershipList = app.membershipList;
		members = store.openIterator(Membership.factory, store.teamId);
		team = store.openIterator(Team.factory, store.teamId);
		team.reset("sent");

		fragments = store.openIterator(Fragment.factory, "messaging");
		Fragment last = fragments.readLast();
		if (last != null)
			nextFragmentSeq = last.seq+1;

		services = new IMessaging[]{
				new SMSTransport(this, app),
				new RockTransport(this, app)
		};

		// find the smallest mtu we must support
		int mtu = 0x7FFFFFFF;
		for (int i=0;i<services.length;i++){
			mtu = Math.min(services[i].getMTU(), mtu);
		}

		// round down to nearest 50 bytes to limit wasted iridium credits
		if ((mtu % 50)!=0)
			mtu -= mtu % 50;

		MTU = mtu;
		Log.v(TAG, "Using MTU = "+mtu);

		RandomAccessFile f = new RandomAccessFile(new File(store.root,"partial_fragment"), "rw");
		boolean empty = f.length() == 0;

		f.setLength(MTU+4);
		fragmentBuff = f.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, MTU+4);

		if (empty) {
			fragmentBuff.putInt(0, 0);
		}else{
			// recover pre-quit fragment?
			int size = fragmentBuff.getInt();
			fragmentBuff.position(size+fragmentBuff.position());
			if (size>0)
				nextFragmentSeq = fragmentBuff.getInt(4 + PeerId.LEN)+1;
			Log.v(TAG, "Reset fragment buffer to "+size);
		}

		memberWatcher = new StorageWatcher<TeamMember>(App.backgroundHandler, store, TeamMember.factory) {
			@Override
			protected void Visit(PeerId peer, RecordIterator<TeamMember> records) throws IOException {
				records.reset("enrolled");
				if (records.getOffset()==0 && records.next()) {
					Log.v(TAG, "Enrolling "+peer+" in the team list");
					membershipList.enroll(peer);
					records.next();
					records.mark("enrolled");
					App.backgroundHandler.removeCallbacks(sendRunner);
					App.backgroundHandler.postDelayed(sendRunner, 500);
				}
			}
		};
		memberWatcher.activate();

		formWatcher = new QueueWatcher<Form>(store, Form.factory) {
			@Override
			void generateMessage(PeerId peer, RecordIterator<Form> records) throws IOException {
				int pos = membershipList.getPosition(peer);
				Form form = records.read();
				Serialiser serialiser = new Serialiser();
				serialiser.putByte((byte) pos);
				serialiser.putTime(form.time, myTeam.epoc);
				Log.v(TAG, "Sending form from "+records.getOffset());
				fragmentMessage(FORM, serialiser.getResult());
			}
		};

		chatWatcher = new QueueWatcher<StoredChatMessage>(store, StoredChatMessage.factory) {
			@Override
			void generateMessage(PeerId peer, RecordIterator<StoredChatMessage> records) throws IOException {
				StoredChatMessage msg = records.read();

				Serialiser serialiser = new Serialiser();
				serialiser.putByte((byte)(int)membershipList.getPosition(peer));
				serialiser.putTime(msg.time.getTime(), myTeam.epoc);
				serialiser.putString(msg.message);

				Log.v(TAG, "Sending chat message  from "+records.getOffset());
				fragmentMessage(MESSAGE, serialiser.getResult());
			}
		};

		App.backgroundHandler.removeCallbacks(sendRunner);
		App.backgroundHandler.postDelayed(sendRunner, 500);
	}

	private static MessageQueue instance=null;
	// start fragmenting and queuing messages, if this is the app instance with that role
	public static void init(App app){
		try {
			if (instance!=null)
				throw new IllegalStateException();
			Team myTeam = app.teamStorage.getLastRecord(Team.factory, app.teamStorage.teamId);
			if (myTeam!=null && myTeam.leader.equals(app.networks.myId))
				instance = new MessageQueue(app, myTeam);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private static final byte CREATE_TEAM = 0;
	private static final byte DESTROY_TEAM = 1;
	private static final byte ENROLL=2;
	private static final byte LEAVE=3;
	private static final byte LOCATION=4;
	private static final byte MESSAGE=5;
	private static final byte FORM=6;

	public void fragmentMessage(byte messageType, byte[] messageBytes) throws IOException {
		fragmentMessage(messageType, messageBytes, messageBytes.length);
	}

	private boolean endFragment() throws IOException {
		if (fragmentBuff.position()<=4)
			return false;
		Log.v(TAG, "End fragment "+(nextFragmentSeq -1)+" "+fragmentBuff.position()+"?");
		fragmentBuff.flip();
		fragmentBuff.position(4);
		byte[] fragmentBytes = new byte[fragmentBuff.remaining()];
		fragmentBuff.get(fragmentBytes);
		fragments.append(new Fragment(fragmentBytes));
		fragmentBuff.clear();
		fragmentBuff.putInt(0, 0);
		return true;
	}

	private void beginFragment(int pieceLen) throws IOException {
		int seq = nextFragmentSeq++;
		Log.v(TAG, "Begin fragment "+seq);
		fragmentBuff.clear();
		fragmentBuff.position(4);
		store.teamId.write(fragmentBuff);
		fragmentBuff.putInt(seq);
		fragmentBuff.put((byte) (pieceLen > 255 ? 255 : pieceLen));
		fragmentBuff.putInt(0, fragmentBuff.position());
	}

	public void fragmentMessage(byte messageType, byte[] messageBytes, int length) throws IOException {
		int offset = -3;
		while (offset < length) {
			int len = length - offset;

			if (len > fragmentBuff.remaining())
				len = fragmentBuff.remaining();

			if (fragmentBuff.position() <= 4)
				beginFragment((offset == -3) ? 0 : len + 1);

			Log.v(TAG, "Adding "+messageType+" to seq "+(nextFragmentSeq-1)+" @"+fragmentBuff.position()+", offset "+offset+" len "+len+"/"+length);
			if (offset == -3) {
				fragmentBuff.put(messageType);
				fragmentBuff.putShort((short) length);
				offset += 3;
				len -= 3;
			}
			fragmentBuff.put(messageBytes, offset, len);
			offset += len;
			fragmentBuff.putInt(0, fragmentBuff.position());

			if (len > 254 || fragmentBuff.remaining()<3)
				endFragment();
		}
	}

	private boolean teamState() throws IOException{
		boolean ret = false;
		team.reset("sent");
		while (team.next()){
			Serialiser serialiser = new Serialiser();
			Team record = team.read();
			if (record.id == null) {
				serialiser.putRawLong(record.epoc);
				fragmentMessage(DESTROY_TEAM, serialiser.getResult());
			}else{
				serialiser.putRawLong(record.epoc);
				serialiser.putString(record.name);
				fragmentMessage(CREATE_TEAM, serialiser.getResult());
			}
			Team.factory.serialise(serialiser, record);
			ret = true;
		}
		team.mark("sent");
		return ret;
	}

	private boolean memberEnrollments() throws IOException{
		members.reset("sent");
		Serialiser serialiser = new Serialiser();
		boolean sent = false;

		while(members.next()){
			Membership m = members.read();
			int pos = membershipList.getPosition(m.peerId);
			if (pos > 255)
				continue;

			TeamMember member = membershipList.getTeamMember(m.peerId);
			if (member == null)
				break;

			serialiser.putByte((byte) pos);
			serialiser.putTime(m.time, myTeam.epoc);
			if (m.enroll) {
				serialiser.putString(member.employeeId);
				serialiser.putString(member.name);
				fragmentMessage(ENROLL, serialiser.getResult());
			}else{
				fragmentMessage(LEAVE, serialiser.getResult());
			}
			sent = true;
		}
		members.mark("sent");
		return sent;
	}

	private boolean hasNext() throws IOException {
		fragments.reset("sending");
		if (fragments.next())
			return true;
		members.reset("sent");
		if (members.next())
			return true;
		team.reset("sent");
		if (team.next())
			return true;
		if (chatWatcher.hasMessage())
			return true;
		if (formWatcher.hasMessage())
			return true;
		return false;
	}

	private boolean nextMessage() throws IOException {
		// Look for something to send in priority order;
		int seq = nextFragmentSeq;
		if (fragmentBuff.position()<=4)
			seq++;

		// keep trying to pack more data into the current fragment, before ending it
		while(nextFragmentSeq <= seq){
			if (teamState())
				continue;
			if (memberEnrollments())
				continue;
			// TODO locations HERE!?
			if (chatWatcher.nextMessage())
				continue;
			if (formWatcher.nextMessage())
				continue;
			// TODO delay closing the last fragment on a timer?
			Log.v(TAG, "Message queue ran out");
			return endFragment();
		}
		return true;
	}

	private void markAck(int seq) throws IOException {
		if (seq <-1 || seq >= nextFragmentSeq)
			throw new IllegalStateException("Sequence out of range ("+seq+", "+nextFragmentSeq+")");

		if (seq <0){
			fragments.start();
			fragments.next();
			fragments.mark("http_acked");
			return;
		}

		int first = 0;
		int last = nextFragmentSeq -1;
		{
			Fragment current = fragments.read();
			if (current != null) {
				int currentSeq = current.seq;
				if (seq >= currentSeq)
					first = currentSeq;
				else
					last = currentSeq;
			}
		}
		boolean forwards = (seq - first) < (last - seq);
		if (forwards && first == 0) {
			fragments.start();
			if (!fragments.next())
				throw new IllegalStateException("Seq "+seq+" not found!");
		}
		if (!forwards && last == nextFragmentSeq -1) {
			fragments.end();
			if (!fragments.prev())
				throw new IllegalStateException("Seq "+seq+" not found!");
		}
		while(true){
			Fragment fragment = fragments.read();
			if (fragment.seq == seq) {
				fragments.next();
				fragments.mark("http_acked");
				return;
			}
			if (!(forwards ? fragments.next() : fragments.prev()))
				break;
		}
		throw new IllegalStateException("Seq "+seq+" not found!");
	}

	private String read(InputStream stream) throws IOException {
		final char[] buffer = new char[512];
		final StringBuilder out = new StringBuilder();
		Reader in = new InputStreamReader(stream, "UTF-8");
		while(true) {
			int rsz = in.read(buffer, 0, buffer.length);
			if (rsz < 0)
				break;
			out.append(buffer, 0, rsz);
		}
		in.close();
		return out.toString();
	}

	private void sendViaHttp(){
		String baseUrl = app.getPrefs().getString(App.BASE_SERVER_URL, null);
		if (baseUrl == null)
			return;

		NetworkInfo network = connectivityManager.getActiveNetworkInfo();
		if (network == null || !network.isConnected())
			return;

		try {
			fragments.reset("http_acked");
			// If we've already acked them all, skip the connection to the server
			if (fragments.next()){
				// double check the latest ack sequence with the server
				URL url = new URL(baseUrl+"/succinct/api/v1/ack/"+store.teamId);
				HttpURLConnection connection = (HttpURLConnection)url.openConnection();
				connection.setRequestProperty("Connection", "keep-alive");
				connection.connect();
				int response = connection.getResponseCode();
				if (response == 404){
					markAck(-1);
				}else if (response == 200){
					markAck(Integer.parseInt(read((InputStream)connection.getContent())));
				}else{
					Log.e(TAG, "Unexpected http response code "+response);
					return;
				}
			}

			while(true){
				Fragment sendFragment = fragments.read();
				if (sendFragment == null){
					// If we reach the end of the fragment list, we can avoid other transports
					fragments.mark("sending");
					if (!(nextMessage() && fragments.next())) {
						return;
					}
					continue;
				}

				URL url = new URL(baseUrl+"/succinct/api/v1/uploadFragment/"+store.teamId);
				HttpURLConnection connection = (HttpURLConnection)url.openConnection();
				connection.setRequestMethod("POST");
				connection.setRequestProperty("Connection", "keep-alive");
				connection.setFixedLengthStreamingMode(sendFragment.bytes.length);
				connection.connect();
				OutputStream out = connection.getOutputStream();
				out.write(sendFragment.bytes);
				out.close();
				int response = connection.getResponseCode();
				if (response!=200){
					Log.e(TAG, "Unexpected http response code "+response);
					return;
				}

				markAck(Integer.parseInt(read((InputStream)connection.getContent())));
			}
		} catch (IOException e) {
			Log.e(TAG, e.getMessage(), e);
		}
	}

	private void sendNextFragment(){
		Log.v(TAG, "sendNextFragment");
		try {
			// Check if we have or can build a new fragment
			if (!hasNext())
				return;

			// Check that we have at least one service that is ready to deliver a fragment
			int status = IMessaging.UNAVAILABLE;
			for (int i = 0; i < services.length && status == IMessaging.UNAVAILABLE; i++){
				status = services[i].checkAvailable();
			}
			if (status != IMessaging.SUCCESS)
				return;

			// Now we can actually fragment the next message
			// and try to send fragments

			fragments.reset("sending");
			while (true) {
				if (!fragments.next()){
					if (!(nextMessage() && fragments.next())) {
						// tell each service they can teardown their connection now.
						// since we have run out of fragments
						for (int i = 0; i < services.length; i++)
							services[i].done();
						break;
					}
				}
				status = IMessaging.UNAVAILABLE;

				Fragment send = fragments.read();
				Log.v(TAG, "Attempting to send fragment @"+fragments.getOffset());

				for (int i = 0; i < services.length && status == IMessaging.UNAVAILABLE; i++) {
					status = services[i].trySend(send);
					Log.v(TAG, "Service "+i+" returned "+status);
				}

				if (status != IMessaging.SUCCESS)
					break;
			}
			fragments.mark("sending");
		}catch (Exception e){
			throw new IllegalStateException(e);
		}
	}

	private final Runnable sendRunner = new Runnable() {
		@Override
		public void run() {
			sendViaHttp();
			sendNextFragment();
		}
	};

	// one of our services might be ready for a new fragment
	public void onStateChanged(){
		App.backgroundHandler.removeCallbacks(sendRunner);
		App.backgroundHandler.post(sendRunner);
	}
}
