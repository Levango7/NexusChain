package org.nexus.crypto;

import org.bouncycastle.util.encoders.Hex;
import com.kosprov.jargon2.api.*;

public class ArgonManage {
	public static enum Type {
		ARGON2d,
		ARGON2i,
		ARGON2id;

		Type(String value, String valueCapitalized) {
			this.value = value;
			this.valueCapitalized = valueCapitalized;
		}

		private String value = this.name().toLowerCase();
		private String valueCapitalized;

		Type() {
			this.valueCapitalized = Character.toUpperCase(this.value.charAt(0)) + this.value.substring(1);
		}

		public String getValue() {
			return this.value;
		}

		public String getValueCapitalized() {
			return this.valueCapitalized;
		}
	}

	private Jargon2.Type type;
	private byte[] salt;
	public static final int MEMORY_COST = 20480;
	public static final int TIME_COST = 4;
	public static final int PARALLELISM = 2;


	public ArgonManage() {
	}

	public ArgonManage(Type type) {
		this.type = Jargon2.Type.valueOf(type.name());
	}

	public ArgonManage(byte[] salt) {
		this.salt = salt;
	}

	public ArgonManage(Type type, byte[] salt) {
		this.type = Jargon2.Type.valueOf(type.name());
		this.salt = salt;
	}

	public void setSalt(byte[] salt) {
		this.salt = salt;
	}

	public byte[] hash(byte[] in){
		String password = Hex.toHexString(salt) + Hex.toHexString(in);
		return Jargon2.jargon2Hasher().type(this.type).memoryCost(MEMORY_COST)
				.timeCost(TIME_COST).parallelism(PARALLELISM).salt(salt)
				.password(password.getBytes()).rawHash();
	}

	public String kdf(){
		return this.type.name().toLowerCase();
	}

	public byte[] getSalt() {
		return salt;
	}
}