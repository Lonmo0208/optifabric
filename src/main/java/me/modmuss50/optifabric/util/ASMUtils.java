package me.modmuss50.optifabric.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

public class ASMUtils {
	public static ClassNode readClass(byte[] bytes) {
		try {
			return readClass(new ClassReader(Objects.requireNonNull(bytes, "Cannot read null class bytes")));
		} catch (Exception e) {
			System.err.println("[OptiFabric] 读取类时出错，尝试修复...");
			// 尝试使用更宽松的设置
			ClassReader reader = new ClassReader(bytes);
			ClassNode node = new ClassNode();
			reader.accept(node, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
			return node;
		}
	}

	public static ClassNode readClass(File file) throws IOException {
		try (InputStream in = new FileInputStream(Objects.requireNonNull(file, "Cannot read null file"))) {
			return readClass(new ClassReader(in));
		}
	}

	public static ClassNode readClass(ZipFile jar, ZipEntry entry) throws IOException {
		try (InputStream in = jar.getInputStream(entry)) {
			return readClass(new ClassReader(Objects.requireNonNull(in, "Entry not present in jar")));
		}
	}

	private static ClassNode readClass(ClassReader reader) {
		ClassNode node = new ClassNode();
		// 修改：使用EXPAND_FRAMES而不是SKIP_FRAMES，确保正确处理栈映射帧
		reader.accept(node, ClassReader.EXPAND_FRAMES);
		return node;
	}

	// 新增方法：将ClassNode转换为字节数组，并自动计算栈映射帧
	public static byte[] writeClass(ClassNode classNode, boolean computeFrames) {
		ClassWriter writer = new ClassWriter(computeFrames ? ClassWriter.COMPUTE_FRAMES : ClassWriter.COMPUTE_MAXS);
		classNode.accept(writer);
		return writer.toByteArray();
	}

	// 新增方法：快速写入类（默认计算栈映射帧）
	public static byte[] writeClass(ClassNode classNode) {
		return writeClass(classNode, true);
	}
}