package com.atsuishio.superbwarfare.client.overlay

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.ceil

class GroundHudLayoutTest {
    @Test fun wrappedAlertsAvoidPassengerNamesInCompactViews() {
        // Seven occupied seats reach the alert rows at minimum-height GUI scales.
        for (height in listOf(240, 270)) {
            assertEquals(124, GroundHudLayout.alertLeft(height, 7, 50, 74, 9))
        }
        assertEquals(12, GroundHudLayout.alertLeft(360, 7, 50, 74, 9))
        assertEquals(12, GroundHudLayout.alertLeft(240, 0, 50, 74, 9))
        assertEquals(12, GroundHudLayout.alertLeft(240, 1, 50, 74, 9))
        assertEquals(34, GroundHudLayout.passengerRowY(240, 6))
        assertTrue(124 - 3 > 42 + 72) // Dark alert backing also clears the name column.
    }

    @Test fun resolutionsAndGuiScalesKeepSixSystemsLabelsAndStatusSeparated() {
        for((pixelsW,pixelsH) in listOf(640 to 480,1280 to 720,1920 to 1080,2560 to 1440,1280 to 1024,3440 to 1440)) {
            for(requested in listOf(1,2,3,4,0)) {
                var scale=1
                while((requested==0 || scale<requested) && pixelsW/(scale+1)>=320 && pixelsH/(scale+1)>=240) scale++
                val w=ceil(pixelsW.toDouble()/scale).toInt();val h=ceil(pixelsH.toDouble()/scale).toInt()
                for(count in 1..6) {
                    val layout=GroundHudLayout(w,h,count)
                    assertTrue(layout.amberLineY>=0,"$pixelsW x $pixelsH scale=$requested count=$count")
                    assertTrue(layout.amberLineY+4+18 < layout.firstRowTop-23)
                    assertTrue(layout.shellCenterX-layout.shellWidth/2>=0)
                    assertTrue(layout.shellCenterX+layout.shellWidth/2<=w)
                    assertEquals(w-12,layout.panelRight)
                    for(i in 0 until count) {
                        val x=layout.slotLeft(i);val y=layout.slotTop(i)
                        assertTrue(x>=124 && x+40<=w-12)
                        assertTrue(y-23>=0 && y+layout.slotHeight+3+9<=h)
                        assertTrue(y+27+9<y+layout.slotHeight)
                        for(j in 0 until i) {
                            val xx=layout.slotLeft(j);val yy=layout.slotTop(j)
                            assertTrue(x>=xx+40 || xx>=x+40 || y-23>=yy+layout.slotHeight+12 || yy-23>=y+layout.slotHeight+12)
                        }
                    }
                }
            }
        }
    }
}
