package de.luh.vss.chat.common;

import java.io.Serializable;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Objects;

public class User implements Serializable { // Implement Serializable

	private static final long serialVersionUID = 1L; // Add serialVersionUID

	private final UserId userId;
	private final InetSocketAddress endpoint; // Use InetSocketAddress which is Serializable

	public User(UserId userId, InetSocketAddress endpoint) {
		this.userId = userId;
		this.endpoint = endpoint;
	}

	public UserId getUserId() {
		return userId;
	}

	public InetSocketAddress getEndpoint() {
		return endpoint;
	}



	public static class UserId implements Serializable { // Implement Serializable

		private static final long serialVersionUID = 1L;

		public static UserId BROADCAST = new UserId(0);

		private final int id;

		public UserId(int id) {
			if (id < 0 || id > 9999)
				throw new IllegalArgumentException("wrong user ID");
			this.id = id;
		}

		public int id() {
			return id;
		}

		@Override
		public String toString() {
			return String.valueOf(id);
		}

		// Override equals and hashCode for proper map operations
		@Override
		public boolean equals(Object obj) {
			if (this == obj) return true;
			if (obj == null || getClass() != obj.getClass()) return false;
			UserId userId = (UserId) obj;
			return id == userId.id;
		}

		@Override
		public int hashCode() {
			return Objects.hash(id);
		}
	}
}
