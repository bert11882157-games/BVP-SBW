package com.atsuishio.superbwarfare.client.aircraft

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AircraftLoadoutLayoutTest {
    @Test fun tightlyProjectedPylonsRemainIndividuallyClickableAcrossGuiScales() {
        for((width,height) in listOf(320 to 240,480 to 270,640 to 360,960 to 540)) {
            val buttonWidth=AircraftLoadoutLayout.BUTTON_WIDTH
            val anchors=(0 until 12).map { AircraftLoadoutLayout.Anchor("p$it","$it",width/2+it*3-18,height/2) }
            val placed=AircraftLoadoutLayout.place(anchors,width,height,0)
            assertEquals(12,placed.size)
            for((i,a) in placed.withIndex()) {
                assertTrue(a.x>=0 && a.x+buttonWidth<=width && a.y>=48 && a.y+20<height)
                for(b in placed.take(i)) assertFalse(a.x<b.x+buttonWidth && a.x+buttonWidth>b.x && a.y<b.y+20 && a.y+20>b.y)
            }
        }
    }
}
