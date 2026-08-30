package com.sultansgame.modmanager.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ManagerNavigationTest {
    @Test fun workshopIsHiddenByDefault() {
        assertEquals(
            listOf(Destination.Start, Destination.Library, Destination.SaveEditor, Destination.Settings),
            visibleDestinations(false),
        )
    }

    @Test fun enabledWorkshopFollowsLibrary() {
        assertEquals(
            listOf(
                Destination.Start,
                Destination.Library,
                Destination.Acquire,
                Destination.SaveEditor,
                Destination.Settings,
            ),
            visibleDestinations(true),
        )
    }

    @Test fun saveEditorIsAlwaysReachable() {
        assertEquals(Destination.SaveEditor, effectiveDestination(Destination.SaveEditor, false))
        assertEquals(Destination.SaveEditor, effectiveDestination(Destination.SaveEditor, true))
    }

    @Test fun hiddenWorkshopFallsBackToLibrary() {
        assertEquals(Destination.Library, effectiveDestination(Destination.Acquire, false))
    }

    @Test fun invalidRouteFallsBackToStart() {
        assertEquals(Destination.Start, destinationFromRoute("unknown"))
    }

    @Test fun saveEditorRouteRoundTrips() {
        assertEquals(Destination.SaveEditor, destinationFromRoute(Destination.SaveEditor.name))
    }
}
