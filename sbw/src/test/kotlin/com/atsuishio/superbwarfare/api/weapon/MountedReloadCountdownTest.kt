package com.atsuishio.superbwarfare.api.weapon

import com.atsuishio.superbwarfare.data.gun.subdata.Reload
import com.atsuishio.superbwarfare.data.gun.value.ReloadState
import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MountedReloadCountdownTest {
    @Test fun `credited short reloads always reach completion exactly once`() {
        for (duration in listOf(0, 1, 2, 100, 160)) for (offset in 1..2) for (credit in listOf(0, 66, 99)) {
            val reload = Reload(CompoundTag())
            reload.setState(ReloadState.EMPTY_RELOADING)
            reload.setTime(duration + offset)
            reload.setPendingProgressPercent(credit)
            reload.applyPendingProgress()
            var completions = 0
            repeat(duration + offset + 3) {
                reload.reduce()
                if (reload.countdownFinished()) {
                    completions++
                    reload.setState(ReloadState.NOT_RELOADING)
                    reload.setTime(0)
                }
            }
            assertEquals(1, completions, "duration=$duration offset=$offset credit=$credit")
        }
    }

    @Test fun `persisted zero timer reload recovers but idle and iterative states do not complete`() {
        val reload = Reload(CompoundTag())
        assertFalse(reload.countdownFinished())
        reload.setState(ReloadState.EMPTY_RELOADING)
        assertTrue(reload.countdownFinished())
        for (stage in 1..3) {
            reload.setStage(stage)
            assertFalse(reload.countdownFinished())
        }
    }
}
