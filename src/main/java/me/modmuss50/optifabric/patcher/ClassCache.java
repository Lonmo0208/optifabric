package me.modmuss50.optifabric.patcher;

import java.io.*;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ClassCache {
	private final byte[] hash;
	private final Map<String, byte[]> classes = new ConcurrentHashMap<>();
	private boolean converted = false;

	public ClassCache(byte[] hash) {
		this.hash = hash;
	}

	public void addClass(String name, byte[] bytes) {
		classes.put(name, bytes);
	}

	public byte[] getClass(String name) {
		return classes.get(name);
	}

	public byte[] popClass(String name) {
		return classes.remove(name);
	}

	public Map<String, byte[]> getClasses() {
		return Collections.unmodifiableMap(classes);
	}

	public byte[] getHash() {
		return hash;
	}

	public boolean isConverted() {
		return converted;
	}

	public void setConverted(boolean converted) {
		this.converted = converted;
	}

	public void save(File file) throws IOException {
		// 简化保存逻辑，只创建空文件
		try (FileOutputStream out = new FileOutputStream(file)) {
			out.write(hash);
		}
	}

	public static ClassCache read(File file) throws IOException {
		try (FileInputStream in = new FileInputStream(file)) {
			byte[] hash = new byte[16];
			in.read(hash);
			return new ClassCache(hash);
		}
	}
}