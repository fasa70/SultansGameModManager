package com.sultansgame.modmanager.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ManagerNavigationTest {
    @Test fun workshopIsHiddenByDefault() {
        assertEquals(listOf(Destination.Start, Destination.Library, Destination.Settings), visibleDestinations(false))
    }

    @Test fun enabledWorkshopFollowsLibrary() {
        assertEquals(listOf(Destination.Start, Destination.Library, Destination.Acquire, Destination.Settings), visibleDestinations(true))
    }

    @Test fun hiddenWorkshopFallsBackToLibrary() {
        assertEquals(Destination.Library, effectiveDestination(Destination.Acquire, false))
    }

    @Test fun invalidRouteFallsBackToStart() {
        assertEquals(Destination.Start, destinationFromRoute("unknown"))
    }
}
