package me.modmuss50.optifabric.mod;

import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.MethodNode;

import it.unimi.dsi.fastutil.objects.Object2IntArrayMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;

import net.fabricmc.loader.api.FabricLoader;

import com.chocohead.mm.api.ClassTinkerers;

import me.modmuss50.optifabric.patcher.ClassCache;
import me.modmuss50.optifabric.patcher.fixes.OptifineFixer;
import me.modmuss50.optifabric.util.ASMUtils;

public class OptifineInjector {
	private static Set<String> patched = new HashSet<>();
	private final ClassCache classCache;

	public OptifineInjector(ClassCache classCache) {
		this.classCache = classCache;
	}

	public Optional<ClassNode> predictFuture(String className) {
		byte[] bytes = classCache.getClass(className);
		return bytes != null ? Optional.of(ASMUtils.readClass(bytes)) : Optional.empty();
	}

	public void setup() {
		Consumer<ClassNode> transformer = target -> {
			//Avoid double patching things, not that this should happen
			if (!patched.add(target.name)) {
				System.err.println("Already patched " + target.name);
				return;
			}

			//Skip applying class patches we veto
			if (OptifineFixer.INSTANCE.shouldSkip(target.name)) {
				return;
			}

			//Remember the access we started with
			Object2IntMap<String> memberToAccess = new Object2IntArrayMap<>(target.methods.size());
			memberToAccess.defaultReturnValue(-1);
			for (MethodNode method : target.methods) {
				memberToAccess.put(method.name + method.desc, method.access);
			}
			for (FieldNode field : target.fields) {
				memberToAccess.put(field.name + ' ' + field.desc, field.access);
			}

			//I cannot imagine this being very good at all
			ClassNode source = getSourceClassNode(target);

			//Patch the class if required
			OptifineFixer.INSTANCE.getFixers(target.name).forEach(classFixer -> classFixer.fix(source, target));

			target.methods = source.methods;
			target.fields = source.fields;
			target.interfaces = source.interfaces;
			target.superName = source.superName;

			// 修改：修复栈映射帧问题
			fixStackMapFrames(target);

			// Lets make every class we touch match the access it used to have
			target.access = widerAccess(target.access, source.access);
			for (MethodNode method : target.methods) {
				int access = memberToAccess.getInt(method.name + method.desc);
				if (access != -1) method.access = widerAccess(access, method.access);
			}
			for (FieldNode field : target.fields) {
				int access = memberToAccess.getInt(field.name + ' ' + field.desc);
				if (access != -1) field.access = widerAccess(access, field.access);
			}
		};

		for (String name : classCache.getClasses()) {
			ClassTinkerers.addReplacement(name, transformer);
		}
	}

	// 新增方法：修复栈映射帧
	private void fixStackMapFrames(ClassNode classNode) {
		for (MethodNode method : classNode.methods) {
			// 如果方法有代码，检查栈映射帧
			if (method.instructions != null && method.instructions.size() > 0) {
				boolean hasFrames = false;
				for (AbstractInsnNode insn : method.instructions.toArray()) {
					if (insn instanceof FrameNode) {
						hasFrames = true;
						FrameNode frame = (FrameNode) insn;
						// 确保帧不为null
						if (frame.local == null) {
							// 修复null的局部变量帧
							frame.local = new java.util.ArrayList<>();
						}
						if (frame.stack == null) {
							// 修复null的栈帧
							frame.stack = new java.util.ArrayList<>();
						}
					}
				}

				// 如果方法没有栈映射帧，我们需要重新计算它们
				if (!hasFrames && !method.name.startsWith("<")) {
					// 重新计算方法，使用ASM自动生成栈映射帧
					try {
						ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
						classNode.accept(cw);
					} catch (Exception e) {
						System.err.println("[OptiFabric] 警告: 无法为 " + classNode.name + "." + method.name + " 重新计算栈映射帧: " + e.getMessage());
					}
				}
			}
		}
	}

	private static int widerAccess(int origin, int target) {
		if (!Modifier.isFinal(origin)) target &= ~Modifier.FINAL;

		switch (target & 0x7) {
			case Modifier.PUBLIC:
				return target;

			case Modifier.PROTECTED:
				return Modifier.isPublic(origin) ? (target & (~0x7)) | Modifier.PUBLIC : target;

			case 0:
				return Modifier.isPrivate(origin) ? target : (target & (~0x7)) | (origin & 0x7);

			case Modifier.PRIVATE:
				return (target & (~0x7)) | (origin & 0x7);

			default:
				if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
					throw new AssertionError("Unexpected access: " + target + " (transformed from " + origin + ')');
				}

				return target;
		}
	}

	private ClassNode getSourceClassNode(ClassNode classNode) {
		byte[] bytes = classCache.popClass(classNode.name);
		if(bytes == null) {
			throw new RuntimeException("Failed to find patched class for: " + classNode.name);
		}
		return ASMUtils.readClass(bytes);
	}
}