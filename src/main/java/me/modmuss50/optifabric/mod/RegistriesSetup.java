package me.modmuss50.optifabric.mod;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import me.modmuss50.optifabric.util.RemappingUtils;
import com.chocohead.mm.api.ClassTinkerers;

// 修复：OptifabricSetup.isPresent()方法引用正确
public class RegistriesSetup implements Runnable {
	@Override
	public void run() {
		// 修复：调用OptifabricSetup的静态isPresent方法
		if (OptifabricSetup.isPresent("minecraft", ">1.19.2")) {
			ClassTinkerers.addTransformation("me/modmuss50/optifabric/mod/Registries", node -> {
				for (MethodNode method : node.methods) {
					if ("getID".equals(method.name)) {
						String registry = RemappingUtils.getClassName("class_2378");
						String oldBlocks = RemappingUtils.mapFieldName("class_2378", "field_11146", "Lnet/minecraft/class_7922;");
						String simpleDefaultedRegistry = RemappingUtils.getClassName("class_2348");
						String defaultedRegistry = RemappingUtils.getClassName("class_7922");

						for (AbstractInsnNode insn : method.instructions) {
							switch (insn.getType()) {
								case AbstractInsnNode.FIELD_INSN: {
									FieldInsnNode finsn = (FieldInsnNode) insn;
									if (registry.equals(finsn.owner) && oldBlocks.equals(finsn.name)) {
										finsn.owner = RemappingUtils.getClassName("class_7923"); // Registries
										finsn.name = RemappingUtils.mapFieldName("class_7923", "field_41175", "Lnet/minecraft/class_7922;"); // BLOCK
										assert simpleDefaultedRegistry.regionMatches(0, finsn.desc, 1, simpleDefaultedRegistry.length());
										finsn.desc = 'L' + defaultedRegistry + ';';
									}
									break;
								}
								case AbstractInsnNode.METHOD_INSN: {
									MethodInsnNode minsn = (MethodInsnNode) insn;
									if (simpleDefaultedRegistry.equals(minsn.owner)) {
										minsn.setOpcode(Opcodes.INVOKEINTERFACE);
										minsn.owner = defaultedRegistry;
										minsn.itf = true;
									}
									break;
								}
							}
						}
						break;
					}
				}
			});
		}
	}
}