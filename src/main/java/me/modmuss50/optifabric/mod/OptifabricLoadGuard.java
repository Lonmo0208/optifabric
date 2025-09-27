package me.modmuss50.optifabric.mod;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

public class OptifabricLoadGuard implements PreLaunchEntrypoint {
	@Override
	public void onPreLaunch() {
		System.out.println("[OptiFabric] 加载保护启动");

		// 临时禁用字节码验证
		System.setProperty("fabric.skipMinecraftVanillaCheck", "true");
		System.setProperty("fabric.skipMcProvider", "true");

		// 尝试禁用验证（可能不适用于所有JVM）
		try {
			System.setProperty("java.lang.VerifyError.suppress", "true");
		} catch (Exception e) {
			// 忽略
		}

		// 添加额外的系统属性以改善兼容性
		System.setProperty("optifabric.forceCompatibility", "true");
		System.setProperty("optifabric.skipVersionCheck", "true");

		System.out.println("[OptiFabric] 加载保护完成");

		//The first class loaded cannot have any Mixins for it or extra Mixin configs added won't apply
		//They would apply by bumping the Mixin phase afterwards, but this is a much cleaner solution
		//There is good precedent as this as a solution to the problem, first found here:
		//https://github.com/ReplayMod/ReplayMod/commit/27edfcb4f3cd0eac0c7fb24e87ee3fa67324ab0a
	}
}