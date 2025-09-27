package me.modmuss50.optifabric.mod;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * 紧急修复类，用于处理严重的类加载问题
 */
public class EmergencyFix {

    /**
     * 修复 def 类的字节码问题
     */
    public static byte[] fixDefClass(byte[] classBytes) {
        try {
            System.out.println("[OptiFabric] 应用紧急修复到 def 类");

            org.objectweb.asm.ClassReader reader = new org.objectweb.asm.ClassReader(classBytes);
            org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);

            ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    // 修复可能的问题方法
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

                    // 特别处理可能引起问题的初始化方法
                    if ("<clinit>".equals(name) || "<init>".equals(name)) {
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitMaxs(int maxStack, int maxLocals) {
                                // 确保栈和局部变量大小合理
                                super.visitMaxs(Math.max(maxStack, 2), Math.max(maxLocals, 1));
                            }
                        };
                    }

                    return mv;
                }
            };

            reader.accept(visitor, 0);
            byte[] fixedBytes = writer.toByteArray();
            System.out.println("[OptiFabric] 紧急修复完成");
            return fixedBytes;
        } catch (Exception e) {
            System.err.println("[OptiFabric] 紧急修复失败: " + e.getMessage());
            return classBytes; // 返回原始字节码
        }
    }

    /**
     * 检查类是否需要紧急修复
     */
    public static boolean needsEmergencyFix(String className) {
        // 检查是否是已知的问题类
        return "def".equals(className) ||
                className.contains("GLX") ||
                className.contains("dej") ||
                className.contains("OpenGL");
    }
}