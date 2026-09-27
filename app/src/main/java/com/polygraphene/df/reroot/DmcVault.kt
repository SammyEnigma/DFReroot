package com.polygraphene.df.reroot

sealed interface DmcResult {
    data class Unsupported(val detail: String) : DmcResult
    data class Available(val lock: Int, val maint: Int, val at: Int, val odinAllowed: Boolean) : DmcResult
}

sealed interface DmcWriteResult {
    data class Done(val wrote: Boolean) : DmcWriteResult
    data class Skipped(val reason: String) : DmcWriteResult
    data class Failed(val reason: String) : DmcWriteResult
}

object DmcVault {
    fun read(): DmcResult {
        val managerClass = try {
            Class.forName("com.samsung.android.service.vaultkeeper.VaultKeeperManager")
        } catch (t: Throwable) {
            return DmcResult.Unsupported("no VaultKeeper (${t.javaClass.simpleName})")
        }
        return try {
            val instance = managerClass.getMethod("getInstance", String::class.java)
                .invoke(null, "DMC")
                ?: return DmcResult.Unsupported("no DMC instance")
            val data = managerClass.getMethod("read", Int::class.javaPrimitiveType)
                .invoke(instance, 1) as? ByteArray
                ?: return DmcResult.Unsupported("DMC read failed")
            if (data.size != 32) return DmcResult.Unsupported("DMC bad length ${data.size}")
            val lock = data[0].toInt()
            val maint = data[1].toInt()
            val at = data[2].toInt()
            DmcResult.Available(lock, maint, at, lock == 0 || maint == 1 || at == 1)
        } catch (t: Throwable) {
            DmcResult.Unsupported(t.javaClass.simpleName + (t.message?.let { ": $it" } ?: ""))
        }
    }

    fun writeAtFlag(): DmcWriteResult {
        val managerClass = try {
            Class.forName("com.samsung.android.service.vaultkeeper.VaultKeeperManager")
        } catch (t: Throwable) {
            return DmcWriteResult.Skipped("no VaultKeeper (${t.javaClass.simpleName})")
        }
        return try {
            val instance = managerClass.getMethod("getInstance", String::class.java)
                .invoke(null, "DMC")
                ?: return DmcWriteResult.Skipped("no DMC instance")
            val read = managerClass.getMethod("read", Int::class.javaPrimitiveType)
            val write = managerClass.getMethod("write", Int::class.javaPrimitiveType, ByteArray::class.java)
            val data = read.invoke(instance, 1) as? ByteArray
                ?: return DmcWriteResult.Failed("DMC read failed")
            if (data.size != 32) return DmcWriteResult.Failed("DMC bad length ${data.size}")
            if (data[2].toInt() == 1) return DmcWriteResult.Done(false)
            data[2] = 1
            val rc = write.invoke(instance, 1, data) as? Int ?: -1
            if (rc != 0) return DmcWriteResult.Failed("DMC write rc=$rc")
            val verify = read.invoke(instance, 1) as? ByteArray
            if (verify == null || verify.size != 32 || verify[2].toInt() != 1) {
                return DmcWriteResult.Failed("DMC verify failed")
            }
            DmcWriteResult.Done(true)
        } catch (t: Throwable) {
            DmcWriteResult.Failed(t.javaClass.simpleName + (t.message?.let { ": $it" } ?: ""))
        }
    }
}
