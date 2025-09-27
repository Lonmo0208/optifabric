package me.modmuss50.optifabric.mod;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.fabricmc.tinyremapper.IMappingProvider.Member;
import me.modmuss50.optifabric.util.RemappingUtils;
import com.chocohead.mm.api.ClassTinkerers;

// 修复：OptifabricSetup.isPresent()方法引用正确
public class TextSetup implements Runnable {
	@Override
	public void run() {
		// 修复：调用OptifabricSetup的静态isPresent方法
		if (OptifabricSetup.isPresent("minecraft", ">=1.19")) {
			ClassTinkerers.addTransformation("me/modmuss50/optifabric/mod/Text", node -> {
				String mutableText = RemappingUtils.getClassName("class_5250");
				for (MethodNode method : node.methods) {
					if ("literal".equals(method.name) && method.desc.startsWith("(Ljava/lang/String;)")) {
						InsnList instructions = new InsnList();
						instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
						Member literal = RemappingUtils.mapMethod("class_2561", "method_43470", "(Ljava/lang/String;)Lnet/minecraft/class_5250;");
						instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, literal.owner, literal.name, literal.desc, true));
						instructions.add(new InsnNode(Opcodes.ARETURN));
						method.instructions = instructions;
					} else {
						for (AbstractInsnNode insn : method.instructions) {
							if (insn.getType() == AbstractInsnNode.METHOD_INSN) {
								MethodInsnNode minsn = ((MethodInsnNode) insn);
								if (mutableText.equals(minsn.owner)) {
									if (minsn.getOpcode() != Opcodes.INVOKESTATIC) minsn.setOpcode(Opcodes.INVOKEVIRTUAL);
									minsn.itf = false;
								}
							}
						}
					}
				}
			});
		}
	}
}