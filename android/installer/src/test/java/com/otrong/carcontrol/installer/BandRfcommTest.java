package com.otrong.carcontrol.installer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class BandRfcommTest {
    @Test
    public void reconnectFallsBackWhenDirectChannelsAreUnavailable() throws Exception {
        List<BandRfcomm.Route> attempted = new ArrayList<>();
        String result = BandRfcomm.tryRoutes(true, null, route -> {
            attempted.add(route);
            if (route == BandRfcomm.Route.SPP_SECURE) return "connected";
            throw new IOException();
        });
        assertEquals("connected", result);
        assertEquals(Arrays.asList(BandRfcomm.Route.DIRECT_SECURE,
                BandRfcomm.Route.DIRECT_COMPAT, BandRfcomm.Route.SPP_SECURE), attempted);
    }

    @Test
    public void prefersTheLastSuccessfulRouteAndDoesNotContinueAfterCancellation() {
        List<BandRfcomm.Route> attempted = new ArrayList<>();
        assertThrows(InterruptedException.class,
                () -> BandRfcomm.tryRoutes(true, BandRfcomm.Route.SPP_COMPAT, route -> {
                    attempted.add(route);
                    throw new InterruptedException();
                }));
        assertEquals(Collections.singletonList(BandRfcomm.Route.SPP_COMPAT), attempted);
    }

    @Test
    public void keepsTheStandardConnectionFirstOnInitialSetup() {
        List<BandRfcomm.Route> attempted = new ArrayList<>();
        IOException failure = assertThrows(IOException.class, () -> BandRfcomm.tryRoutes(false, null, route -> {
            attempted.add(route);
            throw new IOException();
        }));
        assertEquals(BandRfcomm.Route.SPP_SECURE, attempted.get(0));
        assertEquals(4, failure.getSuppressed().length);
    }

    @Test
    public void reportsWhenDirectRoutesFailBeforeFallbackSucceeds() throws Exception {
        BandRfcomm.RouteOutcome<String> outcome = BandRfcomm.tryRoutesOutcome(true, null, route -> {
            if (route == BandRfcomm.Route.SPP_SECURE) return "fallback";
            throw new IOException("eof");
        });
        assertEquals(BandRfcomm.Route.SPP_SECURE, outcome.route());
        assertEquals(true, outcome.directRejected());

        BandRfcomm.RouteOutcome<String> direct = BandRfcomm.tryRoutesOutcome(true, null, route -> "direct");
        assertEquals(BandRfcomm.Route.DIRECT_SECURE, direct.route());
        assertEquals(false, direct.directRejected());
    }
}
