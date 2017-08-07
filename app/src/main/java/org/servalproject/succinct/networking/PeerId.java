package org.servalproject.succinct.networking;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;

public class PeerId {
	// How long does an ID need to be?
	private static final int LEN=4;
	private final byte[] id;

	PeerId(byte[] bytes){
		this.id = bytes;
	}
	public PeerId(ByteBuffer buff){
		this.id = new byte[LEN];
		buff.get(id);
	}
	PeerId(){
		this.id = new byte[LEN];
		new SecureRandom().nextBytes(id);
	}

	public void write(ByteBuffer buff){
		buff.put(id);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;

		PeerId peerId = (PeerId) o;

		return Arrays.equals(id, peerId.id);

	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(id);
	}
}
