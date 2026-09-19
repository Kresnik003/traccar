package org.traccar.api.resource;

import org.junit.jupiter.api.Test;
import org.traccar.helper.DiagnosticReport;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class DiagnosticoResourceTest {

    /**
     * This build has every patch, so every marker must say yes here. A marker
     * with a typo in it says NO on every jar, which is the opposite lie.
     */
    @Test
    public void everyMarkerIsInThisBuild() {
        List<String> missing = new ArrayList<>();
        for (String marker : DiagnosticoResource.PATCH_CLASSES) {
            if (!DiagnosticReport.present(marker, getClass().getClassLoader())) {
                missing.add(marker);
            }
        }
        assertEquals(List.of(), missing);
    }

}
