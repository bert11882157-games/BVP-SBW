package com.atsuishio.superbwarfare.client.camera

import org.junit.jupiter.api.Test

/** Run the standalone full-sphere consumer fixtures on the real project's runtime classpath. */
class FixedWingInversionPresentationJUnitTest {
    @Test fun cameraRiderAndMouseBasisRemainCoherentThroughInversion() {
        FixedWingInversionPresentationTest.main(emptyArray())
    }
}
